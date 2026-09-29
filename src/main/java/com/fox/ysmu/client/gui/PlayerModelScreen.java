package com.fox.ysmu.client.gui;

import com.fox.ysmu.Tags;
import com.fox.ysmu.ysmu;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ClientPackInfo;
import com.fox.ysmu.client.ClientPackRegistry;
import com.fox.ysmu.client.gui.button.*;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.RevokeModelGuiGrant;
import com.fox.ysmu.util.ModelIdUtil;
import com.google.common.collect.Maps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.*;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Model selection GUI: a preview column on the left and a ten-slot grid on the right.
 * <p>
 * The grid is folder aware, ported from the upstream 1.20 catalog browser: models that live in a directory under
 * {@code config/ysmu/custom} are folded into one folder tile, clicking the tile lists the models inside it, the back
 * arrow (or a right-click) returns to the parent folder, and the current folder path is drawn above the grid. The
 * rules themselves live in {@link ModelBrowserState}.
 */
public class PlayerModelScreen extends GuiScreen {
    private final ModelSelectionTarget target;
    /** Which folder is open and which page each folder is on; static state inside, like upstream. */
    private final ModelBrowserState browser = new ModelBrowserState();
    /**
     * Decoded display path to model id for every model the client has, not only the visible category: the browser
     * returns paths, and a star-category listing must still resolve to the ids it filtered.
     */
    private final Map<String, ResourceLocation> modelIdByPath = Maps.newHashMap();
    private GuiTextField textField;
    private int x;
    private int y;
    private int lastClientModelCount = -1;
    /** {@link ClientPackRegistry#version()} the tiles were built from, so late pack manifests refresh them. */
    private int lastPackVersion = -1;
    private boolean requestedModelSync;
    /** CU-16: 缩放因子在 initGui 时缓存，避免每帧重建 ScaledResolution。 */
    private int scaleFactor = 1;

    /**
     * CU-03: 该构造器可能在主菜单（{@code thePlayer == null}）被调用 —— 此时 target 持有 null 玩家并返回安全值
     * （预览实体为 null、型号/贴图为 null、apply 为 no-op），不会 NPE。新代码请优先使用
     * {@link #PlayerModelScreen(EntityPlayer)} 并显式传玩家，或在入口处就拒绝 null。
     */
    public PlayerModelScreen() {
        this(Minecraft.getMinecraft().thePlayer);
    }

    public PlayerModelScreen(EntityPlayer player) {
        this.target = ModelSelectionTarget.of(player);
    }

    /** Opens the same screen for a non-player entity, for example a companion handed over by another mod. */
    public PlayerModelScreen(ModelSelectionTarget target) {
        this.target = target;
    }

    public ModelSelectionTarget getTarget() {
        return target;
    }

    /**
     * Rebuilds the folder tree from the client's model list and applies the search box, the category and the open
     * folder. Folders are derived from the model ids themselves, so a pack that was added or removed while the screen
     * was open is picked up on the next call.
     */
    private void calculateModelList() {
        this.modelIdByPath.clear();
        for (ResourceLocation modelId : ClientModelManager.MODELS.keySet()) {
            // Two ids can only share a display path if two roots hold the same relative path; the first one wins so
            // the grid stays stable across calls.
            this.modelIdByPath.putIfAbsent(ModelIdUtil.getModelDisplayName(modelId), modelId);
        }
        // The server's pack manifests give folders their real name/description and cover; folders without one are
        // derived from the model paths alone.
        this.browser.rebuild(this.modelIdByPath.keySet(), ClientPackRegistry.names(), ClientPackRegistry.descriptions());
        this.browser.filter(this.textField == null ? "" : this.textField.getText(), this::isStarred);
    }

    private boolean isStarred(String displayPath) {
        ResourceLocation modelId = this.modelIdByPath.get(displayPath);
        return modelId != null && this.target.isStarred(modelId);
    }

