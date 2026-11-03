package com.fox.ysmu.client.gui;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
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

import com.fox.ysmu.Config;
import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.client.gui.button.ModelInfoButton;
import com.fox.ysmu.client.upload.UploadManager;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.format.ZipModelFormat;
import com.fox.ysmu.model.upload.ModelFileValidator;
import com.fox.ysmu.model.upload.ModelUploadInstaller;
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
 * <p>与 Lgeacy 的差异：选文件用 AWT {@link FileDialog}（1.7.10 没有 TinyFileDialogs）并保留可手填
 * 路径；上传走本仓的分片 + 完整校验通道而不是单包 {@code UploadFile}；管理动作失败由服务端回聊天提示；
 * 重命名输入框预填当前名字。危险操作都在服务端 {@code ServerModelManage} 校验，本界面不暂停单人游戏。</p>
 *
 * <p>原生文件框（本轮修复）：一次点击只允许一个窗口，并且必须由 AWT 事件线程创建/显示，结果经
 * {@code Minecraft.func_152344_a} 交回客户端线程——见 {@link #openFileChooser()}。</p>
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
    /**
     * 原生文件框选中、还没填进输入框的路径（回调在 AWT/客户端线程交接，存下来由 {@link #initGui()} 一次性消费）。
     */
    private String pendingPath;
    /** {@link #renameField} 当前预填的是哪个条目的名字；换条目才重新预填，重排界面时保留正在输入的内容。 */
    private String renameFor;

    /**
     * 进程级"原生文件框是否已经打开"标记。
     * <p>
     * 这是"点一次选出两个窗口"的修复点：1.7.10 的 {@code GuiScreen.mouseClicked} 对每个鼠标按下事件都会调用一次
     * {@code actionPerformed}（{@code GuiScreen.java:237-258}，没有双击去抖），而本界面有<b>两处</b>会打开文件框——
     * 上传页签（与 Lgeacy 一样，点页签即弹框）和"浏览"按钮。旧实现每次调用都新起一个守护线程、各自
     * {@code new FileDialog(...)} + {@code setVisible(true)}，彼此不知道对方存在，所以第二次点击（双击的第二次
     * 按下，或在 AWT 首次初始化、原生窗口还没出现时再点一次）会叠出第二个模态框。开关是 <b>static</b> 的，因为每次
     * 列表刷新都会重建整个界面实例，实例字段挡不住跨实例的第二个框。
     * </p>
     */
    private static final Object CHOOSER_LOCK = new Object();
    private static boolean chooserOpen = false;
    private static FileDialog chooserDialog = null;

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
        if (this.action == Action.EMPTY) {
            // 面板收起时把输入框与暂存路径一并放掉：重新打开动作是全新的，不接着上一次的残留。
            this.renameField = null;
            this.pathField = null;
            this.pendingPath = null;
            this.renameFor = null;
        }
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
        // 服务端可能永远不回 CompleteFeedback（分片在半路丢失时服务端只是静默清掉累加器）：界面不能因此
        // 永远停在"上传中"，见 UploadManager#releaseStalledUpload。
        UploadManager.releaseStalledUpload();
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
            RequestServerModelInfo.Info info = this.selectedModel();
            String fileName = info == null ? null : info.getFileName();
            String text;
            int cursor;
            if (this.renameField != null && info != null && fileName.equals(this.renameFor)) {
                // 重排界面（窗口缩放、面板重建）时保留正在输入的内容与光标：Lgeacy 的 resize 覆盖
                // （ModelManageScreen.java:302-311）做的正是这件事。
                text = this.renameField.getText();
                cursor = this.renameField.getCursorPosition();
            } else {
                text = stemOf(info);
                cursor = text.length();
            }
            this.renameField = new GuiTextField(this.fontRendererObj, this.x + 270, this.y + 51, 145, 14);
            this.renameField.setMaxStringLength(24);
            this.renameField.setTextColor(COLOR_TEXT);
            this.renameField.setText(text);
            this.renameField.setCursorPosition(Math.min(cursor, text.length()));
            this.renameField.setFocused(true);
            this.renameFor = fileName;
        }
        if (this.action == Action.UPLOAD) {
            // 原生文件框刚选中的路径优先（pendingPath 是一次性交接，填完即清），其次保留输入框里已有的内容，
            // 最后才是上一次上传记下的路径。旧实现在 drawScreen 里逐帧把输入框改回 pendingPath，导致"选过文件
            // 就再也手打不进路径"——这里改成只在重建输入框时填一次。
            String text;
            int cursor;
            if (this.pendingPath != null) {
                text = this.pendingPath;
                cursor = text.length();
                this.pendingPath = null;
            } else if (this.pathField != null) {
                text = this.pathField.getText();
                cursor = this.pathField.getCursorPosition();
            } else if (UploadManager.FILE_PATH != null) {
                text = UploadManager.FILE_PATH;
                cursor = text.length();
            } else {
                text = "";
                cursor = 0;
            }
            this.pathField = new GuiTextField(this.fontRendererObj, this.x + 270, this.y + 61, 145, 14);
            this.pathField.setMaxStringLength(1024);
            this.pathField.setTextColor(COLOR_TEXT);
            this.pathField.setText(text);
            this.pathField.setCursorPosition(Math.min(cursor, text.length()));
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

    /**
     * 重命名输入框的预填内容：条目名去掉它自己的模型后缀（文件夹没有后缀）。Lgeacy 用
     * {@code fileName.substring(0, length - 4)} 硬切 4 个字符（{@code ModelManageScreen.java:97-100}），只对
     * {@code .ysm}/{@code .zip} 这类 4 字符后缀成立；这里按最后一个点取，对这两种后缀与它逐字相同，对将来
     * 再加的模型文件形态也不会退化成"把整个名字当后缀"。
     */
    private static String stemOf(RequestServerModelInfo.Info info) {
        String name = info == null || info.getFileName() == null ? "" : info.getFileName();
        String suffix = modelSuffix(info);
        return suffix.isEmpty() ? name : name.substring(0, name.length() - suffix.length());
    }

    /** 一个条目自己带的模型后缀（含点）；文件夹与无后缀条目返回空串。 */
    private static String modelSuffix(RequestServerModelInfo.Info info) {
        if (info == null || info.getType() == Type.FOLDER) {
            return "";
        }
        String name = info.getFileName();
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot);
    }

    /**
     * 把建议名换成"本条目自己的后缀"：先去掉建议名里可能已有的模型后缀（{@code .ysm}/{@code .zip}），再补上
     * {@code suffix}。与 {@code ServerModelManage.withModelSuffix} 同一形状，两端算出同一个名字。
     */
    private static String withOwnSuffix(String suggestion, String suffix) {
        String stem = suggestion;
        String lower = stem.toLowerCase(Locale.ROOT);
        if (lower.endsWith(ModelUploadInstaller.MODEL_SUFFIX)) {
            stem = stem.substring(0, stem.length() - ModelUploadInstaller.MODEL_SUFFIX.length());
        } else if (lower.endsWith(ZipModelFormat.SUFFIX)) {
            stem = stem.substring(0, stem.length() - ZipModelFormat.SUFFIX.length());
        }
        return stem + suffix;
    }

    /** 上传接受的文件后缀：{@code .ysm} 与 ZIP 模型包 {@code .zip}（两者都是本仓扫描器会加载的形态）。 */
    private static boolean isUploadableFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(ModelUploadInstaller.MODEL_SUFFIX) || lower.endsWith(ZipModelFormat.SUFFIX);
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
                // 旧实现只写 uploadError 而不切到上传面板，而 drawScreen 只在 action==UPLOAD 时画它——"另一个上传
                // 还在跑"这句话因此永远看不到。切到上传面板，提示与"上传中"状态一起显示。
                this.action = Action.UPLOAD;
                this.uploadError = I18n.format("commands.yes_steve_model.upload.client.busy");
                this.initGui();
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
                this.clearSelection();
                this.initGui();
            }
            return;
        }
        if (button.id == ID_NEXT) {
            if ((page + 1) * MAX_COUNT < this.modelsCount) {
                page++;
                this.clearSelection();
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
                // 保留条目自己的后缀（.ysm 还是 .ysm、.zip 还是 .zip）。旧实现无条件是 .ysm：重命名一个 zip
                // 条目时发出去的名字会被服务端按源后缀改回原名，界面刷新后名字没变——看起来像"重命名没反应"。
                String newName = withOwnSuffix(value, modelSuffix(info));
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
                // 面板留在上传页：上传是异步的，收起面板就无法显示"上传中"状态（旧实现把 action 清成 EMPTY，
                // 用户在 CompleteFeedback 到达前看不到任何进行中的迹象）。完成后 refreshingAfterUpload 收面板。
                this.uploadError = null;
                this.initGui();
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

    /**
     * 上传前的廉价检查（后缀/存在/大小）；完整模型解析在后台线程由 {@link UploadManager} 做。
     */
    private String validateUploadPath(String path) {
        if (path.isEmpty()) {
            return I18n.format("gui.yes_steve_model.model_manage.error.path_empty");
        }
        File file = new File(path);
        if (!file.isFile()) {
            return I18n.format("gui.yes_steve_model.model_manage.error.read_failed");
        }
        // 与 ModelFileValidator / ModelUploadInstaller 的接受面一致：.ysm 与 ZIP 模型包都能装。
        if (!isUploadableFileName(file.getName())) {
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
     * 清错误、收起上传面板并重新拉列表。服务端回的 {@link RequestServerModelInfo} 会重建本界面。
     */
    public void refreshingAfterUpload() {
        this.uploadError = null;
        this.action = Action.EMPTY;
        NetworkHandler.CHANNEL.sendToServer(new RefreshModelManage());
        this.initGui();
    }

    /**
     * 分页会换一页条目：把选中项与动作一并清掉，否则删除/移动/重命名仍指向上一页那条看不见的条目
     * （动作按钮由 {@code index >= 0} 决定是否出现，而 {@code index} 是列表的绝对下标，与页码无关）。
     * 输入框与暂存路径由 {@link #initGui()} 在 {@code action == EMPTY} 时一并释放。
     */
    private void clearSelection() {
        this.index = -1;
        this.action = Action.EMPTY;
        this.uploadError = null;
    }

    /**
     * 打开系统原生文件框——<b>一次点击只允许一个窗口</b>。
     *
     * <p>上游 Lgeacy 用 {@code TinyFileDialogs.tinyfd_openFileDialog}，1.7.10 没有该依赖，这里改用 AWT
     * {@link FileDialog}（phase55 已记录的移植特有差异）。旧实现每次调用都新起一个守护线程、各自
     * {@code new FileDialog(...)} 并 {@code setVisible(true)}，既没有"已经开了一个"的判定，也不记录当前窗口，
     * 于是：</p>
     * <ul>
     *   <li>1.7.10 的 {@code GuiScreen.mouseClicked} 对<b>每个</b>鼠标按下事件调用一次
     *       {@code actionPerformed}（{@code GuiScreen.java:237-258}，没有双击去抖），所以双击"浏览"就是两次
     *       弹框请求；</li>
     *   <li>AWT 第一次使用要初始化 Toolkit、原生窗口出现有延迟，而 LWJGL2 的游戏窗口在这期间照常吃输入，
     *       用户"点了没反应再点一次"又是第二次请求；</li>
     *   <li>两个模态框各自等用户关闭，谁也不会关掉对方——屏幕上就同时有两个文件框。</li>
     * </ul>
     *
     * <p>现在用进程级开关（{@link #CHOOSER_LOCK} + {@link #chooserOpen}）把它收敛成"最多一个"：已有窗口时
     * 第二次请求只把那个窗口提到前面，不再新建。文件框在 AWT 事件线程创建与显示（{@link EventQueue}），
     * {@code setVisible(true)} 的模态阻塞发生在事件线程的嵌套事件循环里，客户端线程不被阻塞；结果用
     * {@code Minecraft.func_152344_a} 交回客户端线程（{@link #applyChosenPath(String)}）。</p>
     */
    private void openFileChooser() {
        // 标题在客户端线程取好：I18n 不在 AWT 线程上调用。
        final String title = I18n.format("gui.yes_steve_model.model_manage.open_file");
        synchronized (CHOOSER_LOCK) {
            if (chooserOpen) {
                final FileDialog existing = chooserDialog;
                // 游戏内验证这条修复的抓手：每次"被挡下的第二次弹框请求"都留一行，配合下方"新建文件框"那行
                // 就能数出实际开了几个原生窗口（打开 Config 的两个 DEBUG 开关后生效）。
                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                    ysmu.LOG.info(
                        "[YSMU-MANAGE] A native model file chooser is already open; bringing it to the front "
                            + "instead of opening a second one (window created: {})",
                        existing != null);
                }
                if (existing != null) {
                    try {
                        EventQueue.invokeLater(() -> {
                            existing.toFront();
                            existing.requestFocus();
                        });
                    } catch (RuntimeException e) {
                        ysmu.LOG.warn("[YSMU-MANAGE] Could not bring the open model file chooser to the front", e);
                    }
                }
                return;
            }
            chooserOpen = true;
        }
        try {
            EventQueue.invokeLater(() -> showFileChooser(title));
        } catch (Throwable unavailable) {
            // 事件队列都建不起来（AWT 不可用）：复位开关并记录，界面保持可用（路径框仍可手填）。
            synchronized (CHOOSER_LOCK) {
                chooserOpen = false;
                chooserDialog = null;
            }
            ysmu.LOG.warn("[YSMU-MANAGE] The native model file chooser is unavailable", unavailable);
        }
    }

    /** AWT 事件线程上显示文件框并把结果交回客户端线程；无论成功、取消还是异常都只关一次、只复位一次。 */
    private void showFileChooser(String title) {
        String chosen = null;
        FileDialog dialog = null;
        try {
            dialog = new FileDialog((Frame) null, title, FileDialog.LOAD);
            dialog.setFilenameFilter((dir, name) -> isUploadableFileName(name));
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                ysmu.LOG.info("[YSMU-MANAGE] Creating one native model file chooser on the AWT event thread");
            }
            synchronized (CHOOSER_LOCK) {
                chooserDialog = dialog;
            }
            // 模态：在事件线程上进入嵌套事件循环，直到用户选中或取消。
            dialog.setVisible(true);
            String fileName = dialog.getFile();
            String directory = dialog.getDirectory();
            if (fileName != null && !fileName.isEmpty() && directory != null) {
                chosen = new File(directory, fileName).getAbsolutePath();
            }
        } catch (Throwable unavailable) {
            ysmu.LOG.warn("[YSMU-MANAGE] The native model file chooser is unavailable", unavailable);
        } finally {
            if (dialog != null) {
                try {
                    dialog.dispose();
                } catch (RuntimeException e) {
                    ysmu.LOG.warn("[YSMU-MANAGE] Could not dispose the model file chooser", e);
                }
            }
            synchronized (CHOOSER_LOCK) {
                chooserOpen = false;
                chooserDialog = null;
            }
        }
        final String selected = chosen;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        minecraft.func_152344_a(() -> applyChosenPath(selected));
    }

    /**
     * 客户端线程：把文件框的答案填进路径框。
     *
     * <p>取消时什么都不做——上游 {@code getUploadFilePath} 同样是静默返回
     * （{@code LgeacyYSM ModelManageScreen.java:222-224}），旧实现把取消也写成"请先选择一个文件"的错误，
     * 用户一按取消就看到一条红字。</p>
     */
    private void applyChosenPath(String selected) {
        if (selected == null) {
            return;
        }
        // 路径先落进共享状态：即使界面已经被列表刷新换掉、甚至已经关掉，用户的选择也不丢
        // （下一次打开上传页时 addExtraButtons 会从 UploadManager.FILE_PATH 取）。
        UploadManager.FILE_PATH = selected;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        // 每次 RequestServerModelInfo 都会重建界面实例（openFromPacket），所以这里对"当前那个实例"生效，
        // 而不是死守着当初发起请求的 this。
        if (!(minecraft.currentScreen instanceof ModelManageScreen target)) {
            return;
        }
        target.pendingPath = selected;
        target.action = Action.UPLOAD;
        target.uploadError = null;
        target.initGui();
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
        // 上传状态由服务端的 CompleteFeedback 解锁；服务端迟迟不回（分片丢失后它只静默清掉累加器）时，
        // 这里按"长时间没有进展"自行解锁，界面不会永远停在"上传中"。
        UploadManager.releaseStalledUpload();
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
        // 旧实现在这里逐帧把输入框改回 pendingPath：选过一次文件之后，手打的任何修改会在下一帧被抹掉，
        // 而"可手填路径"正是本仓相对 Lgeacy 保留的功能。现在路径只在重建输入框时填一次（见 addExtraButtons）。
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
