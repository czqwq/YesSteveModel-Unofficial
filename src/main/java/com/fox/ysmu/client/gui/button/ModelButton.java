package com.fox.ysmu.client.gui.button;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelAccess;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ClientModelMetadataRegistry;
import com.fox.ysmu.client.entity.CustomGuiPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;
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
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

import com.fox.ysmu.model.format.BuiltinModelIndex;

public class ModelButton extends GuiButton {
    private final static ResourceLocation ICON = new ResourceLocation(ysmu.MODID, "texture/icon.png");
    private final Pair<ResourceLocation, List<ResourceLocation>> modelInfo;
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
    /**
     * This tile's own preview animatable, created on the first bake and released by {@link #dispose()}.
     * <p>
     * Upstream gives every catalog card its own {@code CustomGuiPlayerEntity} - ten of them live in
     * {@code PlayerModelScreen.java:56} and {@code CatalogModelButton.java:41} keeps the one its card draws with - so
     * the card's identity is stable across frames and {@code CustomGuiPlayerEntity#determineImmutableContext}
     * ({@code client/gui/CustomGuiPlayerEntity.java:43-46}) applies to it. This port used to draw every tile through
     * one animatable cached per model id ({@code AnimatableCacheUtil.ANIMATABLE_CACHE}), i.e. a plain
     * {@code CustomPlayerEntity} - the class the world draws with - which declares nothing and therefore could never
     * present one stable pose per frame.
     * <p>
     * Created lazily rather than in the constructor because this screen is also reachable from the main menu, where
     * there is no player and no world: {@link CustomGuiPlayerEntity#prepare} has nothing to prepare there and the tile
     * is not drawn at all ({@code RenderUtil.renderEntityInInventory} returns early).
     */
    private CustomGuiPlayerEntity preview;

