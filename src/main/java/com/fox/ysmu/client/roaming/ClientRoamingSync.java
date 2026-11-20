package com.fox.ysmu.client.roaming;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.input.ServerSwitchState;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SRoamingChanges;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.molang.RemoteAnimationVariables;

/**
 * 本地玩家 roaming 变量的客户端 tick 采样器与上报器：把 {@code v.roaming.*} 的当前值按上游的 full/delta 规则
 * 报给服务端。
 *
 * <h2>上游形状（本类逐条移植）</h2>
 * <ul>
 * <li><b>触发是客户端 tick 主动采样</b>，不是表单动作的回声：{@code client/event/ClientTickEvent.java:20-47} →
 * {@code network/forge/ClientProtocolGateway.tick} → {@code LocalPlayerStateReporter.tick}；planner 拿
 * {@code capability.roamingSnapshot(namespace)} 与上次观测比较
 * （{@code network/protocol/PlayerStateReportPlanner.java:139-201}）。因此动画时间线/脚本写的 roaming 也会上报，而不只是
 * GUI 表单写的那一次；本仓的等价采样点是引擎里真实的 {@code (local player, current model)} 作用域
 * （{@link MolangPhysicsRuntime#snapshotVariables}）。</li>
 * <li><b>首份必须 FULL，之后 DELTA</b>：会话开始、权威身份（模型哈希/roaming key）变化、{@code restartReportLifetime}
 * （重生/维度克隆）、30 s 周期 → {@code needsFull}；DELTA 间隔 ≥50 ms（{@code PlayerStateReportPolicy.java:23-29}、
 * {@code PlayerStateReportPlanner.java:93-149}）。服务端在没有该连接该 key 的 FULL baseline 时拒收 DELTA
 * （{@code model/session/server/ServerModelSession.java:98-119}），所以 FULL 即使全空也必须发一次（count=0 是有效的
 * "该命名空间为空"）。</li>
 * <li><b>未知 key / 未收到权威状态不报</b>：payload 还没到时 {@link ClientRoamingKeys#isKnown} 为假；payload 到了但服务端
 * 还没推来该 key 的权威 FULL 时（{@link ClientRoamingStore#isAuthoritative} 为假）同样不发——首份 FULL 是替换，用本地
 * 还没播种的视图去发会把服务端存档抹掉。等授权状态到达的那一 tick 以 FULL 建立 baseline（上游对应物：planner 拿到 key 与
 * 权威快照之前放弃本次机会，{@code PlayerStateReportPlanner.java:139-143}；{@code LocalPlayerStateReporter.java:70-88}）。</li>
 * <li><b>发送门</b>：无远端 YSMU 通道（握手未回，或维度切换的两帧之间）不报；服务端下发的
 * roaming-sync 开关为关时不报（{@link ServerSwitchState#isRoamingSyncEnabled()}）。上游这个开关是服务端的
 * {@code ServerConfig.LOW_BANDWIDTH_USAGE}，经 {@code ServerHello.sync_roaming} 交给客户端
 * （{@code network/forge/SessionProtocolHandler.java:63-75}、{@code PlayerStateReportPolicy.java:23-28}）；移植用
 * {@code S2CServerConfig} 的第二个布尔承载同一事实。旧实现读客户端自己的 {@code Config.LOW_BANDWIDTH_USAGE}
 * 是错误侧的门（客户端配置不该决定服务端是否收状态，review RB-6）。</li>
 * </ul>
 * <p>
 * <b>已删除的移植特有机制。</b>旧实现有两样上游没有的东西：把本地表单写入直接塞进 {@code ClientRoamingStore}，以及在
 * (world, model, key) 变化时发"零条 DELTA"当"请给我保存值"的请求（服务端据此广播）。phase78 删掉两者：写进真实作用域、
 * tick 采样，服务端在登录/换模型/克隆时主动推 FULL（契约 §5.2、§6.3）。空 DELTA 现在只是"没有变化"，不再有请求语义。
 */
public final class ClientRoamingSync {

    /** 上游 DELTA 下限（{@code PlayerStateReportPolicy.java:23-29}: {@code MIN_DELTA_INTERVAL_MILLIS = 50}）。 */
    private static final long DELTA_INTERVAL_MILLIS = 50L;

    /** 上游周期 FULL（{@code PlayerStateReportPolicy.java:23-29}: {@code FULL_REFRESH_INTERVAL_MILLIS = 30_000}）。 */
    private static final long FULL_REFRESH_INTERVAL_MILLIS = 30_000L;

