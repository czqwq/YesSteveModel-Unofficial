package com.fox.ysmu.client.gui.button;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.gui.ModelSelectionTarget;
import com.fox.ysmu.client.renderer.PreviewRefreshPolicy;
import com.fox.ysmu.util.FboCache;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class TextureButton extends GuiButton {
    private final ResourceLocation modelId;
    private final ResourceLocation textureId;
    private final String name;
    private final ModelSelectionTarget target;
    /** CU-16: 缩放因子在构造时缓存（每次 initGui 重建按钮），避免每帧重建 ScaledResolution。FBO 的物理像素
     *  尺寸也用它(width × scaleFactor),替换掉原来给 scissor 用的显示高度(离屏路径不需要 scissor)。 */
    private final int guiScale;

    // ── 行 43 GUI_MODEL_PREVIEW_REFRESH 在贴图页的落点 ────────────────────────────────
    // 参考分支 client/gui/button/TextureButton.java:28-34(字段)、:73-128(烘焙块)、:56-62(dispose)。
    /** Off-screen framebuffer cache for this texture tile. */
    private final FboCache fboCache = new FboCache();
    private boolean modelCacheDirty = true;
    private int modelCacheFramesUntilRefresh = 0;
    private int modelCacheLastRefreshInterval = -1;
    /** 上一次烘焙时的悬停状态(立刻重烘焙,否则 hover 动画要等下一个周期)。 */
    private boolean modelCacheWasHovered;
    /** 上一次烘焙时的选中状态:本仓的贴图 tile 把 selected 当 focus 通道传给 RenderUtil,所以要跟踪它。 */
    private boolean modelCacheWasSelected;
    /** 自动模式的墙钟累加器;只在 {@code Config.GUI_MODEL_PREVIEW_REFRESH < 0} 时使用。 */
    private final PreviewRefreshPolicy.Tracker previewRefresh = new PreviewRefreshPolicy.Tracker();

    public TextureButton(int id, int pX, int pY, ResourceLocation modelId, ResourceLocation textureId, ModelSelectionTarget target) {
        super(id, pX, pY, 54, 102, "");
        this.modelId = modelId;
        this.textureId = textureId;
        this.name = ModelIdUtil.getSubNameFromId(textureId);
        this.target = target;

        Minecraft mc = Minecraft.getMinecraft();
        this.guiScale = mc == null ? 1 : new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    public void doPress() {
        target.apply(modelId, textureId);
    }

    /** Releases this tile's off-screen FBO; call when the button leaves the screen (page flip / close).
     *  Reference {@code TextureButton#dispose} (its {@code :56-62}); this port has no GUI images to free. */
    public void dispose() {
        this.fboCache.delete();
        this.previewRefresh.reset();
        this.modelCacheDirty = true;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        FontRenderer font = mc.fontRenderer;
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, 0xFF_434242, 0xFF_434242);
        boolean selected = textureId.equals(target.getTextureId());
        // Draw only once the model behind this texture has been built, for the same reason as ModelButton: until
        // then its geometry is not in the engine's cache and its textures have not been uploaded, so rendering would
        // either bind an id that does not exist yet or show the built-in default in every tile. The null test comes
        // first because the id has to be derived before it can be looked up, and deriving it from null throws.
        boolean drawable = modelId != null && ClientModelManager.isModelPublished(ModelIdUtil.getMainId(modelId));
        ClientModelManager.ensureGeometry(modelId);
        if (drawable) {
            // 行 43: 贴图预览也走"按策略烘焙到离屏 FBO,其余帧只 blit"(参考 TextureButton.java:73-128)。
            this.bakePreviewIfDue(mc, selected);
        } else {
            // 同 ModelButton:几何不在时缓存画面不可信,标脏等它回来;已有的缓存画面继续 blit。
            this.modelCacheDirty = true;
            this.fboCache.draw(this.xPosition, this.yPosition, this.width, this.height - 20);
        }

        List<String> split = font.listFormattedStringToWidth(name, 50);
        if (split.size() > 1) {
            this.drawCenteredString(font, split.get(0), this.xPosition + this.width / 2, this.yPosition + this.height - 19, 0xF3EFE0);
            this.drawCenteredString(font, split.get(1), this.xPosition + this.width / 2, this.yPosition + this.height - 10, 0xF3EFE0);
        } else {
            this.drawCenteredString(font, name, this.xPosition + this.width / 2, this.yPosition + this.height - 15, 0xF3EFE0);
        }
        if (selected || this.field_146123_n) {
            drawBorder(selected ? 0xff_82C56A : 0xff_F3EFE0);
        }
    }

    private void drawBorder(int color) {
        this.drawGradientRect(this.xPosition, this.yPosition + 1, this.xPosition + 1, this.yPosition + this.height - 1, color, color);
        this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + 1, color, color);
        this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1, this.xPosition + this.width, this.yPosition + this.height - 1, color, color);
        this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1, this.xPosition + this.width, this.yPosition + this.height, color, color);
    }

    /**
     * 行 43: 到点就把这张贴图预览重画进它的离屏 FBO,然后把缓存贴图 blit 到按钮的模型区域。
     *
     * <p>结构与 {@code ModelButton#bakePreviewIfDue} 相同;三处与参考不同,理由都在那里:</p>
     * <ul>
     *   <li>参考的 TextureButton <b>不</b>受 {@code GUI_ENHANCEMENTS} 影响(它的 {@code :75} 直接读
     *       {@code Config.GUI_MODEL_PREVIEW_REFRESH}),这里照做:该开关的说明是"模型选择 GUI 增强",
     *       贴图页不在它的名字里。</li>
     *   <li>参考的 TextureButton 用 6 参数重载,根本不驱动预览动画通道,所以它没有"悬停/选中变化要重烘焙"
     *       这件事;本仓的贴图 tile 把 {@code hovered}/{@code selected} 当 hover/focus 通道传下去(上游纹理选择
     *       界面的行为),所以这里额外跟踪这两个状态 —— 否则静态模式下点选一张贴图不会切换 focus 动画。</li>
     *   <li><b>不接姿态签名跳过</b>({@code RenderUtil.renderEntityInInventory} 的
     *       {@code allowPoseSkip=false}):同一个主模型在这一页有 N 张贴图,它们共用同一个签名 key,
     *       开了只会互相顶掉、还额外要求每张贴图都正确失效(参考 {@code util/RenderUtil.java:532-533}
     *       明确说这一页不要开)。所以这里也不把 FBO 清除改成回调 —— 不跳过时"先清后画"是正确的。</li>
     * </ul>
     */
    private void bakePreviewIfDue(Minecraft mc, boolean selected) {
        boolean hovered = this.func_146115_a();
        int refreshMode = Config.GUI_MODEL_PREVIEW_REFRESH;
        if (refreshMode != this.modelCacheLastRefreshInterval) {
            this.modelCacheFramesUntilRefresh = 0;
            this.modelCacheLastRefreshInterval = refreshMode;
            this.previewRefresh.reset();
        }
        boolean hoverChanged = hovered != this.modelCacheWasHovered;
        boolean selectedChanged = selected != this.modelCacheWasSelected;
        boolean periodicDue;
        if (refreshMode < 0) {
            PreviewRefreshPolicy.noteVisible();
            periodicDue = this.previewRefresh.due(this.id, hovered || selected);
        } else {
            periodicDue = refreshMode > 0 && --this.modelCacheFramesUntilRefresh <= 0;
        }
        if (hoverChanged || selectedChanged || this.modelCacheDirty || periodicDue) {
            this.modelCacheWasHovered = hovered;
            this.modelCacheWasSelected = selected;
            this.modelCacheDirty = false;
            this.modelCacheFramesUntilRefresh = refreshMode;

            int fbW = this.width * this.guiScale;
            int fbH = (this.height - 20) * this.guiScale;
            this.fboCache.checkAndResize(fbW, fbH, 0);
            this.fboCache.bind();
            // 见 ModelButton 的说明:1.7.10 的 bindFramebuffer(false) 不设 viewport(同 HudPreviewCache:437-440)。
            GL11.glViewport(0, 0, fbW, fbH);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // 投影只换 x/y,z 沿用屏幕 GUI 的那一段(1000..3000),modelview 不能动(见 ModelButton 的说明)。
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(this.xPosition, this.xPosition + this.width,
                this.yPosition + this.height - 20, this.yPosition,
                1000.0, 3000.0);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            try {
                long bakeStart = System.nanoTime();
                RenderUtil.renderEntityInInventory(this.xPosition + this.width / 2, this.yPosition + this.height / 2 + 24,
                    35, mc.thePlayer, modelId, textureId, hovered, selected);
                PreviewRefreshPolicy.noteBake((System.nanoTime() - bakeStart) / 1.0e6F);
            } finally {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                this.fboCache.unbind(mc);
            }
        }
        this.fboCache.draw(this.xPosition, this.yPosition, this.width, this.height - 20);
    }
}