    public ModelButton(int id, int pX, int pY, Pair<ResourceLocation, List<ResourceLocation>> modelInfo,
                       ModelSelectionTarget target) {
        super(id, pX, pY, 52, 90, "");
        this.modelInfo = modelInfo;
        this.target = target;
        this.displayString = ModelIdUtil.getModelFileName(modelInfo.getLeft());

        Minecraft mc = Minecraft.getMinecraft();
        this.guiScale = mc == null ? 1 : new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    /**
     * Whether this tile is locked: the model is restricted on the server and this client holds no grant for it.
     * <p>
     * Upstream computes that fact when it builds the card
     * ({@code client/gui/PlayerModelScreen.java:255-257} -> {@code client/gui/button/CatalogModelButton.java:42,48-56}),
     * which uses it in four places: the fill colour ({@code CatalogModelButton.java:100}), refusing the press
     * ({@code :80-85}), refusing the hit test ({@code :141-144}) and, in {@code renderState}, dropping the hover frame
     * and adding the dark overlay ({@code :194-206}).
     * <p>
     * Upstream re-evaluates it while the screen is open: {@code tick()} notices that the session published a new
     * catalog ({@code PlayerModelScreen.java:377-390}) and calls {@code init()}, which rebuilds the cards from the live
     * {@code authoritativeGrants()} ({@code :235,280}). This port has no catalog snapshot to compare against and
     * rebuilds its tiles only when the model count or the pack manifests change
     * ({@code PlayerModelScreen#updateScreen}), so a value snapshotted at build time would keep a locked tile unlocked
     * after {@code /ysm auth <player> clear} until the user left the page. Asking the holder at the moment the fact is
     * used is that same refresh with nothing to compare and nothing to invalidate.
     * <p>
     * {@code needsAuth} is false while the client has received no authorization information at all, which is what
     * keeps a not-yet-synced or vanilla server from locking every tile.
     */
    private boolean needAuth() {
        return ClientModelAccess.needsAuth(this.modelInfo.getLeft());
    }

    /**
     * Upstream's CatalogModelCardState.java:222-224 uses the model's declared default for the tile and selection.
     * Reuse the renderer's model-local resolver rather than maintaining a second texture ladder. Empty texture
     * lists still return null here, because a catalog tile must skip rendering and selection of a textureless model.
     */
    private ResourceLocation defaultTexture() {
        return defaultTexture(this.modelInfo.getLeft(), this.modelInfo.getRight());
    }

    @Nullable
    static ResourceLocation defaultTexture(ResourceLocation modelId, @Nullable List<ResourceLocation> textures) {
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        return CustomPlayerModel.resolveTexture(
            modelId, textures, null, ClientModelManager.DECLARED_DEFAULT_TEXTURES.get(modelId));
    }

    /**
     * The hover tooltip for this tile: where the model comes from, then the lines its geometry declares.
     * <p>
     * Upstream renders a source row for every card
     * ({@code client/gui/button/CatalogModelTooltipFormatter.java:26-88}, whose source enum includes
     * {@code BUILTIN("builtin")}), so this list is never empty any more - only its source row is always present.
     * <p>
     * Resolved on demand rather than captured when the button is constructed, because the extra info it shows comes
     * from the model's geometry and geometry is parsed on demand: when this screen builds its buttons nothing has
     * been drawn yet, so the value would otherwise be {@code null} for the whole life of the screen. Null is a
     * normal answer and means "this model has no extra info".
     */
    public List<IChatComponent> tooltips() {
        List<IChatComponent> lines = new ArrayList<>();
        lines.add(new ChatComponentTranslation(sourceKeyFor(this.modelInfo.getLeft())));
        List<IChatComponent> declared = ClientModelManager.EXTRA_INFO
            .get(ModelIdUtil.getMainId(this.modelInfo.getLeft()));
        if (declared != null) {
            lines.addAll(declared);
        }
        return lines;
    }

    /**
     * Which translation key names where this model comes from.
     * <p>
     * A model the server published as restricted answers the auth row first: upstream's tooltip has one source row per
     * entry and an entry whose origin is {@code AUTH} maps to {@code Source.AUTH}
     * ({@code client/gui/button/CatalogModelTooltipFormatter.java:173-180}), whose row is
     * {@code gui.yes_steve_model.catalog.tooltip.source.auth} ("Authorized") and whose key here is
     * {@code gui.yes_steve_model.model.source.auth}. {@code requiresAuth} is false when no authorization information
     * has arrived, so a vanilla server keeps answering built-in/server exactly as before.
     * <p>
     * A model the shipped contract declares is built-in ({@code BuiltinModelIndex.declaredInternalIds()}, the same
     * identity the server uses for its source priority). Every other model on a client arrived from the server: this
     * port keeps no per-model local/server registry, and the only thing the client ever reads off its own disk is the
     * built-in tree, which is exactly the built-in case. There is deliberately no third value.
     */
    static String sourceKeyFor(@Nullable ResourceLocation modelId) {
        if (ClientModelAccess.requiresAuth(modelId)) {
            return "gui.yes_steve_model.model.source.auth";
        }
        boolean builtin = modelId != null && BuiltinModelIndex.declaredInternalIds()
            .contains(modelId.getResourcePath());
        return builtin ? "gui.yes_steve_model.model.source.builtin" : "gui.yes_steve_model.model.source.server";
    }

    /**
     * The name this tile draws: the pack's own {@code metadata.name} for the running locale, else the name the
     * geometry declares, else the leaf name - the same fallback chain as upstream
     * {@code CatalogModelTooltipFormatter.java:61-70} with the pack's language table consulted first
     * ({@code ModelInfoView.translateOr:91-93}).
     */
    private String declaredName() {
        return ClientModelMetadataRegistry.displayName(
            this.modelInfo.getLeft(),
            ClientModelMetadataRegistry.currentLocale(),
            this.displayString);
    }

    public void doPress() {
        // Upstream CatalogModelButton.onPress (:80-85) ignores the press outright while the tile is locked.
        if (needAuth()) {
            return;
        }
        ResourceLocation texture = this.defaultTexture();
        if (texture == null) {
            // CU-08: 没有任何贴图的模型无法应用，直接忽略点击（不再越界崩溃）。
            return;
        }
        target.apply(modelInfo.getLeft(), texture);
    }

    /**
     * Upstream refuses a locked selection twice, and both halves are shaped after it: the hit test
     * ({@code CatalogModelButton.java:141-144}, {@code clicked} returns {@code !needAuth && super.clicked(...)}) and
     * the press handler ({@code :80-85}, {@link #doPress()}). 1.7.10's {@code GuiScreen.mouseClicked} only calls
     * {@code actionPerformed} for a button whose {@code mousePressed} returned true
     * ({@code build/rfg/minecraft-src/java/net/minecraft/client/gui/GuiScreen.java:245-249}), so the first refusal is
     * this override.
     */
    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        return !needAuth() && super.mousePressed(mc, mouseX, mouseY);
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
        // The tile's own animatable goes with it: an AnimationFactory that outlives its button keeps an FML ticker
        // registered for a screen that no longer exists (CustomGuiPlayerEntity#close).
        if (this.preview != null) {
            this.preview.close();
            this.preview = null;
        }
    }