    /**
     * baseline 所属的世界，按身份比较：1.7.10 每次进世界/切维度都新建 {@link World}
     * （{@code Minecraft.loadWorld:2249-2301}、{@code NetHandlerPlayClient.handleRespawn:988-1000}），本仓已用它作连接身份
     * （{@code input/ServerSwitchState} 同一判定）。
     */
    private static World baselineWorld;

    /** baseline 所属的模型 id（base id，见 {@link ExtendedModelInfo#getModelId()}）。 */
    private static ResourceLocation baselineModel;

    /** baseline 所属的 roaming key，来自 {@link ClientRoamingKeys#keyFor}。 */
    private static int baselineKey;

    /** 上一次建立 baseline 时 key 是否已由载荷给出（{@link ClientRoamingKeys#isKnown}）。 */
    private static boolean baselineKeyKnown;

    /** 上一次成功上报的完整命名空间（后缀 -> 值），用于算出 DELTA。 */
    private static Map<String, Double> lastReported = Collections.emptyMap();

    /** 下一次发送必须是 FULL（首份、身份变化、或 reset 后强制重建）。 */
    private static boolean fullPending = true;

    /** 上一次发送 FULL 的墙钟时间；0 表示还没有发过。 */
    private static long lastFullMillis;

    /** 上一次发送 DELTA 的墙钟时间，用于 50 ms 节流。 */
    private static long lastDeltaMillis;

    private ClientRoamingSync() {}

