package com.fox.ysmu.client.gui.button;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.ysmu;
import com.fox.ysmu.client.gui.ModelSelectionTarget;
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
    /** CU-16: 缩放因子与显示高度在构造时缓存（按钮每次 initGui 重建），避免每帧重建 ScaledResolution。 */
    private final int guiScale;
    private final int guiDisplayHeight;

    public ModelButton(int id, int pX, int pY, Pair<ResourceLocation, List<ResourceLocation>> modelInfo,
                       ModelSelectionTarget target) {
        super(id, pX, pY, 52, 90, "");
        this.modelInfo = modelInfo;
        this.color = 0xFF_434242;
        this.target = target;
        this.displayString = ModelIdUtil.getModelFileName(modelInfo.getLeft());

        Minecraft mc = Minecraft.getMinecraft();
        this.guiScale = mc == null ? 1 : new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        this.guiDisplayHeight = mc == null ? 1 : mc.displayHeight;
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
        // 剪裁测试（缩放）：CU-16 用构造时缓存的缩放因子
        int scale = this.guiScale;
        int scissorX = this.xPosition * scale;
        // 在GL11中，Y轴的原点在左下角，所以需要从屏幕总高度中减去
        int scissorY = this.guiDisplayHeight - ((this.yPosition + this.height - 20) * scale);
        int scissorW = this.width * scale;
        int scissorH = (this.height - 20) * scale;
        ResourceLocation texture = this.defaultTexture();
        // CU-13: scissor 必须在 finally 里恢复，渲染异常时不能让整个界面被裁剪。
        // CU-08: 没有贴图时跳过实体预览（原来会在 get(0) 处越界崩溃）。
        // The tile is drawn only once this model has been published on this connection. Asking is separate from
        // drawing on purpose: ensureGeometry queues the build, while isModelPublished answers whether the geometry
        // and textures are really there - and for a model whose build failed, or whose geometry is left over from a
        // previous connection, those two differ. Binding this model's texture before it is uploaded would make
        // TextureManager fall back to a lookup that cannot succeed; see CustomPlayerModel#getTextureLocation.
        boolean published = ClientModelManager.isModelPublished(this.modelInfo.getLeft());
        ClientModelManager.ensureGeometry(this.modelInfo.getLeft());
        if (texture != null && published) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            try {
                GL11.glScissor(scissorX, scissorY, scissorW, scissorH);
                // Render实体,同时驱动该 tile 的预览动画通道(悬停/选中),与上游 catalog 卡片一致
                RenderUtil.renderEntityInInventory(this.xPosition + this.width / 2, this.yPosition + this.height / 2 + 20, 30,
                    mc.thePlayer, modelInfo.getLeft(), texture, this.func_146115_a(),
                    modelInfo.getLeft().equals(this.target.getModelId()));
            } finally {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
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
}