    /** This tile's preview animatable; see the field. Never {@code null} after the first call. */
    private CustomGuiPlayerEntity previewEntity() {
        if (this.preview == null) {
            this.preview = new CustomGuiPlayerEntity();
        }
        return this.preview;
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
        // Upstream CatalogModelButton.java:100: a locked card fills with translucent black instead of the flat opaque
        // grey, so the preview behind it reads as darkened rather than replaced.
        int backgroundColor = needAuth() ? 0x7F000000 : 0xFF434242;
        this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, backgroundColor, backgroundColor);
        ResourceLocation texture = this.defaultTexture();
        // CU-08: 没有贴图时跳过实体预览（原来会在 get(0) 处越界崩溃）。
        // The tile is drawn only once this model has been published on this connection. Asking is separate from
        // drawing on purpose: ensureGeometry queues the build, while isModelPublished answers whether the geometry
        // and textures are really there - and for a model whose build failed, or whose geometry is left over from a
        // previous connection, those two differ. Binding this model's texture before it is uploaded would make
        // TextureManager fall back to a lookup that cannot succeed; see CustomPlayerModel#getTextureLocation.
        boolean published = ClientModelManager.isModelPublished(this.modelInfo.getLeft());
        ClientModelManager.ensureGeometry(this.modelInfo.getLeft());
        // The pack's own GUI background goes between that solid fill and the model preview, and its foreground over the
        // preview - upstream's order in CatalogModelButton.renderWidget (:101-106): fillGradient -> background ->
        // entity -> foreground, all three inside its "the render target is there" branch (:102-106). Both images cover
        // the whole tile, as upstream's renderImage blits them (:164-172). Gating on `published` is the port's form of
        // that branch: this tile has no loading placeholder, so an unpublished model keeps the bare fill it had before.
        // Config.GUI_ENHANCEMENTS gates both as well, which is the reference's own choice: upstream has no switch for
        // these two images (its hover/focus animation is a separate channel, driven at CatalogModelButton:99), and the
        // port keeps the gate because the switch's own description is "foreground/background textures and GUI
        // animations".
        if (Config.GUI_ENHANCEMENTS && published) {
            this.drawGuiImage(mc, this.modelInfo.getLeft(), ClientModelManager.GUI_IMAGE_BACKGROUND);
        }
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
        // The pack's GUI foreground goes over the model preview and under the tile's own text, border and star - the
        // same slot upstream gives it (CatalogModelButton:106 draws it before renderName/renderState).
        if (Config.GUI_ENHANCEMENTS && published) {
            this.drawGuiImage(mc, this.modelInfo.getLeft(), ClientModelManager.GUI_IMAGE_FOREGROUND);
        }
        // 渲染文本
        // font.split->listFormattedStringToWidth
        // Upstream CatalogModelButton.java:75-78: the card title follows the Show Model ID First switch, so the
        // declared name is read here instead of being snapshotted in the constructor (it can arrive later, the same
        // reason tooltips() queries on demand).
        String title = Config.SHOW_MODEL_ID_FIRST ? this.displayString : declaredName();
        List<String> split = font.listFormattedStringToWidth(title, 45);
        if (split.size() > 1) {
            this.drawCenteredString(font, split.get(0), this.xPosition + this.width / 2, this.yPosition + this.height - 19, 0xF3EFE0);
            // CU-17: 模型名超过两行时在第二行末尾补省略号，提示被截断（按 45px 宽裁剪后再追加）。
            String secondLine = split.get(1);
            if (split.size() > 2) {
                secondLine = font.trimStringToWidth(secondLine, 45 - font.getStringWidth("...")) + "...";
            }
            this.drawCenteredString(font, secondLine, this.xPosition + this.width / 2, this.yPosition + this.height - 10, 0xF3EFE0);
        } else {
            this.drawCenteredString(font, title, this.xPosition + this.width / 2, this.yPosition + this.height - 15, 0xF3EFE0);
        }
        // 悬停时的高亮边框
        // Upstream CatalogModelButton.java:194 draws the frame only on a card that is not locked
        // (`!needAuth && isHoveredOrFocused()`), while its tooltip still shows (:119-121), which is how the player
        // reads that the model is "Authorized".
        if (!needAuth() && this.field_146123_n) {
            this.drawGradientRect(this.xPosition, this.yPosition + 1, this.xPosition + 1, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1, this.xPosition + this.width, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1, this.xPosition + this.width, this.yPosition + this.height, 0xff_F3EFE0, 0xff_F3EFE0);
        }
        // Upstream CatalogModelButton.renderState (:203-206) then darkens a locked card over everything drawn so far -
        // its title included - and before the star badge. The 1.7.10 equivalent layers by call order (drawGradientRect
        // has no z argument; Gui.java:87-114), so this goes after the text and the border, before the star below.
        if (needAuth()) {
            this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, 0x9F222222, 0x9F222222);
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
     * Draws one of the model's own GUI presentation images stretched over the whole tile.
     * <p>
     * Upstream's card blits the same image the same way - over the full card with UV 0..1
     * ({@code CatalogModelButton#renderImage}: {@code graphics.blit(id, getX(), getY(), 0, 0, width, height, width,
     * height)}) - and the reference's tile did too (its {@code drawGuiTexture}). Nothing is drawn when the model
     * declares no such image, which is the common case: the call is then one map lookup and a null return.
     * <p>
     * The GL state is set explicitly instead of inherited, because the FBO blit that runs between the two images
     * leaves depth test, lighting and colour material disabled ({@link FboCache#draw}), and the texture matrix is reset
     * because the model pass may leave a transform on it (the same reason the reference reset it).
     */
    private void drawGuiImage(Minecraft mc, ResourceLocation modelId, String imageId) {
        ResourceLocation imageTexture = ClientModelManager.guiImageTextureFor(modelId, imageId);
        if (imageTexture == null) {
            return;
        }
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        mc.getTextureManager().bindTexture(imageTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(this.xPosition, this.yPosition + this.height, 0.0, 0.0, 1.0);
        tessellator.addVertexWithUV(this.xPosition + this.width, this.yPosition + this.height, 0.0, 1.0, 1.0);
        tessellator.addVertexWithUV(this.xPosition + this.width, this.yPosition, 0.0, 1.0, 0.0);
        tessellator.addVertexWithUV(this.xPosition, this.yPosition, 0.0, 0.0, 0.0);
        tessellator.draw();
        GL11.glDisable(GL11.GL_BLEND);
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
     *       {@code RenderUtil.renderEntityInInventory} 的 {@code hovered}/{@code focused}/{@code preview}
     *       重载,由 {@code ModelPreviewAnimationState} 决定通道 —— 所以"动画名变了"这个重烘焙触发条件在这里就是
     *       悬停/选中状态的变化。</li>
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
                this.previewEntity(),
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
