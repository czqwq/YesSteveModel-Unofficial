package com.fox.ysmu.client.gui.button;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.ysmu;

/**
 * One folder (upstream: "pack") tile in the model selection grid; the port of the 1.20 tree's
 * {@code com.elfmcys.ysm.client.gui.button.PackButton}.
 * <p>
 * The tile keeps the model tile's 52x90 footprint so folders and models share the grid, but is filled with upstream's
 * folder purple and the {@code default_pack_icon.png} artwork so a folder is never mistaken for a model. The label is
 * the folder's last path segment; the full hierarchy is offered as a tooltip, which is how a nested folder
 * ({@code wine_fox/2024/}) stays distinguishable from a top-level one.
 */
public class FolderButton extends GuiButton {

    private static final ResourceLocation DEFAULT_ICON = new ResourceLocation(ysmu.MODID, "texture/default_pack_icon.png");
    private static final int BACKGROUND_COLOR = 0xFF_9B51E0;
    private static final int BORDER_COLOR = 0xFF_E1BEE7;
    private static final int LABEL_COLOR = 0x55_5555;
    /** Same label width as {@link ModelButton}, so folder and model labels wrap identically. */
    private static final int LABEL_WIDTH = 45;

    private final String hierarchy;
    /** The pack's own cover, or {@code null} to use the generic folder art. */
    private final ResourceLocation icon;
    /** Rendered by {@code PlayerModelScreen} while hovered; the button itself has no screen reference. */
    public final List<String> tooltips;

    /**
     * @param hierarchy   the folder this tile opens, as a hierarchy with a trailing slash ({@code wine_fox/})
     * @param displayName the label, from the pack manifest when the server announced one
     * @param icon        the pack cover texture, or {@code null} for the generic folder art
     * @param description the pack description for the tooltip, or {@code null}/blank to show the path instead
     */
    public FolderButton(int id, int pX, int pY, String hierarchy, String displayName, ResourceLocation icon,
        String description) {
        super(id, pX, pY, 52, 90, displayName);
        this.hierarchy = hierarchy;
        this.icon = icon;
        this.tooltips = Collections.singletonList(
            description == null || description.trim()
                .isEmpty() ? hierarchy : description);
    }

    /** The folder this tile opens, as a hierarchy with a trailing slash, for example {@code wine_fox/}. */
    public String getHierarchy() {
        return this.hierarchy;
    }

    /**
     * Draws the whole bound texture across the tile with texture coordinates 0..1.
     * <p>
     * {@code drawTexturedModalRect} cannot be used here: it divides the source rectangle by 256 because it assumes a
     * 256x256 GUI atlas, so a 52x90 pack cover only contributed its top-left fifth and third (the reported "cover
     * shows a corner, magnified"). Upstream passes the tile size as the texture size, which maps the whole image onto
     * the tile, and 0..1 is that same mapping.
     */
    private void drawFullTexture(int x, int y, int width, int height) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x, y + height, this.zLevel, 0.0D, 1.0D);
        tessellator.addVertexWithUV(x + width, y + height, this.zLevel, 1.0D, 1.0D);
        tessellator.addVertexWithUV(x + width, y, this.zLevel, 1.0D, 0.0D);
        tessellator.addVertexWithUV(x, y, this.zLevel, 0.0D, 0.0D);
        tessellator.draw();
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height;

        this.drawGradientRect(
            this.xPosition,
            this.yPosition,
            this.xPosition + this.width,
            this.yPosition + this.height,
            BACKGROUND_COLOR,
            BACKGROUND_COLOR);

        boolean blending = GL11.glGetBoolean(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager()
            .bindTexture(this.icon == null ? DEFAULT_ICON : this.icon);
        drawFullTexture(this.xPosition, this.yPosition, this.width, this.height);
        if (!blending) {
            GL11.glDisable(GL11.GL_BLEND);
        }

        List<String> split = font.listFormattedStringToWidth(this.displayString, LABEL_WIDTH);
        if (split.size() > 1) {
            this.drawCenteredString(font, split.get(0), this.xPosition + this.width / 2, this.yPosition + this.height - 19, LABEL_COLOR);
            String secondLine = split.get(1);
            if (split.size() > 2) {
                secondLine = font.trimStringToWidth(secondLine, LABEL_WIDTH - font.getStringWidth("...")) + "...";
            }
            this.drawCenteredString(font, secondLine, this.xPosition + this.width / 2, this.yPosition + this.height - 10, LABEL_COLOR);
        } else {
            this.drawCenteredString(font, this.displayString, this.xPosition + this.width / 2, this.yPosition + this.height - 15, LABEL_COLOR);
        }

        if (this.field_146123_n) {
            this.drawGradientRect(this.xPosition, this.yPosition + 1, this.xPosition + 1, this.yPosition + this.height - 1, BORDER_COLOR, BORDER_COLOR);
            this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + 1, BORDER_COLOR, BORDER_COLOR);
            this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1, this.xPosition + this.width, this.yPosition + this.height - 1, BORDER_COLOR, BORDER_COLOR);
            this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1, this.xPosition + this.width, this.yPosition + this.height, BORDER_COLOR, BORDER_COLOR);
        }
    }
}
