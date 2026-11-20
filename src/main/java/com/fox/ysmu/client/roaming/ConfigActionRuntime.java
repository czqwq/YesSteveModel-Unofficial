package com.fox.ysmu.client.roaming;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.audio.MolangSoundContext;
import com.fox.ysmu.client.gui.ModelConfigRegistry;
import com.fox.ysmu.client.input.ServerSwitchState;
import com.fox.ysmu.data.ConfigAction;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SConfigAction;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.molang.RemoteAnimationVariables;

/**
 * 「模型设置」表单的类型化读写运行时：在受试玩家<b>真实的 {@code (entity, model)} MoLang 作用域</b>里同步执行本地模型载荷
 * 里同名 form 的程序。
 *
 * <h2>上游机制（本类移植的权威形状）</h2>
 * <ul>
 * <li><b>读</b>：GUI 直接对本地玩家的 animatable 同步执行 form 的 {@code value} 读程序
 * （{@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java:230-258} →
 * {@code client/entity/AnimatableEntity.java:759-765} → {@code expression/aot/EntityExpressionRuntime.java:1147-1157}），
 * 所以控件显示的就是角色运行时里的值；</li>
 * <li><b>写</b>：点击只提交 {@code ConfigAction{buttonId, formIndex, 类型化值}}，<b>发送端不本地执行</b>
 * （{@code network/forge/ClientProtocolGateway.java:85-129}）；服务端按自己那份模型声明校验后广播
 * {@code ConfigActionEvent} 给跟踪者<b>和发送者自己</b>（{@code network/forge/ControlHandler.java:98-115}），每端在
 * subject 玩家的真实运行时里执行本地程序（{@code network/forge/ClientControlHandler.java:55-69,112-130} →
 * {@code EntityExpressionRuntime.java:1159-1178}）。</li>
 * </ul>
 * <p>
 * 本仓引擎的作用域只在渲染 pass 内打开（{@code MolangPhysicsRuntime.begin/end}），所以这里的读与动作都先用
 * {@link MolangPhysicsRuntime#pushScope} 打开同一个 {@code (player UUID, main model id)} 作用域再求值：写落在渲染同一条
 * 运行时上，普通 {@code v.*} 因此世界角色下一帧就读到、重开面板也从同一作用域读回（上游"同一运行时"的等价形态）。
 * 程序从本地 {@link RawYsmModel.ConfigForm} 现场拼装并按源码缓存（{@link RoamingProgram#compileCached}）；wire 上
 * 从不携带 MoLang 源码。1.7.10 引擎没有上游的 AOT program inventory
 * （{@code expression/inventory/ModelProgramInventory.java:437-459}），这是移植特有的执行层差别。
 *
 * <h2>本类在 port 里的两处特有边界（均已登记理由）</h2>
 * <ol>
 * <li><b>无远端通道时本地执行</b>（{@code ServerSwitchState.isRemoteChannelPresent() == false}）：移植支持
 * {@code LocalModelLoader}/{@code /ysmclient} 的"仅客户端模型"（上游没有这个场景，见 AGENTS.md），没有服务端可以转发
 * 这次动作，所以只能在本机世界作用域直接执行——不广播、不持久化，语义就是上游 {@code ClientProtocolGateway.send()} 在
 * 远端通道缺失时"丢弃"的一种移植特有扩展。</li>
 * <li><b>客户端独有模型（有远端通道也本地执行）</b>：{@link com.fox.ysmu.client.ClientModelManager#isClientOnlyModel}
 * 为真时，服务端目录里没有这个模型，`C2SConfigAction` 只会被"没有声明快照"的校验静默丢弃（契约 §6.2），所以改为在本机
 * 世界作用域执行——同样不广播、不持久化。判定本身是移植特有的（上游根本没有客户端独有模型）：它把"本机目录里有、且
 * 不是内置模型"的模型当成客户端独有；一个同时存在于本机目录与服务端目录的同名模型会被这条启发式判为客户端独有，
 * 于是设置只在本地生效（服务端/其他客户端收不到），这是记录在案的残差，而不是另造一张来源表。</li>
 * </ol>
 */
public final class ConfigActionRuntime {

    private ConfigActionRuntime() {}

