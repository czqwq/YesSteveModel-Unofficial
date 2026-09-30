package com.fox.ysmu.client.gui.button;

import com.fox.ysmu.client.gui.ModelSelectionTarget;
import com.fox.ysmu.client.ClientModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

public class TextureCountButton extends FlatColorButton {
    /** CU-05: 与 ModelButton/StarButton 一样使用界面持有的目标，而不是本地玩家。 */
    private final ModelSelectionTarget target;

    public TextureCountButton(int id, int x, int y, ModelSelectionTarget target) {
        super(id, x, y, 20, 20, "");
        this.target = target;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        // 在绘制前更新显示文本
        this.updateDisplayString();
        // 调用父类方法进行绘制
        super.drawButton(mc, mouseX, mouseY);
    }

    private void updateDisplayString() {
        ResourceLocation modelId = this.target.getModelId();
        if (modelId != null && ClientModelManager.MODELS.containsKey(modelId)) {
            this.displayString = String.valueOf(
                ClientModelManager.MODELS.get(modelId)
                    .size());
            return;
        }
        this.displayString = "";
    }
}
