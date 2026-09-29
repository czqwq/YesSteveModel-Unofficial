package com.fox.ysmu.client.gui.button;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.client.ClientModelMetadataRegistry;

/**
 * One author tile on the model information screen: the author's avatar, name, role and comment; the port of the 1.20
 * tree's {@code com.elfmcys.ysm.client.gui.button.AuthorButton}, sized 70x130 like upstream.
 * <p>
 * The avatar comes from the model's own {@code ysm.json} ({@code "avatar": "avatar/zljjxmm.png"}) and is uploaded by
 * {@link ClientModelMetadataRegistry}. When an author has no avatar image - which is the case for every model that
 * only travelled over the legacy channel - the tile keeps its background and shows a placeholder instead of a broken
 * texture.
 * <p>
 * Upstream opens a confirmation screen and then the system browser for {@code http(s)} contacts, and copies anything
 * else to the clipboard. This port always copies: launching a browser from a GUI is not something a 1.7.10 client can
 * undo, and the contact is equally usable pasted from the clipboard.
 */
public class AuthorButton extends GuiButton {

    private static final int WIDTH = 70;
    private static final int HEIGHT = 130;
    private static final int AVATAR_SIZE = 64;
    private static final int BACKGROUND = 0x8F_434242;
    private static final int HOVER_BACKGROUND = 0x8F_306BAC;
    private static final int NAME_COLOR = 0xF3EFE0;
    private static final int ROLE_COLOR = 0x55_FF55;
    private static final int COMMENT_COLOR = 0xFF_FFFFFF;

    private final ClientModelMetadataRegistry.Author author;
    /** Contacts as pre-formatted tooltip lines; the screen renders them while hovered. */
    public final List<String> tooltips = new ArrayList<>();
    /** Set by the screen after it copied a contact, so the tooltip can say so. */
    public String copiedContact;

    public AuthorButton(int id, int pX, int pY, ClientModelMetadataRegistry.Author author) {
        super(id, pX, pY, WIDTH, HEIGHT, "");
        this.author = author;
        if (author != null) {
            for (Map.Entry<String, String> contact : author.contacts.entrySet()) {
                tooltips.add(EnumChatFormatting.GRAY + contact.getKey() + ": " + EnumChatFormatting.WHITE + contact.getValue());
            }
        }
    }

    public ClientModelMetadataRegistry.Author getAuthor() {
        return author;
    }

    /** The contact this tile would copy, or {@code null} when the author declared none. */
    public String firstContact() {
        if (author == null) {
            return null;
        }
        for (String value : author.contacts.values()) {
            if (value != null && !value.trim()
                .isEmpty()) {
                return value;
            }
        }
        return null;
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

        int background = this.field_146123_n ? HOVER_BACKGROUND : BACKGROUND;
        this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, background, background);

        if (author != null && author.avatar != null) {
            boolean blending = GL11.glGetBoolean(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager()
                .bindTexture(author.avatar);
            drawScaledAvatar(this.xPosition + 3, this.yPosition + 3);
            if (!blending) {
                GL11.glDisable(GL11.GL_BLEND);
            }
        }

        if (author == null) {
            this.drawCenteredString(font, "......", this.xPosition + this.width / 2, this.yPosition + this.height / 2, 0xAA_AAAA);
            return;
        }

        String name = trim(font, author.name);
        this.drawCenteredString(font, name, this.xPosition + this.width / 2, this.yPosition + 72, NAME_COLOR);
        this.drawCenteredString(font, trim(font, author.role), this.xPosition + this.width / 2, this.yPosition + 83, ROLE_COLOR);

        int lineY = this.yPosition + 95;
        for (String line : font.listFormattedStringToWidth(author.comment, AVATAR_SIZE)) {
            if (lineY + font.FONT_HEIGHT > this.yPosition + this.height) {
                break;
            }
            this.drawString(font, line, this.xPosition + 3, lineY, COMMENT_COLOR);
            lineY += font.FONT_HEIGHT;
        }
    }

    /**
     * Draws the whole avatar texture into the 64x64 box. The UVs are 0..1 rather than pixels because authors ship
     * avatars of any size ({@code 64x64}, {@code 512x512}, JPEG crops); {@code drawTexturedModalRect} assumes a
     * 256x256 atlas and would show a scaled-down corner of a larger image.
     */
    private void drawScaledAvatar(int x, int y) {
        net.minecraft.client.renderer.Tessellator tessellator = net.minecraft.client.renderer.Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x, y + AVATAR_SIZE, this.zLevel, 0.0D, 1.0D);
        tessellator.addVertexWithUV(x + AVATAR_SIZE, y + AVATAR_SIZE, this.zLevel, 1.0D, 1.0D);
        tessellator.addVertexWithUV(x + AVATAR_SIZE, y, this.zLevel, 1.0D, 0.0D);
        tessellator.addVertexWithUV(x, y, this.zLevel, 0.0D, 0.0D);
        tessellator.draw();
    }

    private static String trim(FontRenderer font, String text) {
        if (text == null) {
            return "";
        }
        if (font.getStringWidth(text) <= AVATAR_SIZE) {
            return text;
        }
        return font.trimStringToWidth(text, AVATAR_SIZE - font.getStringWidth("...")) + "...";
    }
}