    /**
     * 该模型是否"本地玩家当前穿戴、且本地有声明的模型"：设置面板/轮盘的打开条件，也是读写的作用域前提。
     * <p>
     * 上游只允许配置当前穿戴模型：GUI 拿的就是本地玩家自己的 {@code PlayerAnimatableCapability}
     * （{@code client/input/AnimationRouletteKey.java:41-46}），服务端也只接受 sender 当前模型的动作
     * （{@code network/forge/ControlHandler.java:98-108}）。移植的设置屏是独立 Screen，因此这个判定同时承担"面板是否
     * 还有意义"（{@code ModelConfigScreen} 每次绘制复核）与"动作目标是否成立"两个职责。
     */
    public static boolean isLocalModel(@Nullable ResourceLocation modelId) {
        if (modelId == null) {
            return false;
        }
        EntityPlayer local = localPlayer();
        if (local == null) {
            return false;
        }
        ExtendedModelInfo info = ExtendedModelInfo.get(local);
        if (info == null || !modelId.equals(info.getModelId())) {
            return false;
        }
        return ModelConfigRegistry.hasSettings(modelId);
    }

    /**
     * 在本地玩家真实作用域里同步执行一个 form 的读程序，返回数值；运行时不可用（玩家不在世界/模型不是穿戴中/声明或程序
     * 不存在/解析失败）时返回 {@code NaN}。
     * <p>
     * 语义与调用方约定（见 {@code ModelConfigScreen#readCurrent}）：checkbox 用 {@code >0} 表示选中、range 直接用、
     * radio 用四舍五入取整。空 {@code value} 的 action-only radio 读成常量 {@code "0"}，与上游
     * {@code format/parser/ModelInfoBuilder.java:154-166} 的 {@code actionOnlyRadio} 分支一致。
     */
    public static double read(@Nullable ResourceLocation modelId, @Nullable String buttonId, int formIndex) {
        if (modelId == null || buttonId == null) {
            return Double.NaN;
        }
        EntityPlayer local = localPlayer();
        if (local == null) {
            return Double.NaN;
        }
        ExtendedModelInfo info = ExtendedModelInfo.get(local);
        if (info == null || !modelId.equals(info.getModelId())) {
            return Double.NaN;
        }
        RawYsmModel.ConfigForm form = formFor(modelId, buttonId, formIndex);
        if (form == null) {
            return Double.NaN;
        }
        String source = readSource(form);
        if (source == null) {
            return Double.NaN;
        }
        RoamingProgram.Compiled program = RoamingProgram.compileCached(source);
        if (program == null) {
            return Double.NaN;
        }
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        try (MolangPhysicsRuntime.Scope scope = MolangPhysicsRuntime.pushScope(local, mainId);
            MolangSoundContext sound = soundContext(local, local, mainId)) {
            return program.evaluate();
        } catch (Exception failure) {
            ysmu.LOG.warn("Failed to evaluate model form read program '{}': {}", source, failure.getMessage());
            return Double.NaN;
        }
    }

    /** GUI checkbox 动作：校验本地声明后，有远端通道则发 {@link C2SConfigAction}，否则在本机世界作用域执行。 */
    public static void submit(@Nullable ResourceLocation modelId, @Nullable String buttonId, int formIndex,
        boolean value) {
        dispatch(modelId, ConfigAction.boolAction(buttonId, formIndex, value));
    }

    /** GUI range 动作；值走 wire 的 f32（上游 {@code ConfigActionProto.float_value}）。 */
    public static void submit(@Nullable ResourceLocation modelId, @Nullable String buttonId, int formIndex,
        float value) {
        dispatch(modelId, ConfigAction.floatAction(buttonId, formIndex, value));
    }

    /** GUI radio 动作：label 的声明顺序索引必须与本地声明的名字逐字一致（上游 {@code validCatalogAction} 的 label 部分）。 */
    public static void submit(@Nullable ResourceLocation modelId, @Nullable String buttonId, int formIndex,
        @Nullable String labelName, int labelIndex) {
        dispatch(modelId, ConfigAction.labelAction(buttonId, formIndex, labelName, labelIndex));
    }