    @Override
    public void initGui() {
        // clearWidgets() -> buttonList.clear()
        this.buttonList.clear();
        // 最后一道兜底：列表为空说明两条通道都没把模型送到（例如玩家在服务端 reload 之前就打开了界面）。
        // 这里无条件再向服务端要一次 legacy 同步 —— 它按客户端自报的 md5 求差集，代价只是真正缺的那几个文件，
        // 不是整批重传；用 17 协议开关把它关掉，只会让这个界面在模型丢失时永远空着。
        if (ClientModelManager.MODELS.isEmpty() && !this.requestedModelSync) {
            this.requestedModelSync = true;
            ClientModelManager.sendSyncModelMessage();
        }
        this.calculateModelList();
        this.lastClientModelCount = ClientModelManager.MODELS.size();
        this.lastPackVersion = ClientPackRegistry.version();

        this.x = (width - 420) / 2;
        this.y = (height - 235) / 2;
        // CU-16: 缓存缩放因子（initGui 在 setWorldAndResolution 之后调用，屏幕尺寸变化会重新 initGui）。
        this.scaleFactor = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();

        String perText = "";
        boolean focus = false;
        if (textField != null) {
            perText = textField.getText();
            focus = textField.isFocused();
        }
        textField = new GuiTextField(this.fontRendererObj, x + 144, y + 6, 158, 16);
        textField.setText(perText);
        textField.setTextColor(0xF3EFE0);
        textField.setFocused(focus);
        textField.setCursorPositionEnd();

        // 按钮创建和点击逻辑分离 使用唯一的 ID 来标识按钮
        // addRenderableWidget -> this.buttonList.add
        // CU-05: 贴图数量/收藏按钮与 ModelButton、STAR 过滤使用同一个 ModelSelectionTarget 主体。
        this.buttonList.add(new TextureCountButton(0, x + 5, y + 5, this.target));
        // Model information (authors with their avatars, tips, license). Upstream puts it in the header row; here the
        // header slot is taken by the texture counter, so it sits directly below it.
        this.buttonList.add(new FlatIconButton(7, x + 5, y + 27, 20, 20, 80, 16).setTooltips("gui.yes_steve_model.model.info"));
        this.buttonList.add(new FlatIconButton(1, x + 28, y + 5, 79, 20, 32, 16).setTooltips("gui.yes_steve_model.model.texture"));
        if (this.target.supportsStars()) {
            this.buttonList.add(new StarButton(2, x + 110, y + 5, this.target));
        }
        // Folder navigation: the back arrow only exists inside a folder, next to the breadcrumb. Upstream uses the
        // (0, 32) cell of the GUI atlas for it.
        if (!this.browser.currentPack().isEmpty()) {
            this.buttonList.add(new FlatIconButton(4, x + 110, y + 27, 20, 20, 0, 32).setTooltips("gui.back"));
        }
        this.buttonList.add(new FlatIconButton(3, x + 328, y + 5, 18, 18, 32, 0).setTooltips("gui.yes_steve_model.all_models"));
        if (this.target.supportsStars()) {
            this.buttonList.add(new FlatIconButton(5, x + 308, y + 5, 18, 18, 0, 0).setTooltips("gui.yes_steve_model.star_models"));
        }
        this.buttonList.add(new FlatIconButton(6, x + 397, y + 5, 18, 18, 16, 16).setTooltips("gui.yes_steve_model.config"));
        this.buttonList.add(new FlatIconButton(8, x + 377, y + 5, 18, 18, 80, 0).setTooltips("gui.yes_steve_model.open_model_folder.open"));
        this.buttonList.add(new FlatColorButton(9, x + 198, y + 215, 52, 14, I18n.format("gui.yes_steve_model.pre_page")));
        this.buttonList.add(new FlatColorButton(10, x + 308, y + 215, 52, 14, I18n.format("gui.yes_steve_model.next_page")));

        this.addGridButtons();
    }

