package com.fox.ysmu.client.gui;

import java.io.File;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.client.gui.button.ModelInfoButton;
import com.fox.ysmu.client.upload.UploadManager;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.upload.ModelFileValidator;
import com.fox.ysmu.model.upload.ModelUploadTarget;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.HandleFile;
import com.fox.ysmu.network.message.RefreshModelManage;
import com.fox.ysmu.network.message.RequestServerModelInfo;
import com.fox.ysmu.ysmu;

/**
 * 服务端模型管理界面（phase55，LgeacyYSM {@code ModelManageScreen} 的 1.7.10 移植）：由 {@code /ysm manage}
 * 打开，列出 custom/auth 两个根的条目，支持上传、删除、移动（custom↔auth）、重命名。
 *
 * <p>与 Lgeacy 的差异：选文件用 AWT {@link java.awt.FileDialog}（1.7.10 没有 TinyFileDialogs）并保留可手填
 * 路径；上传走本仓的分片 + 完整校验通道而不是单包 {@code UploadFile}；管理动作失败由服务端回聊天提示；
 * 重命名输入框预填当前名字。危险操作都在服务端 {@code ServerModelManage} 校验，本界面不暂停单人游戏。</p>
 */
@SideOnly(Side.CLIENT)
public class ModelManageScreen extends GuiScreen {