    /**
     * {@code S2CConfigAction.Handler} 在客户端线程调用：把服务端授权过的动作在 subject 玩家自己的真实作用域里执行。
     * <p>
     * 校验链照上游 {@code ClientControlHandler.handleConfigAction}（{@code :55-69}）：
     * 按 entity id 在当前世界解析实体并只认玩家 → 取该玩家当前模型 id → 本地载荷内容身份与报文 {@code identity} 比对
     * （非空才比）→ 本地 {@link ModelConfigRegistry} 里找同 id 的 button/form 并核对类型/label → 在该玩家
     * {@code (entity, model)} 作用域里执行本地程序。任何一步失败都静默丢弃（最多一条 warn 日志）。
     */
    public static void receive(int subjectEntityId, @Nullable String identity, @Nullable ConfigAction action) {
        if (action == null || !action.valid()) {
            trace("receive dropped: invalid action {}", action);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            trace("receive dropped: no world");
            return;
        }
        Entity entity = mc.theWorld.getEntityByID(subjectEntityId);
        if (!(entity instanceof EntityPlayer player)) {
            trace("receive dropped: entity {} is not a player (world={})", subjectEntityId, mc.theWorld);
            return;
        }
        ExtendedModelInfo info = ExtendedModelInfo.get(player);
        ResourceLocation modelId = info == null ? null : info.getModelId();
        if (modelId == null) {
            trace("receive dropped: {} has no selected model", player.getCommandSenderName());
            return;
        }
        String localIdentity = ClientRoamingKeys.contentHashFor(modelId);
        if (identity != null && !identity.isEmpty() && !identity.equals(localIdentity)) {
            // 上游接收端要求本地 module 的共享语义身份与报文逐项相等，不一致静默丢弃
            // (ClientControlHandler.java:112-120)。本仓能覆盖"模型内容"这一项的就是载荷 sha256。
            trace(
                "receive dropped: identity mismatch packet='{}' local='{}' model={}",
                identity,
                localIdentity,
                modelId);
            return;
        }
        RawYsmModel.ConfigForm form = formFor(modelId, action.buttonId, action.formIndex);
        if (form == null || !action.matches(form)) {
            trace(
                "receive dropped: no local form for model={} button='{}' form={} (form={}, action={})",
                modelId,
                action.buttonId,
                action.formIndex,
                form,
                action);
            return;
        }
        trace("receive accepted: model={} action={} -> execute", modelId, action);
        execute(player, modelId, action, form);
    }

    /** 触发一次类型化动作：有远端权威时只发报文（发送端不本地执行，照上游 {@code ClientProtocolGateway}）。 */
    private static void dispatch(@Nullable ResourceLocation modelId, @Nullable ConfigAction action) {
        if (modelId == null || action == null || !action.valid() || !isLocalModel(modelId)) {
            trace("dispatch dropped: model={} action={} localModel={}", modelId, action, isLocalModel(modelId));
            return;
        }
        RawYsmModel.ConfigForm form = formFor(modelId, action.buttonId, action.formIndex);
        if (form == null || !action.matches(form)) {
            trace(
                "dispatch dropped: no local form for model={} button='{}' form={} (form={}, action={})",
                modelId,
                action.buttonId,
                action.formIndex,
                form,
                action);
            return;
        }
        // 远端权威 = 有 YSMU 服务端在听，且这个模型是服务端目录里也有的（客户端独有模型没有服务端声明，见类注释）。
        boolean remote = ServerSwitchState.isRemoteChannelPresent();
        boolean clientOnly = ClientModelManager.isClientOnlyModel(modelId);
        trace(
            "dispatch: model={} action={} remote={} clientOnly={} identity='{}'",
            modelId,
            action,
            remote,
            clientOnly,
            ClientRoamingKeys.contentHashFor(modelId));
        if (remote && !clientOnly) {
            try {
                NetworkHandler.CHANNEL.sendToServer(new C2SConfigAction(ClientRoamingKeys.contentHashFor(modelId), action));
            } catch (Exception failure) {
                // 连接正在消失等发送失败：上游 best-effort（NetworkHandler.sendToServer 无重放），这里同样丢弃。
                ysmu.LOG.warn("Failed to submit YSM config action {}", action.buttonId, failure);
            }
            return;
        }
        // 移植特有：没有远端权威（无 YSMU 服务端，或模型只在本机）时没有任何一端能广播这次动作，只能本机执行。
        execute(localPlayer(), modelId, action, form);
    }

