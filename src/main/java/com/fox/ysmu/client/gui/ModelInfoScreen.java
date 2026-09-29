package com.fox.ysmu.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelMetadataRegistry;
import com.fox.ysmu.client.gui.button.AuthorButton;
import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * Model information screen: the model's name, tips, license and its authors, each on a tile with the avatar declared
 * in the model's {@code ysm.json}. This is the 1.7.10 port of the 1.20 tree's
 * {@code com.elfmcys.ysm.client.gui.ModelInfoScreen}, and it is the only place in YSMU where author avatars are
 * rendered - the metadata was already parsed and synced before this screen existed, but nothing displayed it.
 * <p>
 * Upstream shows five author tiles per page with {@code <}/{@code >} buttons; the same layout is used here, with the
 * screen's 420x235 frame matching the model selection screen it returns to.
 */
public class ModelInfoScreen extends GuiScreen {

    private static final int AUTHORS_PER_PAGE = 5;
    private static final int AUTHOR_X_STEP = 75;
    private static final int AUTHOR_HEIGHT = 130;
    /** Left edge of the author row, and the first free column after it, in frame coordinates. */
    private static final int AUTHOR_X = 25;
    private static final int AUTHOR_Y = 52;
    private static final int AUTHORS_WIDTH = AUTHOR_X + AUTHOR_X_STEP * AUTHORS_PER_PAGE;

    private final PlayerModelScreen parent;
    private final ResourceLocation modelId;
    private int startAuthorIndex;
    private int x;
    private int y;

    public ModelInfoScreen(PlayerModelScreen parent, ResourceLocation modelId) {
        this.parent = parent;
        this.modelId = modelId;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.x = (width - 420) / 2;
        this.y = (height - 235) / 2;

        List<ClientModelMetadataRegistry.Author> authors = authors();
        if (authors.size() <= this.startAuthorIndex) {
            this.startAuthorIndex = 0;
        }

        for (int slot = 0; slot < AUTHORS_PER_PAGE; slot++) {
            int index = this.startAuthorIndex + slot;
            // Empty slots are rendered as placeholders, like upstream: the row keeps its shape and the page arrows
            // stay where the user last saw them.
            ClientModelMetadataRegistry.Author author = index < authors.size() ? authors.get(index) : null;
            this.buttonList.add(
                new AuthorButton(10 + slot, x + AUTHOR_X + AUTHOR_X_STEP * slot, y + AUTHOR_Y, author));
        }

        this.buttonList.add(
            new FlatColorButton(0, x + 2, y + AUTHOR_Y, 18, AUTHOR_HEIGHT, "<")
                .setTooltips("gui.yes_steve_model.pre_page"));
        this.buttonList.add(
            new FlatColorButton(1, x + AUTHORS_WIDTH, y + AUTHOR_Y, 18, AUTHOR_HEIGHT, ">")
                .setTooltips("gui.yes_steve_model.next_page"));
        this.buttonList.add(
            new FlatColorButton(2, x + 330, y + 210, 85, 18, I18n.format("gui.yes_steve_model.model.return")));
    }

    private List<ClientModelMetadataRegistry.Author> authors() {
        ClientModelMetadataRegistry.Metadata metadata = ClientModelMetadataRegistry.get(this.modelId);
        return metadata == null ? Collections.emptyList() : metadata.authors;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                if (this.startAuthorIndex > 0) {
                    this.startAuthorIndex = Math.max(0, this.startAuthorIndex - AUTHORS_PER_PAGE);
                    this.initGui();
                }
                break;
            case 1:
                if (this.startAuthorIndex + AUTHORS_PER_PAGE < authors().size()) {
                    this.startAuthorIndex += AUTHORS_PER_PAGE;
                    this.initGui();
                }
                break;
            case 2:
                this.mc.displayGuiScreen(this.parent);
                break;
            default:
                if (button instanceof AuthorButton) {
                    copyFirstContact((AuthorButton) button);
                }
                break;
        }
    }

    /**
     * Copies the author's first contact. Upstream opens {@code http(s)} contacts in the browser after a confirmation
     * screen and copies everything else; this port copies in both cases, which needs no external process and still
     * gives the user the address.
     */
    private void copyFirstContact(AuthorButton button) {
        String contact = button.firstContact();
        if (contact == null) {
            return;
        }
        setClipboardString(contact);
        button.copiedContact = contact;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawGradientRect(x, y, x + 420, y + 235, 0xff_222222, 0xff_222222);

        ClientModelMetadataRegistry.Metadata metadata = ClientModelMetadataRegistry.get(this.modelId);
        String title = metadata == null || metadata.name.trim()
            .isEmpty() ? ModelIdUtil.getModelFileName(this.modelId) : metadata.name;
        this.drawCenteredString(fontRendererObj, title, x + 210, y + 6, 0xFF_AA00);

        if (metadata != null && !metadata.tips.trim()
            .isEmpty()) {
            int lineY = y + 20;
            for (String line : fontRendererObj.listFormattedStringToWidth(metadata.tips, 410)) {
                if (lineY > y + AUTHOR_Y - fontRendererObj.FONT_HEIGHT) {
                    break;
                }
                this.drawString(fontRendererObj, line, x + 5, lineY, 0xAA_AAAA);
                lineY += fontRendererObj.FONT_HEIGHT;
            }
        }

        List<ClientModelMetadataRegistry.Author> authors = authors();
        String pageInfo = String.format(
            "%d/%d",
            this.startAuthorIndex / AUTHORS_PER_PAGE + 1,
            Math.max(1, (authors.size() + AUTHORS_PER_PAGE - 1) / AUTHORS_PER_PAGE));
        this.drawString(fontRendererObj, pageInfo, x + 205, y + 210, 0xF3EFE0);

        if (metadata != null && !metadata.license.trim()
            .isEmpty()) {
            this.drawString(
                fontRendererObj,
                I18n.format("gui.yes_steve_model.model.license", metadata.license),
                x + 5,
                y + 220,
                0xFF_FFFFFF);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        for (Object button : this.buttonList) {
            if (button instanceof AuthorButton authorButton) {
                if (!authorButton.func_146115_a()) {
                    continue;
                }
                List<String> tooltips = new ArrayList<>(authorButton.tooltips);
                if (authorButton.copiedContact != null) {
                    tooltips.add(EnumChatFormatting.GREEN + authorButton.copiedContact);
                }
                if (!tooltips.isEmpty()) {
                    tooltips.add(
                        EnumChatFormatting.DARK_GRAY
                            + I18n.format("gui.yes_steve_model.model.info.contact.click_hint"));
                    this.func_146283_a(tooltips, mouseX, mouseY);
                }
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
