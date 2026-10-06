package com.fox.ysmu.client.gui;

import java.util.List;
import java.util.Map;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.input.ExtraAnimationKey;
import com.fox.ysmu.Config;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetPlayAnimation;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.*;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public class AnimationRouletteScreen extends GuiScreen {

    /** 轮盘扇区数，必须与 ExtraAnimationKey.EXTRA_ANIMATION_KEYS 的长度一致。 */
    private static final int KEY_COUNT = 8;

    private int x;
    private int y;
    private int selectId = -1;
    /** The model whose extra-animation labels this wheel shows; see {@link #resolveNames}. */
    private ResourceLocation modelId;
    private String[] names;
    /** CU-16: 按键提示文本只在 initGui 时构造一次，避免每帧重复建 ChatComponent 并格式化。 */
    private String[] keyTexts = new String[KEY_COUNT];

    @Override
    public void initGui() {
        this.x = width / 2;
        this.y = height / 2 - 8;
        this.buildKeyTexts();

        if (mc != null && mc.thePlayer != null) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
            if (eep != null) {
                ResourceLocation modelId = eep.getModelId();
                // Ask for the model's geometry. This only *queues* the build - the table the labels come from is
                // filled when the build is published on a later tick - which is why the labels are re-resolved while
                // the screen is open rather than captured once here; see resolveNames.
                ClientModelManager.ensureGeometry(modelId);
                this.modelId = modelId;
                resolveNames();
                if (Config.DEBUG_WHEEL) {
                    dumpWheelData(modelId);
                }
            }
        }
    }

    /**
     * Re-reads the model's extra-animation labels, which arrive asynchronously.
     * <p>
     * The table is written when the model is published, and this screen can be opened before that has happened - the
     * first open after a join is exactly that case. Upstream has no such gap: the labels are part of the model's
     * manifest, which its catalog holds before the model is ever drawn. The port keeps them in the geometry payload
     * and parses that payload on demand, so the equivalent is to ask again once the payload has landed, which is what
     * the tooltip and the information screen already do by re-reading per frame.
     */
    private void resolveNames() {
        if (this.modelId == null) {
            return;
        }
        String[] resolved = ClientModelManager.EXTRA_ANIMATION_NAME.get(ModelIdUtil.getMainId(this.modelId));
        if (resolved != null) {
            this.names = resolved;
        }
    }

    /**
     * 行 47 DEBUG_WHEEL：转储本仓轮盘真正持有的数据（参考分支 client/gui/AnimationRouletteScreen.java:75-110）。
     * <p>
     * 参考分支转储的是它的 {@code ExtraWheelData}（entries / classifies / configButtons 三张表）；本仓轮盘没有
     * 这张表，扇区标签就是 {@link ClientModelManager#EXTRA_ANIMATION_NAME} 的扁平数组（本类 {@link #names}，
     * 按 {@link ModelIdUtil#getMainId} 取，见 {@link #resolveNames}），动画分类与设置按钮由
     * {@link ModelConfigRegistry} 保存、由设置界面消费（按模型 id 取，与 {@code PlayerModelScreen:174} 一致）。
     * 所以这里分三段转储本仓真实存在的三份数据，前缀与参考分支一致（{@code [YSMU-WHEEL]}），页号按参考分支的
     * {@code idx / ITEMS_PER_PAGE}（本类 {@link #KEY_COUNT}）计算。
     * <p>
     * 只在 {@link #initGui} 里调用一次：标签表是模型发布时才写入的（{@link #resolveNames} 的异步间隙），所以
     * 这次转储可能显示 0 个扇区而按钮/分类已在；参考分支没有这个间隙（标签随清单在模型绘制之前就存在）。
     */
    private void dumpWheelData(ResourceLocation modelId) {
        String[] labels = this.names;
        int entries = 0;
        if (labels != null) {
            for (String label : labels) {
                if (StringUtils.isNotBlank(label)) {
                    entries++;
                }
            }
        }
        ysmu.LOG.info("[YSMU-WHEEL] === Wheel Data for {} ===", modelId);
        ysmu.LOG.info("[YSMU-WHEEL] Total entries: {} ({} pages)", entries, (entries + KEY_COUNT - 1) / KEY_COUNT);
        if (labels != null) {
            for (int i = 0; i < labels.length; i++) {
                if (StringUtils.isNotBlank(labels[i])) {
                    ysmu.LOG
                        .info("[YSMU-WHEEL]   page={} [ANIM] key='extra{}' label='{}'", i / KEY_COUNT, i, labels[i]);
                }
            }
        }

        List<RawYsmModel.ExtraAnimationClassify> classifies = ModelConfigRegistry.classifiesFor(modelId);
        if (!classifies.isEmpty()) {
            ysmu.LOG.info("[YSMU-WHEEL] === Sub-pages ({} total) ===", classifies.size());
            for (RawYsmModel.ExtraAnimationClassify classify : classifies) {
                ysmu.LOG.info("[YSMU-WHEEL]   Classify '{}': {} entries", classify.id, classify.extras.size());
                for (Map.Entry<String, String> entry : classify.extras.entrySet()) {
                    ysmu.LOG.info("[YSMU-WHEEL]     key='{}' label='{}'", entry.getKey(), entry.getValue());
                }
            }
        }

        List<RawYsmModel.ExtraAnimationButton> buttons = ModelConfigRegistry.buttonsFor(modelId);
        if (!buttons.isEmpty()) {
            ysmu.LOG.info("[YSMU-WHEEL] === Config Buttons ({} total) ===", buttons.size());
            for (RawYsmModel.ExtraAnimationButton button : buttons) {
                ysmu.LOG.info("[YSMU-WHEEL]   btn id='{}' name='{}' desc='{}' forms={}", button.id, button.name,
                    button.description, button.forms.size());
                for (int i = 0; i < button.forms.size(); i++) {
                    RawYsmModel.ConfigForm form = button.forms.get(i);
                    ysmu.LOG.info(
                        "[YSMU-WHEEL]     form[{}] type='{}' title='{}' desc='{}' default='{}' min={} max={} step={} labels={}",
                        i, form.type, form.title, form.description, form.defaultValue, form.min, form.max, form.step,
                        form.labels);
                }
            }
        }
        ysmu.LOG.info("[YSMU-WHEEL] === End Wheel Dump ===");
    }

    private void buildKeyTexts() {
        for (int i = 0; i < KEY_COUNT; i++) {
            if (i >= ExtraAnimationKey.EXTRA_ANIMATION_KEYS.size()) {
                this.keyTexts[i] = "";
                continue;
            }
            ChatComponentText keyText = new ChatComponentText("[ ");
            keyText.getChatStyle()
                .setColor(EnumChatFormatting.YELLOW);
            KeyBinding keyMapping = ExtraAnimationKey.EXTRA_ANIMATION_KEYS.get(i);
            if (keyMapping.getKeyCode() == Keyboard.KEY_NONE) {
                keyText.appendSibling(new ChatComponentTranslation("key.yes_steve_model.extra_animation.none"));
            } else {
                String keyName = Keyboard.getKeyName(keyMapping.getKeyCode());
                keyText.appendSibling(new ChatComponentText(keyName));
            }
            keyText.appendSibling(new ChatComponentText(" ]"));
            this.keyTexts[i] = keyText.getFormattedText();
        }
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        // The labels arrive with the model's publish, which can happen while this screen is open; asking again here
        // costs one map lookup and means a wheel opened during the build fills in rather than staying bare.
        resolveNames();
        drawRoulette(pMouseX, pMouseY);
        drawRouletteText();
    }

    @Override
    protected void mouseClicked(int pMouseX, int pMouseY, int pButton) {
        if (-1 < selectId && selectId < 8 && mc != null) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            NetworkHandler.CHANNEL.sendToServer(new SetPlayAnimation(selectId));
            if (mc.thePlayer != null && Config.PRINT_ANIMATION_ROULETTE_MSG) {
                // CU-01: 1.7.10 的 ChatComponentTranslation 只支持 %s/%<n>$s，%d 会在聊天栏渲染时抛
                // ChatComponentTranslationFormatException（客户端崩溃），因此这里传字符串。
                mc.thePlayer.addChatMessage(new ChatComponentTranslation(
                    "message.yes_steve_model.model.animation_roulette.play",
                    "extra" + selectId));
            }
            mc.displayGuiScreen(null);
        }
        super.mouseClicked(pMouseX, pMouseY, pButton);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawRouletteText() {
        int count = KEY_COUNT;
        float startDeg = (float) (Math.PI / count);
        for (int i = 0; i < count; i++) {
            int r = 65;

            // CU-16: 按键文本在 initGui 已缓存，这里不再每帧构造 ChatComponent。
            String keyText = i < this.keyTexts.length && this.keyTexts[i] != null ? this.keyTexts[i] : "";
            int textX = (int) (x + r * MathHelper.cos(startDeg));
            int textY = (int) (y + r * MathHelper.sin(startDeg) - (float) this.fontRendererObj.FONT_HEIGHT / 2);
            if (this.names != null && this.names.length > i && StringUtils.isNoneBlank(this.names[i])) {
                this.drawCenteredString(fontRendererObj, this.names[i], textX, textY - 8, 0xF3EFE0);
            } else {
                this.drawCenteredString(fontRendererObj, String.valueOf(i), textX, textY - 8, 0xF3EFE0);
            }
            this.drawCenteredString(fontRendererObj, keyText, textX, textY + 4, 0xF3EFE0);

            startDeg = (float) (startDeg + 2 * Math.PI / count);
        }
    }

    private void drawRoulette(int mouseX, int mouseY) {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;

        int count = 8;
        float theta = (float) Math.atan2(mouseY - y, mouseX - x);
        if (theta < 0) {
            theta = (float) (Math.PI * 2 + theta);
        }
        float distance = MathHelper.sqrt_float((mouseY - y) * (mouseY - y) + (mouseX - x) * (mouseX - x));
        boolean isSelected = false;
        for (int i = 0; i < count; i++) {
            float spacingDeg = (float) (Math.PI / 90);
            float startDeg = (float) ((2 * Math.PI / count) * i + spacingDeg);
            float endDeg = (float) ((2 * Math.PI / count) * (i + 1) - spacingDeg);
            if (startDeg < theta && theta < endDeg && 50 < distance && distance < 100) {
                drawFan(tessellator, 25, 105, startDeg, endDeg, 0xf0FFB100);
                isSelected = true;
                this.selectId = i;
            } else {
                drawFan(tessellator, 25, 105, startDeg, endDeg, 0x90000000);
            }
        }
        if (!isSelected) {
            this.selectId = -1;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
    }

    private void drawFan(Tessellator tessellator, float rIn, float rOut, float startDeg, float endDeg, int color) {
        float alpha = (color >> 24 & 255) / 255.0F;
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        tessellator.startDrawing(GL11.GL_QUADS);
        tessellator.setColorRGBA_F(red, green, blue, alpha);
        tessellator.addVertex(x + rOut * MathHelper.cos(startDeg), y + rOut * MathHelper.sin(startDeg), 0);
        tessellator.addVertex(x + rIn * MathHelper.cos(startDeg), y + rIn * MathHelper.sin(startDeg), 0);
        tessellator.addVertex(x + rIn * MathHelper.cos(endDeg), y + rIn * MathHelper.sin(endDeg), 0);
        tessellator.addVertex(x + rOut * MathHelper.cos(endDeg), y + rOut * MathHelper.sin(endDeg), 0);
        tessellator.draw();
    }
}