    /** Fills the ten grid slots; folder tiles take the slots before model tiles, exactly like upstream's catalog. */
    private void addGridButtons() {
        int buttonId = 11;
        List<String> packs = this.browser.packs();
        List<String> models = this.browser.models();
        for (int i = 0; i < 10; i++) {
            int slotIndex = i + this.browser.page() * 10;
            int xStart = x + 143 + 55 * (i % 5);
            int yStart = y + 28 + 93 * (i / 5);
            if (slotIndex < packs.size()) {
                String hierarchy = packs.get(slotIndex);
                ClientPackInfo pack = ClientPackRegistry.get(hierarchy);
                this.buttonList.add(
                    new FolderButton(
                        buttonId++,
                        xStart,
                        yStart,
                        hierarchy,
                        this.browser.packName(hierarchy),
                        pack == null ? null : pack.icon,
                        pack == null ? null : pack.description));
                continue;
            }
            int modelIndex = slotIndex - packs.size();
            if (modelIndex >= 0 && modelIndex < models.size()) {
                ResourceLocation id = this.modelIdByPath.get(models.get(modelIndex));
                if (id != null) {
                    this.buttonList.add(
                        new ModelButton(
                            buttonId++,
                            xStart,
                            yStart,
                            Pair.of(id, ClientModelManager.MODELS.get(id)),
                            ClientModelManager.EXTRA_INFO.get(ModelIdUtil.getMainId(id)),
                            target));
                }
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                break;
            case 1:
                ResourceLocation currentModel = this.target.getModelId();
                if (currentModel != null) {
                    List<ResourceLocation> textures = ClientModelManager.MODELS.get(currentModel);
                    if (textures != null) {
                        // setScreen -> displayGuiScreen
                        this.mc.displayGuiScreen(new PlayerTextureScreen(this, currentModel, textures));
                    }
                }
                break;
            case 2:
                if (button instanceof StarButton) {
                    ((StarButton) button).doPress();
                }
                break;
            case 3:
                if (this.browser.category() != ModelBrowserState.Category.ALL) {
                    this.browser.category(ModelBrowserState.Category.ALL);
                    this.initGui();
                }
                break;
            case 4:
                // Back arrow: leave the open folder.
                this.browser.backToParent();
                this.initGui();
                break;
            case 5:
                if (this.browser.category() != ModelBrowserState.Category.STAR) {
                    this.browser.category(ModelBrowserState.Category.STAR);
                    this.initGui();
                }
                break;
            case 6:
                this.mc.displayGuiScreen(new ConfigScreen(this));
                break;
            case 7:
                // Model information: authors (with avatars), tips and license of the model applied to this target.
                if (this.target.getModelId() != null) {
                    this.mc.displayGuiScreen(new ModelInfoScreen(this, this.target.getModelId()));
                }
                break;
            case 8:
                this.mc.displayGuiScreen(new OpenModelFolderScreen(this));
                break;
            case 9:
                if (this.browser.page() > 0) {
                    this.browser.page(this.browser.page() - 1);
                    this.initGui();
                }
                break;
            case 10:
                if (this.browser.page() < this.browser.maxPage()) {
                    this.browser.page(this.browser.page() + 1);
                    this.initGui();
                }
                break;
            default:
                if (button instanceof FolderButton folder) {
                    this.browser.enterPack(folder.getHierarchy());
                    this.initGui();
                } else if (button instanceof ModelButton) {
                    ((ModelButton) button).doPress();
                }
                break;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // renderBackground(graphics) -> drawDefaultBackground()
        this.drawDefaultBackground();

        this.drawGradientRect(x, y, x + 135, y + 235, 0xff_222222, 0xff_222222);
        this.drawGradientRect(x + 138, y, x + 420, y + 235, 0xff_222222, 0xff_222222);
        this.drawGradientRect(x + 351, y + 7, x + 352, y + 21, 0xFF_F3EFE0, 0xFF_F3EFE0);
        // textField.render -> textField.drawTextBox
        textField.drawTextBox();

        // CU-16: 使用 initGui 缓存的缩放因子。
        int scale = this.scaleFactor;
        int scissorX = (this.x + 5) * scale;
        int scissorY = mc.displayHeight - ((this.y + 200) * scale);
        int scissorW = 125 * scale;
        int scissorH = 171 * scale;
        // CU-13: scissor 必须在 finally 里恢复，否则纸娃娃渲染一旦抛异常，整个界面都会被裁剪。
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        try {
            GL11.glScissor(scissorX, scissorY, scissorW, scissorH);
            // func_147046_a(x,y,scale,toMouseX,toMouseY,entity)
            // CU-03: 预览实体为 null（主菜单打开的界面 / target 持有 null 玩家）时跳过纸娃娃，
            // 否则 GuiInventory.func_147046_a 会在 GuiInventory.java:96 解引用 null 而 NPE。
            EntityLivingBase preview = this.target.getPreviewEntity();
            if (preview != null) {
                GuiInventory.func_147046_a(x + 67, y + 190, 70, x + 67 - mouseX, y + 180 - 95 - mouseY, preview);
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        ResourceLocation selectedModel = this.target.getModelId();
        if (selectedModel != null) {
            // The tile labels are leaf names, so the preview name uses the leaf too instead of the whole path.
            String modelName = ModelIdUtil.getModelFileName(selectedModel);
            // font -> fontRendererObj
            List<String> modelNameSplit = fontRendererObj.listFormattedStringToWidth(modelName, 125);
            int lineY = y + 205;
            for (String line : modelNameSplit) {
                int nameWidth = fontRendererObj.getStringWidth(line);
                this.drawString(fontRendererObj, line, x + (135 - nameWidth) / 2, lineY, 0xF3EFE0);
                lineY += 10;
            }
        }

        // Breadcrumb of the open folder, drawn above the grid like upstream's "currentPack" line.
        if (!this.browser.currentPack().isEmpty()) {
            this.drawString(fontRendererObj, this.browser.currentPack(), x + 142, y - 12, 0xAAAAAA);
        }

        if (textField.getText().isEmpty() && !textField.isFocused()) {
            this.drawString(fontRendererObj, EnumChatFormatting.ITALIC + I18n.format("gui.yes_steve_model.search"), x + 148, y + 10, 0x777777);
        }

        // CU-18: 空模型列表时给出空态提示（此前只显示"1/1"，属误导性 UI）。
        if (this.browser.models().isEmpty() && this.browser.packs().isEmpty()) {
            String emptyText = I18n.format("gui.yes_steve_model.empty_models");
            this.drawString(
                fontRendererObj,
                emptyText,
                x + 138 + (282 - fontRendererObj.getStringWidth(emptyText)) / 2,
                y + 120,
                0x777777);
        }

        String pageInfo = String.format("%d/%d", this.browser.page() + 1, this.browser.maxPage() + 1);
        this.drawString(fontRendererObj, pageInfo, x + 138 + (282 - fontRendererObj.getStringWidth(pageInfo)) / 2, y + 223 - fontRendererObj.FONT_HEIGHT / 2, 0xF3EFE0);

        String debugInfo = String.format("%s-%s", "1.7.10", Tags.VERSION);
        this.drawString(fontRendererObj, debugInfo, x + 2, y + 226, 0x555555);
        // super.render -> super.drawScreen, 这会绘制所有按钮
        super.drawScreen(mouseX, mouseY, partialTicks);
        // Render tooltips
        for (Object button : this.buttonList) {
            if (button instanceof FlatIconButton f) {
                if (f.func_146115_a() && f.tooltips != null && !f.tooltips.isEmpty()) {
                    this.func_146283_a(f.tooltips, mouseX, mouseY);
                }
            }
            if (button instanceof ModelButton m) {
                if (m.func_146115_a() && m.tooltips != null && !m.tooltips.isEmpty()) {
                    List<String> tooltipStrings = m.tooltips.stream().map(IChatComponent::getFormattedText).collect(Collectors.toList());
                    this.func_146283_a(tooltipStrings, mouseX, mouseY);
                }
            }
            if (button instanceof FolderButton folder) {
                if (folder.func_146115_a() && !folder.tooltips.isEmpty()) {
                    this.func_146283_a(folder.tooltips, mouseX, mouseY);
                }
            }
        }
    }

    // tick -> updateScreen
    @Override
    public void updateScreen() {
        this.textField.updateCursorCounter();
        int currentModelCount = ClientModelManager.MODELS.size();
        // Pack manifests arrive in the same sync round as the models; when they land after the last model was
        // registered there is no count change to notice, so the pack version is watched as well.
        int currentPackVersion = ClientPackRegistry.version();
        if (currentModelCount != this.lastClientModelCount || currentPackVersion != this.lastPackVersion) {
            this.initGui();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        this.textField.mouseClicked(mouseX, mouseY, button);
        // 1.7.10 GuiScreen only dispatches left clicks, so a right click is always "go up one folder".
        if (button == 1 && !this.browser.currentPack().isEmpty()) {
            this.mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.browser.backToParent();
            this.initGui();
        }
    }

    // charTyped and keyPressed -> keyTyped
    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        String perText = this.textField.getText();
        if (this.textField.textboxKeyTyped(typedChar, keyCode)) {
            if (!Objects.equals(perText, this.textField.getText())) {
                this.browser.resetPage();
                this.initGui();
            }
        } else {
            if (this.textField.isFocused() && keyCode != 1) {
                return; // 阻止其他按键（如E键）关闭GUI
            }
            super.keyTyped(typedChar, keyCode);
        }
    }

    // mouseScrolled -> handleMouseInput
    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dWheel = Mouse.getDWheel();
        if (dWheel != 0) {
            int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (inRange(mouseX, mouseY)) {
                scrollPage(dWheel);
            }
        }
    }

    private boolean inRange(int mouseX, int mouseY) {
        boolean isInWidthRange = (x + 143) < mouseX && mouseX < (x + 430);
        boolean isInHeightRange = (y + 25) < mouseY && mouseY < (y + 235);
        return isInWidthRange && isInHeightRange;
    }

    private void scrollPage(int delta) {
        if (delta > 0 && this.browser.page() > 0) {
            this.browser.page(this.browser.page() - 1);
            this.mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.initGui();
        }
        if (delta < 0 && this.browser.page() < this.browser.maxPage()) {
            this.browser.page(this.browser.page() + 1);
            this.mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.initGui();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /**
     * NF-01: 关闭界面时撤销 {@code api/ModelGuiApi} 发放的实体选择授权。
     * <p>
     * 包由 fix-network 交付（{@code network/message/RevokeModelGuiGrant}，packet id 98；
     * {@code NETWORK_PROTOCOL} 已随之 bump 到 2）。服务端 handler 只回收**发送者自己**的授权：
     * {@code RevokeModelGuiGrant.Handler}（{@code network/message/RevokeModelGuiGrant.java:51-68}）调用
     * {@code ModelGuiApi.revokeSelectionGrant(sender, entityId)}，而 ModelGuiApi 的授权表按发送者 UUID 归档，
     * 因此客户端即使伪造别人的 entity id 也撤不掉别人的授权。
     * <p>
     * {@code getGrantId() == -1} 表示"该 target 不需要授权"（本地玩家改自己，见
     * {@link ModelSelectionTarget#getGrantId()}），此时不发送。
     */
    @Override
    public void onGuiClosed() {
        int grantId = this.target.getGrantId();
        if (grantId != -1) {
            ysmu.LOG.debug("Model selection GUI closed, revoking grant for entity {}", grantId);
            NetworkHandler.CHANNEL.sendToServer(new RevokeModelGuiGrant(grantId));
        }
    }
}