    /** 在 subject 玩家的真实作用域里执行一次写程序或 label 动作程序。 */
    private static void execute(@Nullable EntityPlayer subject, ResourceLocation modelId, ConfigAction action,
        RawYsmModel.ConfigForm form) {
        if (subject == null) {
            return;
        }
        String source = actionSource(action, form);
        if (source == null) {
            trace("execute dropped: no program source for action={} form={}", action, form);
            return;
        }
        RoamingProgram.Compiled program = RoamingProgram.compileCached(source);
        if (program == null) {
            trace("execute dropped: program did not compile: '{}'", source);
            return;
        }
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        try (MolangPhysicsRuntime.Scope scope = MolangPhysicsRuntime.pushScope(subject, mainId);
            MolangSoundContext sound = soundContext(subject, subject, mainId)) {
            program.evaluate();
            publishRoamingToRuntime(subject, mainId);
            if (debugEnabled()) {
                Map<String, Double> snapshot = MolangPhysicsRuntime.snapshotVariables(subject, mainId);
                String target = form.defaultValue == null ? "" : form.defaultValue.trim();
                trace(
                    "executed '{}' for {} {}: vars={} target='{}' value={}",
                    source,
                    subject.getCommandSenderName(),
                    mainId,
                    snapshot.size(),
                    target,
                    snapshot.get(target));
            }
        } catch (Exception failure) {
            ysmu.LOG.warn("Failed to execute model form action '{}': {}", source, failure.getMessage());
        }
    }

    /**
     * 把本次程序在作用域里写下的 {@code v.roaming.*} 同步进该实体的远端变量表（{@link RemoteAnimationVariables}）。
     * <p>
     * <b>为什么需要。</b>{@code MolangPhysicsRuntime.begin} 每帧把远端变量表铺进作用域，而本仓的渲染作用域是按
     * {@code (entity, model)} 常驻的表；不把这次写回灌，下一次渲染 pass 就会用服务端上一份权威值把刚写下的 roaming 值盖回去，
     * GUI 与角色都回到旧值，客户端 tick 的采样也永远看不到变化（roaming 上报因此丢失）。上游 PRODUCER 写的是运行时自己的
     * roaming state，权威状态只在服务端 FULL/DELTA 到达时替换它，本机写入在回声之前一直有效
     * （{@code capability/ClientRoamingSession.java:32-62,169-175}）；本仓的"运行时 roaming state"就是这张按实体存的表，
     * 所以这里把本地写入先灌进去，服务端广播的 FULL/DELTA 随后仍是最终权威（{@code S2CRoamingState.Handler} 会替换它）。
     * <p>
     * 只取 {@code v.roaming.*}：普通 {@code v.*} 不经过网络，留在作用域里即可。
     */
    private static void publishRoamingToRuntime(EntityPlayer subject, ResourceLocation mainId) {
        Map<String, Double> snapshot = MolangPhysicsRuntime.snapshotVariables(subject, mainId);
        if (snapshot.isEmpty()) {
            return;
        }
        Map<String, Double> roaming = new HashMap<>();
        for (Map.Entry<String, Double> entry : snapshot.entrySet()) {
            if (entry.getKey() != null && entry.getKey()
                .startsWith(ModelRoamingLimits.PREFIX)) {
                roaming.put(entry.getKey(), entry.getValue());
            }
        }
        if (!roaming.isEmpty()) {
            RemoteAnimationVariables.apply(subject, roaming, false);
        }
    }

    /**
     * 一次动作对应的本地程序源码。
     * <p>
     * 上游在加载期把写程序编成 {@code value = t.value}（{@code format/parser/ModelInfoBuilder.java:162-166}），
     * {@code t.value} 由本次 payload 提供（{@code EntityExpressionRuntime.java:1159-1177}）。本仓的 MoLang 引擎没有
     * program 参数，所以这里把同一个类型化值作为字面量内联进 {@code value=} 的右侧——仍是"写程序来自本地载荷、wire
     * 只带类型化值"的上游形状，只是参数传递从运行时槽位变成源码字面量。
     */
    @Nullable
    private static String actionSource(ConfigAction action, RawYsmModel.ConfigForm form) {
        switch (action.kind) {
            case ConfigAction.KIND_BOOL:
                return writeSource(form, action.boolValue ? "1" : "0");
            case ConfigAction.KIND_F32:
                return writeSource(form, floatLiteral(action.floatValue));
            case ConfigAction.KIND_LABEL:
                return form.labels == null ? null : form.labels.get(action.labelName);
            default:
                return null;
        }
    }

