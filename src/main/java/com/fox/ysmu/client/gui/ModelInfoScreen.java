package com.fox.ysmu.client.gui;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelMetadataRegistry;
import com.fox.ysmu.client.gui.button.AuthorButton;
import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

/**
 * Model information screen: the model's name, tips, license, its authors, each on a tile with the avatar declared
 * in the model's {@code ysm.json}, and up to two of the model's declared metadata links. This is the 1.7.10 port of
 * the 1.20 tree's {@code com.elfmcys.ysm.client.gui.ModelInfoScreen}, and it is the only place in YSMU where author
 * avatars are rendered - the metadata was already parsed and synced before this screen existed, but nothing displayed
 * it.
 * <p>
 * Upstream shows five author tiles per page with {@code <}/{@code >} buttons and its two link buttons in a column on
 * the right ({@code ModelInfoScreen.java:98-127}); the author layout is the same here, with the screen's 420x235
 * frame matching the model selection screen it returns to. The links are drawn as one row under the author row
 * instead of upstream's right-hand column, because in this frame that column is where the author tiles are.
 */
public class ModelInfoScreen extends GuiScreen {

    private static final int AUTHORS_PER_PAGE = 5;
    private static final int AUTHOR_X_STEP = 75;
    private static final int AUTHOR_HEIGHT = 130;
    /** Left edge of the author row, and the first free column after it, in frame coordinates. */
    private static final int AUTHOR_X = 25;
    private static final int AUTHOR_Y = 52;
    private static final int AUTHORS_WIDTH = AUTHOR_X + AUTHOR_X_STEP * AUTHORS_PER_PAGE;
    /** How many links upstream draws: {@code Math.min(links.size(), 2)} (its {@code ModelInfoScreen.java:112}). */
    private static final int MAX_LINKS = 2;
    /** The link row: below the author row, above the page indicator, in frame coordinates. */
    private static final int LINK_X = 5;
    private static final int LINK_Y = 186;
    private static final int LINK_WIDTH = 90;
    private static final int LINK_HEIGHT = 18;
    private static final int LINK_X_STEP = 95;
    /** Widget id of the first link button; ids 0-2 are the page arrows and the return button, 10+ are author tiles. */
    private static final int LINK_BUTTON_ID_BASE = 20;

    private final PlayerModelScreen parent;
    private final ResourceLocation modelId;
    /** The URLs of the link buttons currently on screen, in button order; parallel to {@link #linkButtons}. */
    private final List<String> linkUrls = new ArrayList<>();
    private final List<FlatColorButton> linkButtons = new ArrayList<>();
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

        this.addLinkButtons();
    }

    /**
     * The model's declared links, one button each, at most {@link #MAX_LINKS} of them - upstream's own cap
     * ({@code ModelInfoScreen.java:111-124}). The label is upstream's rule: a preset for the {@code home} and
     * {@code donate} types ({@code gui.yes_steve_model.url.home} / {@code ...url.donate}, its
     * {@code LINK_TYPE_PRESET} at {@code :37-40}), else the declared type itself. The URL goes on the tooltip, since
     * a pack may declare a type no preset names.
     */
    private void addLinkButtons() {
        this.linkButtons.clear();
        this.linkUrls.clear();
        List<Map.Entry<String, String>> links = visibleLinks(this.modelId);
        for (int i = 0; i < links.size(); i++) {
            Map.Entry<String, String> link = links.get(i);
            FlatColorButton button = new FlatColorButton(
                LINK_BUTTON_ID_BASE + i,
                x + LINK_X + LINK_X_STEP * i,
                y + LINK_Y,
                LINK_WIDTH,
                LINK_HEIGHT,
                linkLabel(link.getKey()));
            button.setTooltips(Collections.singletonList(link.getValue()));
            this.linkButtons.add(button);
            this.linkUrls.add(link.getValue());
            this.buttonList.add(button);
        }
    }

    /**
     * The links this screen shows for a model: its declared order, cut to {@link #MAX_LINKS}. Static and free of
     * client state so the cap and the order can be checked without a live screen.
     */
    static List<Map.Entry<String, String>> visibleLinks(@Nullable ResourceLocation modelId) {
        Map<String, String> links = ModelConfigRegistry.linksFor(modelId);
        if (links.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, String>> visible = new ArrayList<>(MAX_LINKS);
        for (Map.Entry<String, String> link : links.entrySet()) {
            if (visible.size() >= MAX_LINKS) {
                break;
            }
            visible.add(link);
        }
        return visible;
    }

    /** Upstream's label rule ({@code ModelInfoScreen.java:117-121}): a home/donate preset, else the type itself. */
    private static String linkLabel(@Nullable String type) {
        if ("home".equals(type)) {
            return I18n.format("gui.yes_steve_model.url.home");
        }
        if ("donate".equals(type)) {
            return I18n.format("gui.yes_steve_model.url.donate");
        }
        return type == null ? "" : type;
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
                } else if (button.id >= LINK_BUTTON_ID_BASE) {
                    // The ids are re-issued in declaration order by addLinkButtons, so the offset is the index.
                    int linkIndex = button.id - LINK_BUTTON_ID_BASE;
                    if (linkIndex < this.linkUrls.size()) {
                        openLink(this.linkUrls.get(linkIndex));
                    }
                }
                break;
        }
    }

    /**
     * Opens a declared link. Upstream opens a confirmation screen and then hands the URL to the platform
     * ({@code ModelInfoScreen.java:130-139}: {@code ConfirmLinkScreen} then {@code Util.getPlatform().openUri});
     * 1.7.10 has neither the confirmation screen nor a platform helper, so this is the external-open shape the port
     * already uses for model folders ({@code OpenModelFolderScreen.java:32-39}: {@code Desktop}). The click is the
     * confirmation, and because a JVM without a desktop would otherwise swallow the click silently, the URL is copied
     * to the clipboard - the same interaction this very screen uses for an author's contact
     * ({@link #copyFirstContact}) - and the reason is logged.
     */
    private void openLink(@Nullable String url) {
        if (url == null || url.trim()
            .isEmpty()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop()
                .isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop()
                    .browse(new URI(url));
                return;
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Could not open the model's link {} in a browser", url, e);
        }
        setClipboardString(url);
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
        // The pack's own language table for the running locale comes first, then the name the geometry declares, then
        // the leaf name: upstream ModelInfoView.translateOr:91-93 returns the caller's default when neither the
        // requested locale nor en_us declares the key.
        String title = ClientModelMetadataRegistry.displayName(
            this.modelId,
            ClientModelMetadataRegistry.currentLocale(),
            ModelIdUtil.getModelFileName(this.modelId));
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
            } else if (button instanceof FlatColorButton && this.linkButtons.contains(button)) {
                // A link button's tooltip is the URL itself (see addLinkButtons), which is the only place a pack's
                // custom link type says where it goes. Matching on this screen's own link buttons keeps the page
                // arrows' tooltips out of this loop, which never drew them before.
                FlatColorButton linkButton = (FlatColorButton) button;
                if (linkButton.func_146115_a() && linkButton.tooltips != null && !linkButton.tooltips.isEmpty()) {
                    this.func_146283_a(new ArrayList<>(linkButton.tooltips), mouseX, mouseY);
                }
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