    /** 列表包打开界面的入口；由 {@code RequestServerModelInfo} 的 handler 在客户端 tick 调用。 */
    public static void openFromPacket(List<RequestServerModelInfo.Info> customModels,
        List<RequestServerModelInfo.Info> authModels) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null) {
            minecraft.displayGuiScreen(new ModelManageScreen(customModels, authModels));
        }
    }

    private static final int MAX_COUNT = 13;
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 235;

    private static final int ID_TAB_CUSTOM = 0;
    private static final int ID_TAB_AUTH = 1;
    private static final int ID_UPLOAD = 2;
    private static final int ID_PREV = 3;
    private static final int ID_NEXT = 4;
    private static final int ID_DELETE = 5;
    private static final int ID_MOVE = 6;
    private static final int ID_RENAME = 7;
    private static final int ID_CONFIRM = 8;
    private static final int ID_CANCEL = 9;
    private static final int ID_BROWSE = 10;
    private static final int ID_ROW_BASE = 100;

    private static final int COLOR_PANEL = 0xff_222222;
    private static final int COLOR_HEADER = 0xff_434242;
    private static final int COLOR_TEXT = 0xFFF3EFE0;
    private static final int COLOR_DIM = 0xFFC0C0C0;
    private static final int COLOR_ERROR = 0xFFFF5555;

    /** Lgeacy 把页签与页码放在静态字段里，重开界面保持上次位置；这里保持同样的行为。 */
    private static boolean isCustomModels = true;
    private static int page = 0;

    private final List<RequestServerModelInfo.Info> customModels;
    private final List<RequestServerModelInfo.Info> authModels;

    private int index = -1;
    private Action action = Action.EMPTY;
    private int modelsCount;
    private int x;
    private int y;

    private GuiTextField renameField;
    private GuiTextField pathField;
    private String uploadError;
    /** 原生文件框选中的路径（回调发生在客户端线程之外，存下来在 initGui 里填进输入框）。 */
    private String pendingPath;

    public ModelManageScreen(List<RequestServerModelInfo.Info> customModels,
        List<RequestServerModelInfo.Info> authModels) {
        this.customModels = customModels;
        this.authModels = authModels;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // layout
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.calculateList();
        this.x = (this.width - PANEL_WIDTH) / 2;
        this.y = (this.height - PANEL_HEIGHT) / 2;
        this.addTopButtons();
        this.addPageButtons();
        this.addModelInfoButtons();
        if (this.index >= 0 && this.index < this.getModels()
            .size()) {
            this.addActionButtons();
        }
        if (this.action != Action.EMPTY) {
            this.addExtraButtons();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        // 上传/列表刷新是异步的，暂停单人游戏会让进度卡住（Lgeacy 的 1.20 Screen 也不暂停）。
        return false;
    }

    private void calculateList() {
        this.modelsCount = this.getModels()
            .size();
        if (this.modelsCount == 0 || (this.modelsCount - 1) / MAX_COUNT < page) {
            page = 0;
        }
    }

    private void addTopButtons() {
        FlatColorButton customButton = new FlatColorButton(
            ID_TAB_CUSTOM,
            this.x + 5,
            this.y + 5,
            80,
            18,
            I18n.format("gui.yes_steve_model.model_manage.custom"));
        customButton.setSelect(isCustomModels);
        this.buttonList.add(customButton);

        FlatColorButton authButton = new FlatColorButton(
            ID_TAB_AUTH,
            this.x + 90,
            this.y + 5,
            80,
            18,
            I18n.format("gui.yes_steve_model.model_manage.auth"));
        authButton.setSelect(!isCustomModels);
        this.buttonList.add(authButton);

        this.buttonList.add(
            new FlatColorButton(
                ID_UPLOAD,
                this.x + 175,
                this.y + 5,
                80,
                18,
                I18n.format("gui.yes_steve_model.model_manage.upload")));
    }

    private void addPageButtons() {
        this.buttonList.add(new FlatColorButton(ID_PREV, this.x + 5, this.y + 28, 80, 18, "<"));
        this.buttonList.add(new FlatColorButton(ID_NEXT, this.x + 175, this.y + 28, 80, 18, ">"));
    }

    private void addModelInfoButtons() {
        int modelsY = this.y + 51;
        int first = page * MAX_COUNT;
        for (int i = first; i < first + MAX_COUNT; i++) {
            if (i >= this.getModels()
                .size()) {
                return;
            }
            RequestServerModelInfo.Info info = this.getModels()
                .get(i);
            ModelInfoButton row = new ModelInfoButton(ID_ROW_BASE + i, this.x + 5, modelsY, 12, info);
            if (this.index == i) {
                row.setSelect(true);
            }
            this.buttonList.add(row);
            modelsY += 12;
        }
    }

    private void addActionButtons() {
        this.buttonList.add(
            new FlatColorButton(
                ID_DELETE,
                this.x + 5,
                this.y + PANEL_HEIGHT - 23,
                80,
                18,
                I18n.format("gui.yes_steve_model.model_manage.delete")));
        this.buttonList.add(
            new FlatColorButton(
                ID_MOVE,
                this.x + 90,
                this.y + PANEL_HEIGHT - 23,
                80,
                18,
                I18n.format("gui.yes_steve_model.model_manage.move")));
        this.buttonList.add(
            new FlatColorButton(
                ID_RENAME,
                this.x + 175,
                this.y + PANEL_HEIGHT - 23,
                80,
                18,
                I18n.format("gui.yes_steve_model.model_manage.rename")));
    }

    private void addExtraButtons() {
        this.buttonList.add(
            new FlatColorButton(
                ID_CONFIRM,
                this.x + 270,
                this.y + PANEL_HEIGHT - 23,
                70,
                18,
                I18n.format("gui.yes_steve_model.model_manage.confirm")));
        this.buttonList.add(
            new FlatColorButton(
                ID_CANCEL,
                this.x + 345,
                this.y + PANEL_HEIGHT - 23,
                70,
                18,
                I18n.format("gui.yes_steve_model.model_manage.cancel")));
        if (this.action == Action.RENAME) {
            this.renameField = new GuiTextField(
                this.fontRendererObj,
                this.x + 270,
                this.y + 51,
                145,
                14);
            this.renameField.setMaxStringLength(24);
            this.renameField.setTextColor(COLOR_TEXT);
            RequestServerModelInfo.Info info = this.selectedModel();
            if (info != null) {
                String current = info.getFileName();
                if (info.getType() != Type.FOLDER && current.toLowerCase(Locale.ROOT)
                    .endsWith(".ysm")) {
                    current = current.substring(0, current.length() - 4);
                }
                this.renameField.setText(current);
                this.renameField.setCursorPositionEnd();
            }
            this.renameField.setFocused(true);
        }
        if (this.action == Action.UPLOAD) {
            this.pathField = new GuiTextField(this.fontRendererObj, this.x + 270, this.y + 61, 145, 14);
            this.pathField.setMaxStringLength(1024);
            this.pathField.setTextColor(COLOR_TEXT);
            String initial = this.pendingPath != null ? this.pendingPath : UploadManager.FILE_PATH;
            if (initial != null) {
                this.pathField.setText(initial);
                this.pathField.setCursorPositionEnd();
            }
            this.pathField.setFocused(true);
            this.buttonList.add(
                new FlatColorButton(
                    ID_BROWSE,
                    this.x + 270,
                    this.y + 79,
                    80,
                    18,
                    I18n.format("gui.yes_steve_model.model_manage.browse")));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // interaction
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_TAB_CUSTOM) {
            if (!isCustomModels) {
                isCustomModels = true;
                this.index = -1;
                this.action = Action.EMPTY;
                page = 0;
                this.initGui();
            }
            return;
        }
        if (button.id == ID_TAB_AUTH) {
            if (isCustomModels) {
                isCustomModels = false;
                this.index = -1;
                this.action = Action.EMPTY;
                page = 0;
                this.initGui();
            }
            return;
        }
        if (button.id == ID_UPLOAD) {
            if (UploadManager.STATUE == UploadManager.Statue.PROCESSING) {
                this.uploadError = I18n.format("commands.yes_steve_model.upload.client.busy");
                return;
            }
            this.action = Action.UPLOAD;
            this.index = -1;
            this.uploadError = null;
            this.initGui();
            this.openFileChooser();
            return;
        }
        if (button.id == ID_PREV) {
            if (page > 0) {
                page--;
                this.initGui();
            }
            return;
        }
        if (button.id == ID_NEXT) {
            if ((page + 1) * MAX_COUNT < this.modelsCount) {
                page++;
                this.initGui();
            }
            return;
        }
        if (button.id >= ID_ROW_BASE) {
            this.index = button.id - ID_ROW_BASE;
            this.action = Action.EMPTY;
            this.uploadError = null;
            this.initGui();
            return;
        }
        switch (button.id) {
            case ID_DELETE:
                this.action = Action.DELETE;
                this.initGui();
                break;
            case ID_MOVE:
                this.action = Action.MOVE;
                this.initGui();
                break;
            case ID_RENAME:
                this.action = Action.RENAME;
                this.initGui();
                break;
            case ID_CONFIRM:
                this.confirmAction();
                break;
            case ID_CANCEL:
                this.action = Action.EMPTY;
                this.uploadError = null;
                this.initGui();
                break;
            case ID_BROWSE:
                this.openFileChooser();
                break;
            default:
                break;
        }
    }

    private void confirmAction() {
        RequestServerModelInfo.Info info = this.selectedModel();
        boolean refresh = false;
        switch (this.action) {
            case DELETE:
                if (info != null) {
                    NetworkHandler.CHANNEL.sendToServer(
                        new HandleFile(this.target(), info.getFileName(), HandleFile.Action.DELETE, ""));
                    refresh = true;
                }
                break;
            case MOVE:
                if (info != null) {
                    NetworkHandler.CHANNEL.sendToServer(
                        new HandleFile(this.target(), info.getFileName(), HandleFile.Action.MOVE, ""));
                    refresh = true;
                }
                break;
            case RENAME: {
                if (info == null || this.renameField == null) {
                    break;
                }
                String value = this.renameField.getText()
                    .trim();
                if (value.isEmpty()) {
                    break;
                }
                String newName = value;
                if (info.getType() != Type.FOLDER && !newName.toLowerCase(Locale.ROOT)
                    .endsWith(".ysm")) {
                    newName = newName + ".ysm";
                }
                if (!newName.equals(info.getFileName())) {
                    NetworkHandler.CHANNEL.sendToServer(
                        new HandleFile(this.target(), info.getFileName(), HandleFile.Action.RENAME, newName));
                    refresh = true;
                }
                break;
            }
            case UPLOAD: {
                String path = this.pathField != null ? this.pathField.getText()
                    .trim() : "";
                String error = this.validateUploadPath(path);
                if (error != null) {
                    this.uploadError = error;
                    return;
                }
                if (!UploadManager.startUpload(this.target(), path)) {
                    this.uploadError = I18n.format("commands.yes_steve_model.upload.client.busy");
                    return;
                }
                UploadManager.FILE_PATH = path;
                this.action = Action.EMPTY;
                this.uploadError = null;
                this.initGui();
                // 上传是异步的：不在这里刷新列表，等服务端 CompleteFeedback 后 refreshingAfterUpload()。
                return;
            }
            default:
                break;
        }
        this.action = Action.EMPTY;
        if (refresh) {
            NetworkHandler.CHANNEL.sendToServer(new RefreshModelManage());
        }
        this.initGui();
    }

    /** 上传前的廉价检查（后缀/存在/大小）；完整模型解析在后台线程由 {@link UploadManager} 做。 */
    private String validateUploadPath(String path) {
        if (path.isEmpty()) {
            return I18n.format("gui.yes_steve_model.model_manage.error.path_empty");
        }
        File file = new File(path);
        if (!file.isFile()) {
            return I18n.format("gui.yes_steve_model.model_manage.error.read_failed");
        }
        if (!file.getName()
            .toLowerCase(Locale.ROOT)
            .endsWith(".ysm")) {
            return I18n.format("gui.yes_steve_model.model_manage.error.format_incorrect");
        }
        if (file.length() > ModelFileValidator.maxUploadBytes()) {
            return I18n.format(
                "gui.yes_steve_model.model_manage.error.too_large",
                ModelFileValidator.maxUploadMb());
        }
        return null;
    }

    /**
     * 服务端上传完成（{@code CompleteFeedback}）后由 {@link UploadManager#finishUpload()} 在客户端线程调用：
     * 清错误、重新拉列表。服务端回的 {@link RequestServerModelInfo} 会重建本界面。
     */
    public void refreshingAfterUpload() {
        this.uploadError = null;
        NetworkHandler.CHANNEL.sendToServer(new RefreshModelManage());
    }

    private void openFileChooser() {
        Thread chooser = new Thread(() -> {
            String chosen = null;
            try {
                java.awt.FileDialog dialog = new java.awt.FileDialog(
                    (java.awt.Frame) null,
                    I18n.format("gui.yes_steve_model.model_manage.open_file"),
                    java.awt.FileDialog.LOAD);
                dialog.setFilenameFilter(
                    (dir, name) -> name != null && name.toLowerCase(Locale.ROOT)
                        .endsWith(".ysm"));
                dialog.setVisible(true);
                String fileName = dialog.getFile();
                String directory = dialog.getDirectory();
                dialog.dispose();
                if (fileName != null && directory != null) {
                    chosen = new File(directory, fileName).getAbsolutePath();
                }
            } catch (Throwable t) {
                ysmu.LOG.warn("[YSMU-MANAGE] The native model file chooser is unavailable", t);
            }
            final String selected = chosen;
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null) {
                return;
            }
            minecraft.func_152344_a(() -> {
                if (selected != null) {
                    this.pendingPath = selected;
                    UploadManager.FILE_PATH = selected;
                    this.initGui();
                } else if (this.uploadError == null) {
                    this.uploadError = I18n.format("gui.yes_steve_model.model_manage.error.path_empty");
                }
            });
        }, "YSMU-Model-FileChooser");
        chooser.setDaemon(true);
        chooser.start();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // input hooks
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode != 1) {
            if (this.renameField != null && this.renameField.isFocused()
                && this.renameField.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
            if (this.pathField != null && this.pathField.isFocused()
                && this.pathField.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.renameField != null) {
            this.renameField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (this.pathField != null) {
            this.pathField.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.renameField != null) {
            this.renameField.updateCursorCounter();
        }
        if (this.pathField != null) {
            this.pathField.updateCursorCounter();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // rendering
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawRect(this.x, this.y, this.x + 260, this.y + PANEL_HEIGHT, COLOR_PANEL);
        this.drawRect(this.x + 265, this.y, this.x + PANEL_WIDTH, this.y + PANEL_HEIGHT, COLOR_PANEL);
        this.drawRect(this.x + 270, this.y + 5, this.x + 415, this.y + 23, COLOR_HEADER);
        this.drawCenteredString(
            this.fontRendererObj,
            I18n.format("gui.yes_steve_model.model_manage.action_info"),
            this.x + 342,
            this.y + 11,
            0xFFFFFF);

        int pages = this.modelsCount == 0 ? 1 : (this.modelsCount - 1) / MAX_COUNT + 1;
        this.drawString(
            this.fontRendererObj,
            String.format(Locale.ROOT, "%d/%d", page + 1, pages),
            this.x + 120,
            this.y + 33,
            0xFFFFFF);
        if (this.modelsCount == 0) {
            this.drawString(
                this.fontRendererObj,
                I18n.format("gui.yes_steve_model.model_manage.empty"),
                this.x + 20,
                this.y + 80,
                COLOR_DIM);
        }

        if (this.action == Action.UPLOAD) {
            String folder = isCustomModels ? I18n.format("gui.yes_steve_model.model_manage.custom")
                : I18n.format("gui.yes_steve_model.model_manage.auth");
            String actionName = I18n.format(
                "gui.yes_steve_model.model_manage." + this.action.name()
                    .toLowerCase(Locale.ROOT));
            String statue = I18n.format(
                "gui.yes_steve_model.model_manage.upload.statue."
                    + UploadManager.STATUE.name()
                        .toLowerCase(Locale.ROOT));
            this.drawString(
                this.fontRendererObj,
                I18n.format("gui.yes_steve_model.model_manage.selected", folder),
                this.x + 272,
                this.y + 29,
                0xFFFFFF);
            this.drawString(
                this.fontRendererObj,
                I18n.format("gui.yes_steve_model.model_manage.action", actionName),
                this.x + 272,
                this.y + 39,
                0xFFFFFF);
            this.drawString(
                this.fontRendererObj,
                I18n.format("gui.yes_steve_model.model_manage.upload.statue", statue),
                this.x + 272,
                this.y + 49,
                0xFFFFFF);
            if (this.uploadError != null) {
                this.drawWordWrap(this.uploadError, this.x + 270, this.y + 100, 145, COLOR_ERROR);
            }
        } else if (this.index >= 0 && this.index < this.getModels()
            .size()) {
                RequestServerModelInfo.Info info = this.getModels()
                    .get(this.index);
                this.drawString(
                    this.fontRendererObj,
                    I18n.format("gui.yes_steve_model.model_manage.selected", info.getFileName()),
                    this.x + 272,
                    this.y + 29,
                    0xFFFFFF);
                if (this.action != Action.EMPTY) {
                    String actionName = I18n.format(
                        "gui.yes_steve_model.model_manage." + this.action.name()
                            .toLowerCase(Locale.ROOT));
                    this.drawString(
                        this.fontRendererObj,
                        I18n.format("gui.yes_steve_model.model_manage.action", actionName),
                        this.x + 272,
                        this.y + 39,
                        0xFFFFFF);
                }
            }

        // 按钮（含列表行）由 super 绘制；文本输入框绘制在按钮之上，避免被行盖住。
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (this.action == Action.RENAME && this.renameField != null) {
            this.renameField.drawTextBox();
        }
        if (this.action == Action.UPLOAD && this.pathField != null) {
            this.pathField.drawTextBox();
        }
        // 原生文件框的回调可能发生在 initGui 之后：在绘制阶段补填路径并重建控件。
        if (this.pendingPath != null && this.action == Action.UPLOAD && this.pathField != null
            && !this.pendingPath.equals(this.pathField.getText())) {
            this.pathField.setText(this.pendingPath);
            this.pathField.setCursorPositionEnd();
        }
    }

    private void drawWordWrap(String text, int x, int y, int width, int color) {
        for (String line : this.fontRendererObj.listFormattedStringToWidth(text, width)) {
            this.drawString(this.fontRendererObj, line, x, y, color);
            y += this.fontRendererObj.FONT_HEIGHT;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // model data helpers
    // ---------------------------------------------------------------------------------------------------------------

    private ModelUploadTarget target() {
        return isCustomModels ? ModelUploadTarget.CUSTOM : ModelUploadTarget.AUTH;
    }

    private RequestServerModelInfo.Info selectedModel() {
        if (this.index < 0 || this.index >= this.getModels()
            .size()) {
            return null;
        }
        return this.getModels()
            .get(this.index);
    }

    private List<RequestServerModelInfo.Info> getModels() {
        return isCustomModels ? this.customModels : this.authModels;
    }

    /** 管理界面上的动作（Lgeacy 的 {@code Action} 枚举）。 */
    public enum Action {
        DELETE,
        MOVE,
        RENAME,
        UPLOAD,
        EMPTY
    }
}