    /**
     * 每个客户端 tick 调一次（{@code ClientEventHandler#onClientTick}，相位同上游 {@code ClientTickEvent.START}）。
     * 采样本地玩家当前模型作用域里的 {@code v.roaming.*}，按 full/delta 规则上报；没有变化时什么都不发。
     */
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer local = mc == null ? null : mc.thePlayer;
        World world = mc == null ? null : mc.theWorld;
        if (local == null || world == null) {
            reset();
            return;
        }
        ExtendedModelInfo info = ExtendedModelInfo.get(local);
        ResourceLocation modelId = info == null ? null : info.getModelId();
        if (modelId == null) {
            resetBaseline(world, null);
            return;
        }
        // The key is only meaningful once the payload that carries the content hash has arrived; before that every value
        // the server holds lives under a namespace this client cannot name yet.
        boolean keyKnown = ClientRoamingKeys.isKnown(modelId);
        int key = ClientRoamingKeys.keyFor(modelId);
        if (world != baselineWorld || !modelId.equals(baselineModel) || key != baselineKey
            || keyKnown != baselineKeyKnown) {
            boolean modelChanged = baselineModel != null && !modelId.equals(baselineModel);
            resetBaseline(world, modelId);
            baselineKey = key;
            baselineKeyKnown = keyKnown;
            if (modelChanged) {
                // 上游：换模型是一次新的安装，普通 v.* 的 Molang 内存随旧安装退休（docs/architecture/animation/
                // entity-and-frame-state.md:35；EER:104-108,1373-1387 每次安装新建 State），所以"重登/换模型回包默认值"。
                // 本仓的引擎作用域按 (player UUID, model) 键控、会跨模型切换存活，这里用已有的 clearPlayer 原语把该玩家
                // 的作用域丢掉，roaming 随后由服务端权威副本重建（restoreRuntimeFromStore）。这是换模型这个时刻客户端
                // 唯一知道"安装变了"的地方：报告器每 tick 复核当前穿戴模型，也是 ClientEventHandler#onClientTick 的挂点。
                MolangPhysicsRuntime.clearPlayer(local.getUniqueID());
            }
            restoreRuntimeFromStore(local, key, keyKnown);
        }
        if (!keyKnown) {
            return;
        }
        if (!ServerSwitchState.isRemoteChannelPresent() || !ServerSwitchState.isRoamingSyncEnabled()) {
            return;
        }
        // A model only this client carries has no server-side declarations (upstream has no client-only models at all),
        // so the server can neither validate nor persist a report for it; reporting would be dropped. Skip it.
        if (ClientModelManager.isClientOnlyModel(modelId)) {
            return;
        }
        // No authoritative state for this key yet: the server's FULL has not been applied, so the engine scope has not
        // been seeded and a report now would be an empty FULL built from nothing - and a FULL is a replace. That would
        // erase the saved values on the server. Upstream has the same gate: the planner reports nothing until it has an
        // authoritative roaming snapshot (network/forge/LocalPlayerStateReporter.java:70-88,
        // capability/ClientRoamingSession.java:76-99).
        if (!ClientRoamingStore.isAuthoritative(local.getUniqueID(), key)) {
            return;
        }
        Map<String, Double> current = sample(local, modelId, key);
        long now = System.currentTimeMillis();
        boolean full = fullPending || lastFullMillis == 0L || now - lastFullMillis >= FULL_REFRESH_INTERVAL_MILLIS;
        if (full) {
            if (send(key, true, current)) {
                lastReported = current;
                fullPending = false;
                lastFullMillis = now;
            }
            return;
        }
        Map<String, Double> delta = delta(current, lastReported);
        if (delta.isEmpty()) {
            return;
        }
        if (now - lastDeltaMillis < DELTA_INTERVAL_MILLIS) {
            // Throttled, not dropped: lastReported is not advanced, so the next tick that passes the interval reports it.
            return;
        }
        if (send(key, false, delta)) {
            lastReported = current;
            lastDeltaMillis = now;
        }
    }

    /**
     * Forces the next tick to report a FULL, even when nothing visible changed. Used by the client-side reset path after
     * the engine scope's {@code v.roaming.*} keys were cleared: the cleared namespace has to reach the server as a FULL
     * (an empty one is meaningful), and the ordinary delta logic would see "no change" because both snapshots are empty.
     * This replaces a separate reset packet - the server's authority is expressed by the FULL it broadcasts back
     * ({@code network/forge/PlayerStateHandler.java:214-239}).
     */
    public static void forceFull() {
        fullPending = true;
        lastFullMillis = 0L;
    }

    /** Forgets the baseline, for a logout/resource reload: the next session starts with a FULL again. */
    public static void reset() {
        baselineWorld = null;
        resetBaseline(null, null);
        baselineKey = 0;
        baselineKeyKnown = false;
        lastFullMillis = 0L;
        lastDeltaMillis = 0L;
        fullPending = true;
    }

    private static void resetBaseline(@Nullable World world, @Nullable ResourceLocation modelId) {
        baselineWorld = world;
        baselineModel = modelId;
        lastReported = Collections.emptyMap();
        fullPending = true;
        lastFullMillis = 0L;
        lastDeltaMillis = 0L;
    }

    /**
     * 模型/世界变化时把该 key 的权威副本重新灌进引擎的远端变量表，等价于上游"新安装从 retained 快照初始化 roaming"
     * （{@code capability/ClientRoamingSession.java:32-62}：候选安装把该 key 的 retained 值整体交给新运行时，没有则空）。
     * <p>
     * 为什么不能在收到 S2C 时直接灌：服务端换模型时先推新 key 的 FULL、客户端 EEP 的模型 id 稍后才随 {@code SyncModelInfo}
     * 更新，所以 {@code S2CRoamingState.Handler} 的"报文 key 等于本地当前模型"门控在那一刻还不成立——这条路径就是用已经
     * 存下来的副本补上那一次安装初始化。key 还不知道时什么都不做：旧模型的视图不能泄漏进新模型的作用域，但此时也没有
     * 可信的新 key 可以查表（payload 到达后本方法会再跑一次）。
     */
    private static void restoreRuntimeFromStore(EntityPlayer local, int key, boolean keyKnown) {
        if (!keyKnown) {
            return;
        }
        RemoteAnimationVariables.apply(local, ClientRoamingStore.valuesFor(local.getUniqueID(), key), true);
    }

    /**
     * 把某个玩家的当前模型在服务端权威副本里的 roaming 值重新灌进引擎的远端变量表（FULL 替换），供<b>被观察玩家</b>换模型时
     * 在观察端重建运行时。由 {@code network/message/SyncModelInfo.applyToEntity} 在该玩家 EEP 的模型 id 真的变化后调用。
     * <p>
     * <b>为什么需要（review RB-4b）。</b>{@code S2CRoamingState.Handler} 只在"报文 key == 该玩家<b>当前</b>模型 key"时写
     * {@code RemoteAnimationVariables}，而服务端换模型时先推新 key 的 FULL、观察端的 EEP 是稍后随 {@code SyncModelInfo}
     * 才更新的——那次 FULL 在门控上被丢掉，之后没有谁补，于是观察端会用旧命名空间的同名值（{@code v.roaming.player_size}
     * 这类跨包通用名）或默认值渲染，直到该玩家的 30 s 周期 FULL。上游的对应物是观察端在新安装时从 retained 快照初始化
     * roaming（{@code capability/ClientRoamingSession.java:32-62}）；本仓 retained 快照就是 {@link ClientRoamingStore}。
     * <p>
     * 先清引擎作用域里的 roaming 命名空间再灌表：FULL 的语义是整命名空间替换，"被删掉的键"必须真的消失，否则下一次
     * {@code begin} 只 put 不删，旧键会残留（与 review RB-3 的 FULL 语义一致）。key 还未知（新模型 payload 未到）时查不到
     * 权威副本，此时安装空命名空间仍是正确的"新安装"状态（上游没有 baseline 就是空），该玩家的下一次服务端 FULL/DELTA
     * 会重新填回。
     */
    public static void reseedFromStore(@Nullable EntityPlayer player) {
        if (player == null) {
            return;
        }
        ExtendedModelInfo info = ExtendedModelInfo.get(player);
        ResourceLocation modelId = info == null ? null : info.getModelId();
        if (modelId == null) {
            return;
        }
        int key = ClientRoamingKeys.keyFor(modelId);
        Map<String, Double> values = ClientRoamingStore.valuesFor(player.getUniqueID(), key);
        MolangPhysicsRuntime.clearRoamingVariables(player, ModelIdUtil.getMainId(modelId));
        RemoteAnimationVariables.apply(player, values, true);
    }

    /**
     * 采样本地玩家当前模型的 roaming 命名空间：{@code v.roaming.*} 全名 → 后缀，丢掉服务端不会收的名字
     * （非 UTF-8 ≤32 字节、控制字符、非有限值），并按上游的每模型变量数上限截断。
     * <p>
     * 两个来源按上游的语义合并：
     * <ol>
     * <li>服务端权威状态的客户端副本（{@link ClientRoamingStore#valuesFor}）——上游 {@code LocalPlayerStateReporter}
     * 说的 "retained 快照"（{@code :70-88}），它让客户端在实体还没渲染、作用域还没播种时也能报出已知状态；</li>
     * <li>引擎里真实的 {@code (player, model)} 作用域（{@link MolangPhysicsRuntime#snapshotVariables}）——上游同一处
     * 的 "current install sampling"，即本地运行时自下次上报以来写入的值，覆盖在副本之上（PRODUCER 的本机写入）。</li>
     * </ol>
     * 只报副本而不报作用域会漏掉当帧写入；只报作用域会在登录后实体尚未渲染时把"作用域还空"误报成权威状态为空
     * （FULL 是替换，会把服务端存档抹掉）。合并是这两个上游事实在 1.7.10 上的最小并集，不新增状态。
     */
    private static Map<String, Double> sample(EntityPlayer local, ResourceLocation modelId, int modelKey) {
        Map<String, Double> values = new HashMap<>();
        for (Map.Entry<String, Double> entry : ClientRoamingStore.valuesFor(local.getUniqueID(), modelKey)
            .entrySet()) {
            putSample(values, entry.getKey(), entry.getValue());
        }
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        for (Map.Entry<String, Double> entry : MolangPhysicsRuntime.snapshotVariables(local, mainId)
            .entrySet()) {
            putSample(values, entry.getKey(), entry.getValue());
        }
        return values.isEmpty() ? Collections.emptyMap() : values;
    }

    /** One sampled value: only {@code v.roaming.*} names the wire accepts, up to upstream's per-model variable bound. */
    private static void putSample(Map<String, Double> values, @Nullable String fullName, @Nullable Double value) {
        if (values.size() >= ModelRoamingLimits.MAX_VARIABLES_PER_MODEL) {
            return;
        }
        String suffix = ModelRoamingLimits.suffixOf(fullName);
        if (suffix == null || !ModelRoamingLimits.isAcceptableSuffix(suffix)) {
            return;
        }
        if (value == null || !ModelRoamingLimits.isAcceptableValue(value)) {
            return;
        }
        values.put(suffix, value);
    }

    /**
     * 相对上次上报的变化集：新出现的键与值变了的键。上游 planner 的 DELTA 语义就是这样"与上次观测比较"
     * （{@code network/protocol/PlayerStateReportPlanner.java:192-201}）；删除没有 wire 语义（DELTA 只合并），命名空间的
     * 删除只能由 FULL 表达，这与上游一致（FULL 省略 roaming = 清空）。
     */
    private static Map<String, Double> delta(Map<String, Double> current, Map<String, Double> previous) {
        Map<String, Double> delta = new HashMap<>();
        for (Map.Entry<String, Double> entry : current.entrySet()) {
            Double old = previous.get(entry.getKey());
            if (old == null || !old.equals(entry.getValue())) {
                delta.put(entry.getKey(), entry.getValue());
            }
        }
        return delta;
    }

    /** Sends one report; returns whether it was handed to the channel, so a failed send is retried by the next tick. */
    private static boolean send(int modelKey, boolean full, Map<String, Double> suffixValues) {
        try {
            NetworkHandler.CHANNEL.sendToServer(C2SRoamingChanges.of(modelKey, full, suffixValues));
            return true;
        } catch (Exception failure) {
            // best-effort like upstream (NetworkHandler.sendToServer has no replay): keep the state so the next tick tries
            // again while the connection is still there.
            ysmu.LOG.warn("Failed to report YSM roaming changes; they stay pending", failure);
            return false;
        }
    }
}