    /** {@code form.value + "=literal"}；空 value 的 checkbox/range 上游直接拒绝整个模型（{@code ModelInfoBuilder.java:158-160}），没有可写目标。 */
    @Nullable
    private static String writeSource(RawYsmModel.ConfigForm form, String literal) {
        String target = form.defaultValue == null ? "" : form.defaultValue.trim();
        if (target.isEmpty() || literal == null) {
            return null;
        }
        return target + "=" + literal;
    }

    /**
     * 读程序源码：{@code form.value}；空 value 的 action-only radio 读常量 {@code "0"}，checkbox/range 的空 value 视为不可用。
     * <p>
     * 与 {@link #writeSource} 的上游依据同源：{@code actionOnlyRadio} 分支在
     * {@code ModelInfoBuilder.java:154-166}；checkbox/range 的空 value 是上游拒绝整个模型的情况。
     */
    @Nullable
    private static String readSource(RawYsmModel.ConfigForm form) {
        String source = form.defaultValue == null ? "" : form.defaultValue.trim();
        if (!source.isEmpty()) {
            return source;
        }
        String type = form.type == null ? "" : form.type.toLowerCase(Locale.ROOT);
        if ("radio".equals(type) && form.labels != null && !form.labels.isEmpty()) {
            return "0";
        }
        return null;
    }

    /**
     * f32 值的 MoLang 字面量。
     * <p>
     * 契约要求用 {@code Float.toString(value)} 保持上游 F32 精度；但 mclib 的数字词法器只认 {@code ^-?\d+(\.\d+)?$}
     * （{@code com/eliotlash/mclib/math/MathBuilder.java:719-721}），没有指数形式，而 {@code Float.toString} 对很小/很大的
     * 值给出 {@code 1.0E-4}/{@code 1.0E7}，那样整条写程序会被当成变量名而解析失败。指数形式因此改写成同一个 float 的十进制
     * 展开（{@link BigDecimal} 从 {@code Float.toString} 构造，保留 F32 的最短十进制表示，不引入 double 噪声）。
     */
    private static String floatLiteral(float value) {
        String text = Float.toString(value);
        if (text.indexOf('E') < 0 && text.indexOf('e') < 0) {
            return text;
        }
        return new BigDecimal(text).toPlainString();
    }

    /**
     * 本地声明里的一次 form 解析：button id 相同、form 索引在界内。
     * <p>
     * 客户端读的是<b>本地模型载荷</b>的同一份声明（服务端读服务端声明快照），所以 wire 上不需要 form 类型或 label 表；
     * 找不到 route 的动作是 no-op，与上游接收端在本地 module 里查不到 program 时静默丢弃一致。
     */
    @Nullable
    private static RawYsmModel.ConfigForm formFor(ResourceLocation modelId, @Nullable String buttonId, int formIndex) {
        if (buttonId == null) {
            return null;
        }
        RawYsmModel.ExtraAnimationButton button = ModelConfigRegistry.buttonFor(modelId, buttonId);
        if (button == null || formIndex < 0 || formIndex >= button.forms.size()) {
            return null;
        }
        return button.forms.get(formIndex);
    }

    @Nullable
    private static EntityPlayer localPlayer() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? null : mc.thePlayer;
    }

    /**
     * 音效上下文：与 {@code ModelFormExecution.drain} 同形。label 动作可能带 {@code play_sound} 之类的副作用，需要事件
     * 通路知道"这是哪一端、哪个模型"；设置面板的动作在本地玩家 ui=false（不是预览 UI 音效）。
     */
    private static MolangSoundContext soundContext(EntityPlayer player, Entity entity, ResourceLocation mainId) {
        return MolangSoundContext.push(player, entity, mainId, null, false, true);
    }

    /**
     * 设置动作链路的诊断日志（{@code Config.DEBUG_MODEL_LOAD} 开着时才输出）。点击频率极低，所以门控一个既有的调试开关
     * 就够；它把"动作在哪一步被静默丢弃"变成可查的日志：派发（远端/仅客户端/本地）、服务端校验、客户端回执执行与结果值。
     * 上游对这些拒绝点同样是静默的，这里只加日志、不改语义。
     */
    private static boolean debugEnabled() {
        return Config.DEBUG_MODEL_LOAD;
    }

    private static void trace(String format, Object... args) {
        if (debugEnabled()) {
            ysmu.LOG.info("[YSMU-CONFIG] " + format, args);
        }
    }
}

