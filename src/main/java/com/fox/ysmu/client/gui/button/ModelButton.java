package com.fox.ysmu.client.gui.button;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.ysmu;
import com.fox.ysmu.client.gui.ModelSelectionTarget;
import com.fox.ysmu.client.renderer.PreviewRefreshPolicy;
import com.fox.ysmu.util.FboCache;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class ModelButton extends GuiButton {
    private final static ResourceLocation ICON = new ResourceLocation(ysmu.MODID, "texture/icon.png");
    private final Pair<ResourceLocation, List<ResourceLocation>> modelInfo;
    private final int color;
    private final ModelSelectionTarget target;
    /** CU-16: 缩放因子在构造时缓存（按钮每次 initGui 重建），避免每帧重建 ScaledResolution。FBO 的物理像素
     *  尺寸也用它(width × scaleFactor),替换掉原来给 scissor 用的显示高度(离屏路径不需要 scissor)。 */
    private final int guiScale;

    // ── 行 43 GUI_MODEL_PREVIEW_REFRESH 的落点:这个 tile 自己的离屏 FBO 缓存 ──────────
    // 参考分支 client/gui/button/ModelButton.java:49-60(字段)、:263-366(烘焙块)、:154-173(dispose)。
    // 本仓没有参考的 GUI 前景/背景 DynamicTexture(它的 ClientModelManager.GUI_FOREGROUND_IMAGE 等在本仓
    // 不存在),所以这里只有 FBO 与刷新状态这几项。
    /** Off-screen framebuffer cache for this preview tile — bakes at the policy's rate, blits in between. */
    private final FboCache fboCache = new FboCache();
    /** 需要一次重烘焙(构造后第一次、FBO 被作废、模型重新发布后)。 */
    private boolean modelCacheDirty = true;
    /** 非自动模式的帧计数(1-4 = 每 N 帧);自动模式不用它。 */
    private int modelCacheFramesUntilRefresh = 0;
    /** 上一次生效的刷新模式,用来在配置页改了值之后立刻重新同步计数与错峰。 */
    private int modelCacheLastRefreshInterval = -1;
    /** 上一次烘焙时的悬停状态;悬停变化必须立刻重烘焙(否则 hover 动画要等下一次周期)。 */
    private boolean modelCacheWasHovered;
    /** 上一次烘焙时的选中状态:选中变化会切换 focus 通道,同样是"动画名变了"(参考 :273-274 的 animChanged)。 */
    private boolean modelCacheWasFocused;
    /** 自动模式的墙钟累加器;只在 {@code Config.GUI_MODEL_PREVIEW_REFRESH < 0} 时使用。 */
    private final PreviewRefreshPolicy.Tracker previewRefresh = new PreviewRefreshPolicy.Tracker();

    public ModelButton(int id, int pX, int pY, Pair<ResourceLocation, List<ResourceLocation>> modelInfo,
                       ModelSelectionTarget target) {
        super(id, pX, pY, 52, 90, "");
        this.modelInfo = modelInfo;
        this.color = 0xFF_434242;
        this.target = target;
        this.displayString = ModelIdUtil.getModelFileName(modelInfo.getLeft());

        Minecraft mc = Minecraft.getMinecraft();
        this.guiScale = mc == null ? 1 : new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    /**
     * CU-15 / CU-08（展示侧兜底）: 返回该模型应当应用的默认贴图。
     * 贴图列表为空时返回 {@code null}（调用方跳过渲染与点击），避免 {@code get(0)} 抛
     * {@code IndexOutOfBoundsException}（渲染期抛出会崩客户端）；非空时优先使用
     * {@code Config.DEFAULT_MODEL_TEXTURE}，因为列表第 0 项的顺序会被 PlayerTextureScreen 的历史排序影响。
     */
    private ResourceLocation defaultTexture() {
        List<ResourceLocation> textures = this.modelInfo.getRight();
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        ResourceLocation preferred = ModelIdUtil.getSubModelId(this.modelInfo.getLeft(), Config.DEFAULT_MODEL_TEXTURE);
        if (textures.contains(preferred)) {
            return preferred;
        }
        return textures.get(0);
    }

    /**
     * The hover tooltip for this tile.
     * <p>
     * Resolved on demand rather than captured when the button is constructed, because the extra info it shows comes
     * from the model's geometry and geometry is parsed on demand: when this screen builds its buttons nothing has
     * been drawn yet, so the value would otherwise be {@code null} for the whole life of the screen. Null is a
     * normal answer and means "this model has no extra info".
     */
    @Nullable
    public List<IChatComponent> tooltips() {
        return ClientModelManager.EXTRA_INFO.get(ModelIdUtil.getMainId(this.modelInfo.getLeft()));
    }

    public void doPress() {
        ResourceLocation texture = this.defaultTexture();
        if (texture == null) {
            // CU-08: 没有任何贴图的模型无法应用，直接忽略点击（不再越界崩溃）。
            return;
        }
        target.apply(modelInfo.getLeft(), texture);
    }

    /**
     * Releases this tile's off-screen FBO. Must be called when the button is removed from the screen
     * (page flip / screen close), because {@link FboCache} holds a GL texture that nothing else frees -
     * the reference does the same in {@code ModelButton#dispose} (reference {@code :154-173}).
     * <p>
     * The reference's dispose also frees the two GUI foreground/background DynamicTextures it uploads; this port
     * has no such images (see the GUI_ENHANCEMENTS finding), so only the FBO and the stagger state are reset.
     */
    public void dispose() {
        this.fboCache.delete();
        this.previewRefresh.reset();
        this.modelCacheDirty = true;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        // Hover状态
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        // 绘制背景（原graphics.fillGradient）
        this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, this.color, this.color);
        ResourceLocation texture = this.defaultTexture();
        // CU-08: 没有贴图时跳过实体预览（原来会在 get(0) 处越界崩溃）。
        // The tile is drawn only once this model has been published on this connection. Asking is separate from
        // drawing on purpose: ensureGeometry queues the build, while isModelPublished answers whether the geometry
        // and textures are really there - and for a model whose build failed, or whose geometry is left over from a
        // previous connection, those two differ. Binding this model's texture before it is uploaded would make
        // TextureManager fall back to a lookup that cannot succeed; see CustomPlayerModel#getTextureLocation.
        boolean published = ClientModelManager.isModelPublished(this.modelInfo.getLeft());
        ClientModelManager.ensureGeometry(this.modelInfo.getLeft());
        if (texture != null && published) {
            // 行 43: 预览的实体渲染改成"按策略烘焙到离屏 FBO,其余帧只 blit"(参考 :263-366)。
            this.bakePreviewIfDue(mc, texture);
        } else {
            // 几何不在(还没构建完 / 被重新同步搬走)时缓存里的画面不再可信:标脏而不是继续拿它当初次烘焙的
            // 结果 —— 本仓的 isModelPublished 是渲染前的就绪查询(参考分支没有这个查询,它用 SYNC_IN_PROGRESS
            // 加 GeckoLibCache 直查),所以这里得自己记住"这次发布可能换了画面"。没有新增状态:复用的就是
            // modelCacheDirty,它本来就表示"下一次能看到模型时必须重画"。
            this.modelCacheDirty = true;
            // 但缓存**里已有的**那张画面继续 blit:重建期间显示上一次烘焙的结果,而不是突然空一块(参考的 blit
            // 就在"要不要重烘焙"的判断之外)。没有 FBO 时 draw() 自己 no-op。
            this.fboCache.draw(this.xPosition, this.yPosition, this.width, this.height - 20);
        }
        // 渲染文本
        // font.split->listFormattedStringToWidth
        List<String> split = font.listFormattedStringToWidth(this.displayString, 45);
        if (split.size() > 1) {
            this.drawCenteredString(font, split.get(0), this.xPosition + this.width / 2, this.yPosition + this.height - 19, 0xF3EFE0);
            // CU-17: 模型名超过两行时在第二行末尾补省略号，提示被截断（按 45px 宽裁剪后再追加）。
            String secondLine = split.get(1);
            if (split.size() > 2) {
                secondLine = font.trimStringToWidth(secondLine, 45 - font.getStringWidth("...")) + "...";
            }
            this.drawCenteredString(font, secondLine, this.xPosition + this.width / 2, this.yPosition + this.height - 10, 0xF3EFE0);
        } else {
            this.drawCenteredString(font, this.displayString, this.xPosition + this.width / 2, this.yPosition + this.height - 15, 0xF3EFE0);
        }
        // 悬停时的高亮边框
        if (this.field_146123_n) {
            this.drawGradientRect(this.xPosition, this.yPosition + 1, this.xPosition + 1, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1, this.xPosition + this.width, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1, this.xPosition + this.width, this.yPosition + this.height, 0xff_F3EFE0, 0xff_F3EFE0);
        }
        // 收藏图标
        if (target.isStarred(modelInfo.getLeft())) {
            // graphics.blit
            mc.getTextureManager().bindTexture(ICON);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            this.drawTexturedModalRect(this.xPosition + this.width - 14, this.yPosition, 16, 0, 16, 16);
        }
    }

    /**
     * 行 43: 到点就把这个 tile 重画进它的离屏 FBO,然后把缓存贴图 blit 到按钮的模型区域。
     *
     * <p>逐处对照参考 {@code client/gui/button/ModelButton.java:263-366},四处按本仓结构改写:</p>
     * <ul>
     *   <li>参考在烘焙前按自己的 {@code modelCacheFbW/FbH} 字段判断"FBO 尺寸变了"并调
     *       {@code RenderUtil.invalidatePreviewPose(mainModelId)},再读
     *       {@code RenderUtil.renderEntityInInventory(...)} 的布尔返回值(姿态没变时跳过几何提交)。
     *       本仓没有那两个字段,用的是 {@link FboCache#checkAndResize} 的返回值 —— 它恰好就是
     *       "FBO 被(重)建、画面已经没了"(interval 传 0,所以周期刷新不会让它返回 true),
     *       语义与参考的尺寸比较相同且更少状态。姿态跳过链已在 {@code util/RenderUtil.java} 接上。</li>
     *   <li>参考用 {@code ClientModelManager.SYNC_IN_PROGRESS} 在同步期间暂停烘焙;本仓的对应门是
     *       {@code isModelPublished}(调用方已经问过),所以这里不再重复判断。</li>
     *   <li>参考的悬停/焦点动画由它自己算动画名并写进实体;本仓把 {@code hovered}/{@code focused} 交给
     *       {@code RenderUtil.renderEntityInInventory} 的 8 参数重载,由 {@code ModelPreviewAnimationState}
     *       决定通道 —— 所以"动画名变了"这个重烘焙触发条件在这里就是悬停/选中状态的变化。</li>
     *   <li>FBO 的清除从"烘焙前直接清"改成回调:姿态命中时不会走到清除,否则先清后跳过就是一张空白缩略图
     *       (参考 {@code :313-314})。</li>
     * </ul>
     * <p>投影只换 x/y:z 沿用屏幕 GUI 的那一段(1000..3000)。1.7.10 的 GUI 投影是
     * {@code glOrtho(0,w,h,0,1000,3000)} 加 {@code modelview translate(0,0,-2000)}
     * ({@code build/rfg/minecraft-src/java/net/minecraft/client/renderer/EntityRenderer.java:1745-1748}),
     * 而 {@code RenderUtil.renderModel} 把模型放在 z=+100,所以这里**不能**把 modelview 归零 ——
     * 眼睛坐标 z=-1900 落在 [−3000,−1000] 内,和没开缓存时那次直接绘制落在同一个深度段里(参考同形)。</p>
     */
    private void bakePreviewIfDue(Minecraft mc, ResourceLocation texture) {
        boolean hovered = this.func_146115_a();
        boolean focused = this.modelInfo.getLeft().equals(this.target.getModelId());
        // 刷新策略:-1 = 自动(整页共享预算,见 PreviewRefreshPolicy);0 = 静态(仅交互时重烘焙);
        // 1-4 = 每 N 帧。GUI_ENHANCEMENTS 关闭时退化成静态(参考 ModelButton.java:264-266)。
        int refreshMode = Config.GUI_ENHANCEMENTS ? Config.GUI_MODEL_PREVIEW_REFRESH : 0;
        // Re-sync counter when the user changes the config value (config screen is a different screen; coming back
        // rebuilds the buttons, but the same value can also change under a live screen).
        if (refreshMode != this.modelCacheLastRefreshInterval) {
            this.modelCacheFramesUntilRefresh = 0;
            this.modelCacheLastRefreshInterval = refreshMode;
            this.previewRefresh.reset();
        }
        boolean hoverChanged = hovered != this.modelCacheWasHovered;
        boolean focusChanged = focused != this.modelCacheWasFocused;
        boolean periodicDue;
        if (refreshMode < 0) {
            PreviewRefreshPolicy.noteVisible();
            periodicDue = this.previewRefresh.due(this.id, hovered || focused);
        } else {
            periodicDue = refreshMode > 0 && --this.modelCacheFramesUntilRefresh <= 0;
        }
        if (!hoverChanged && !focusChanged && !this.modelCacheDirty && !periodicDue) {
            // 缓存命中:只 blit,不碰几何。
            this.fboCache.draw(this.xPosition, this.yPosition, this.width, this.height - 20);
            return;
        }
        this.modelCacheWasHovered = hovered;
        this.modelCacheWasFocused = focused;
        boolean wasDirty = this.modelCacheDirty;
        this.modelCacheDirty = false;
        this.modelCacheFramesUntilRefresh = refreshMode;

        // CU-16 的缓存缩放因子:物理像素 = GUI 单位 × scaleFactor(与参考 :291-293 同式)。
        int fbW = this.width * this.guiScale;
        int fbH = (this.height - 20) * this.guiScale;
        // 尺寸变了由 checkAndResize 建/重建 FBO(interval 传 0:周期刷新由上面的策略驱动,见 FboCache 的类注释)。
        // 返回值就是"FBO 被(重)建、里面的画面已经没了":首次烘焙、窗口/GUI scale 变化、dispose() 删掉之后。
        boolean fboRebuilt = this.fboCache.checkAndResize(fbW, fbH, 0);
        // 姿态签名作废(见 RenderUtil.invalidatePreviewPose):下面每一种情况都会让 FBO 里的画面失效,而模型
        // 可能静止不动 —— 姿态签名不变,不在这里作废就会跳过几何提交,留下**空白或过期**的缩略图:
        //   1. fboRebuilt:FBO 被重建,画面物理上已经没了(唯一"内容消失但姿态不变"的情况);
        //   2. 悬停/选中变化:切的是预览动画通道,骨骼姿态可能这一帧还没变;
        //   3. modelCacheDirty:构造后的第一次、dispose() 之后、模型重新发布(画面可能整块换了)。
        if (fboRebuilt || hoverChanged || focusChanged || wasDirty) {
            RenderUtil.invalidatePreviewPose(ModelIdUtil.getMainId(this.modelInfo.getLeft()));
        }
        this.fboCache.bind();
        // 1.7.10 的 Framebuffer#bindFramebuffer(false) 不设 viewport(Framebuffer.java:197-208,只有 true 分支设),
        // 所以调用方负责(同 HudPreviewCache:437-440)。
        GL11.glViewport(0, 0, fbW, fbH);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(this.xPosition, this.xPosition + this.width,
            this.yPosition + this.height - 20, this.yPosition,
            1000.0, 3000.0);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        try {
            // 悬停/选中照旧交给 RenderUtil 驱动该 tile 的预览动画通道(与上游 catalog 卡片一致)。
            // GUI_ENHANCEMENTS 关闭时两个通道都不给:本仓没有参考的 "guiAnimationsEnabled=false"
            // (那个开关在 CustomPlayerEntity 上,不在本任务写权限内),而按钮能控制的通道就是这两个。
            boolean animations = Config.GUI_ENHANCEMENTS;
            long bakeStart = System.nanoTime();
            boolean poseRendered = RenderUtil.renderEntityInInventory(
                this.xPosition + this.width / 2,
                this.yPosition + this.height / 2 + 20,
                30,
                mc.thePlayer,
                this.modelInfo.getLeft(),
                texture,
                hovered && animations,
                focused && animations,
                // 注意:清除动作作为回调传进去,只有真的决定要画时才执行 —— 姿态没变会被跳过,
                // 先清就会留下空白缩略图(参考 ModelButton.java:313-314)。
                () -> {
                    GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                },
                true);
            // 姿态没变时跳过了几何提交(只剩动画 tick),**不能**把那点耗时喂给预算:否则估计成本会塌到零、
            // 频率被推到上限,而每次 tick 的开销反而成倍增加(参考 :351-356)。
            if (poseRendered) {
                PreviewRefreshPolicy.noteBake((System.nanoTime() - bakeStart) / 1.0e6F);
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            // unbind 同时把 viewport 还给屏幕帧缓冲(bindFramebuffer(true) 的 true 分支),所以异常路径也不会
            // 把整个界面画进 FBO。
            this.fboCache.unbind(mc);
        }
        // Draw the cached FBO texture stretched to the model area of the button.
        this.fboCache.draw(this.xPosition, this.yPosition, this.width, this.height - 20);
    }
}
