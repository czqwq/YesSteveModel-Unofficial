package com.fox.ysmu.network.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.roaming.ClientRoamingKeys;
import com.fox.ysmu.client.roaming.ClientRoamingStore;
import com.fox.ysmu.data.ConfigDeclarations;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.eep.ExtendedRoamingVariables;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.molang.RemoteAnimationVariables;

/**
 * 服务端 → 客户端：某个玩家某个模型命名空间的权威 roaming 变量，语义是上游 {@code PlayerStateUpdate.roaming}
 * （{@code tmp/YesSteveModel-dev-1.20} 的 {@code network/protocol/player_state.proto:15-25}；移植把它的 roaming 段单独
 * 装进一个 {@code SimpleNetworkWrapper} 报文，理由见契约 §8 第 1 条）。发送者是服务端（登录/换模型/维度克隆主动推
 * FULL，以及客户端上报被接受后的同 mode 回显），收件端是跟踪该玩家的客户端<b>加他自己</b>（
 * {@code NetworkHandler.sendToTrackingPlayers}，与上游 {@code CONTROL} 的 TRACKING_AND_SELF 同规则）。
 *
 * <p><b>布局（NETWORK_PROTOCOL 13 起）。</b>变量名是<b>后缀</b>（{@code car}），值 float32：</p>
 *
 * <pre>
 * UUID playerId, int modelKey, byte mode(0=DELTA,1=FULL), unsigned short count,
 * count × [UTF8 suffix, float value]
 * </pre>
 *
 * <p><b>应用规则（上游 {@code PlayerStateHandler.applyClientUpdate}，{@code
 * network/forge/PlayerStateHandler.java:327-338,353-373}）。</b>FULL = 整命名空间替换（{@code count=0} 表示清空），
 * DELTA = 合并。收件端同时把值写进两处：{@link ClientRoamingStore}（按玩家+modelKey 分桶，内部键是完整
 * {@code v.roaming.x}）与引擎的 {@link RemoteAnimationVariables}（按实体作用域）。引擎那一处只在报文 modelKey
 * 等于该玩家<b>当前</b>模型的 roaming key 时写：否则一个旧命名空间的回包会把上一模型的值喂给新模型（audit RC-4）。</p>
 */
public class S2CRoamingState implements IMessage {

    /** 上游 wire 的 {@code mode}：增量合并（{@code player_state.proto:6-13} 的 DELTA 语义）。 */
    public static final byte MODE_DELTA = 0;
    /** 上游 wire 的 {@code mode}：整命名空间替换；省略变量即删除（{@code PlayerStateHandler.java:327-338}）。 */
    public static final byte MODE_FULL = 1;

    /** 头：两个 long 的 UUID + int modelKey + byte mode + short count。 */
    private static final int HEADER_BYTES = 16 + 4 + 1 + 2;

    /**
     * 本连接（本次登录）上已被接受的 FULL 所属 modelKey，按玩家 UUID 记。
     * <p>
     * 上游的这个事实在 {@code ServerModelSession} 里（{@code model/session/server/ServerModelSession.java:98-119}：
     * DELTA 必须已有真实 FULL baseline，且 baseline 属于同一连接）。1.7.10 没有 session 层，用这张只有
     * UUID → 最后一次 FULL 的 modelKey 的小表代替；登录、登出、维度/重生克隆都会清（{@code CommonEventHandler}），
     * 因为那些事件换的是"这次连接"的资格。
     */
    private static final Map<UUID, Integer> FULL_BASELINES = new ConcurrentHashMap<>();

    private UUID playerId;
    private int modelKey;
    private byte mode = MODE_DELTA;
    /** 后缀 → 值。{@link #fromBytes} 失败时保持空表，{@link #parsed} 为 false，handler 因此丢弃整包。 */
    private Map<String, Double> values = Collections.emptyMap();
    /**
     * 解码是否完整成功。{@code count=0} 是合法的 FULL（清空命名空间），所以"空表"不能当作失败：handler 必须看这个
     * 标志，否则一条坏包会变成"FULL 空 = 清空"（audit RC-3/RC-4）。
     */
    private boolean parsed;

    public S2CRoamingState() {}

    private S2CRoamingState(UUID playerId, int modelKey, byte mode, Map<String, Double> values, boolean parsed) {
        this.playerId = playerId;
        this.modelKey = modelKey;
        this.mode = mode;
        this.values = values == null ? Collections.emptyMap() : new HashMap<>(values);
        this.parsed = parsed;
    }

    /** 构造一条状态报文；服务端生产路径用 {@link #broadcast}/{@link #broadcastFull}。 */
    public static S2CRoamingState of(UUID playerId, int modelKey, boolean full, @Nullable Map<String, Double> values) {
        return new S2CRoamingState(
            playerId,
            modelKey,
            full ? MODE_FULL : MODE_DELTA,
            values,
            true);
    }

    public UUID getPlayerId() {
        return this.playerId;
    }

    public int getModelKey() {
        return this.modelKey;
    }

    /** {@code true} = FULL，{@code false} = DELTA。 */
    public boolean isFull() {
        return this.mode == MODE_FULL;
    }

    public Map<String, Double> getValues() {
        return this.values;
    }

    /** 解码是否完整成功；{@code false} 时 handler 必须丢弃整包。 */
    public boolean isParsed() {
        return this.parsed;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 服务端发送
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * 把服务端持有的一个 modelKey 的状态按 {@code full} 广播给跟踪者 + 自己（{@code C2SRoamingChanges.Handler}
     * 接受一次上报后的回显；FULL 时内容就是刚写进存档的整命名空间，DELTA 时是本次增量）。
     * <p>
     * 只在服务端线程调用：发送路径读 {@code worldObj}/追踪关系。
     */
    public static void broadcast(EntityPlayerMP player, int modelKey, boolean full,
        @Nullable Map<String, Double> suffixValues) {
        if (player == null) {
            return;
        }
        S2CRoamingState message = new S2CRoamingState(
            player.getUniqueID(),
            modelKey,
            full ? MODE_FULL : MODE_DELTA,
            suffixValues,
            true);
        NetworkHandler.sendToTrackingPlayers(message, player, 128.0d);
    }

    /**
     * 把该玩家<b>当前模型</b>的整个 roaming 命名空间作为 FULL 广播（含自己），并登记本连接 baseline。
     * <p>
     * 调用点是上游"服务端主动重建 baseline"的三处移植：登录（上游 {@code SessionProtocolHandler.java:468-513} 在
     * 会话接受时发自身 FULL）、换模型被接受（上游 {@code ControlHandler.java:169-193} → {@code
     * PlayerStateHandler.sendAuthoritativeFull}）、维度/重生克隆（上游 {@code CapabilityEvent.onPlayerCloned}
     * 把 capability 移到新实例后由 tick 广播 FULL）。EEP/模型/声明缺失时什么都不发——没有这些事实就没有可校验的
     * modelKey，发出去的值也无法被收件端落到正确的命名空间。
     * <p>
     * 评审 RB-2（{@code .agent/phase78-review.md}）：{@code /ysm reload} 会先让客户端清掉 roaming 副本与上报资格
     * （客户端 {@code prepareModelSync}），之后服务端只发 {@code RequestSyncModel}；客户端回 {@code SyncModelFiles}
     * 时由该 handler 再推一次本方法，权威状态才回到客户端，否则本会话永远等不到"下一次 FULL"。
     */
    public static void broadcastFull(EntityPlayerMP player) {
        S2CRoamingState message = buildFull(player);
        if (message == null) {
            return;
        }
        NetworkHandler.sendToTrackingPlayers(message, player, 128.0d);
        acceptFullBaseline(player.getUniqueID(), message.getModelKey());
    }

    /**
     * 只把 subject 当前模型的 FULL 发给一个<b>观察者</b>（新开始追踪该玩家的客户端），不动观察者自己的 baseline。
     * <p>
     * 评审 RB-4a（{@code .agent/phase78-review.md}）：远端玩家的 FULL 只在"报文 key == 观察端 EEP 当前模型 key"时
     * 才写引擎，而换模型时服务端先广播 FULL、观察端的 EEP 要等随后的 dirty {@code SyncModelInfo} 才更新，那一条
     * FULL 因此被门控丢弃，此后要等 30 s 周期 FULL 才补——新观察者则完全没有补发点。上游在开始追踪实体时推送该
     * 实体的当前状态（{@code event/CapabilityEvent.java:144-158}；服务端驱动属性的 tracker 也在 tracking start
     * 补发当前值，{@code capability/ServerDrivenPlayerPropertiesTracker.java}），所以这里照同一形状补一次。
     * <p>
     * 与 {@link #broadcastFull} 的区别只是收件人：发的是被观察者当前模型的权威状态，收件人是 tracker；DELTA
     * baseline 是"这个 tracker 自己的上报资格"，与本消息无关，因此不碰。
     */
    public static void sendFullTo(@Nullable EntityPlayerMP tracker, @Nullable EntityPlayerMP subject) {
        if (tracker == null || subject == null || tracker == subject) {
            return;
        }
        S2CRoamingState message = buildFull(subject);
        if (message == null) {
            return;
        }
        NetworkHandler.CHANNEL.sendTo(message, tracker);
    }

    /**
     * 按 subject 的当前模型构建一条 FULL；EEP/模型/声明缺失时返回 {@code null}（= 什么都不发）。
     * 只在服务端线程调用：读 {@code worldObj}/EEP。
     */
    @Nullable
    private static S2CRoamingState buildFull(@Nullable EntityPlayerMP subject) {
        if (subject == null || subject.isDead || subject.worldObj == null) {
            return null;
        }
        ExtendedModelInfo info = ExtendedModelInfo.get(subject);
        if (info == null || info.getModelId() == null) {
            return null;
        }
        ResourceLocation modelId = info.getModelId();
        ConfigDeclarations declarations = ServerModelManager.configDeclarationsFor(modelId);
        if (declarations == null) {
            // 服务端没有该模型的声明快照（未知模型 / 客户端本地注册模型）：roaming 服务端校验也无法通过，不发。
            return null;
        }
        ExtendedRoamingVariables store = ExtendedRoamingVariables.get(subject);
        if (store == null) {
            return null;
        }
        int modelKey = declarations.roamingKey(modelId.toString());
        Map<String, Float> stored = store.valuesIfPresent(modelKey);
        // FULL 的 count=0 是合法状态（= 清空该命名空间），所以没有存档值时仍然要发这条空 FULL。
        Map<String, Double> snapshot = new HashMap<>();
        if (stored != null) {
            for (Map.Entry<String, Float> entry : stored.entrySet()) {
                snapshot.put(entry.getKey(), (double) entry.getValue());
            }
        }
        return new S2CRoamingState(subject.getUniqueID(), modelKey, MODE_FULL, snapshot, true);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // baseline 表（DELTA 的准入条件）
    // ---------------------------------------------------------------------------------------------------------------

    /** 登记一条已接受的 FULL：本连接上该 modelKey 从此接受 DELTA。 */
    public static void acceptFullBaseline(UUID playerId, int modelKey) {
        if (playerId != null) {
            FULL_BASELINES.put(playerId, modelKey);
        }
    }

    /** 本连接上该 modelKey 是否已有被接受的 FULL（上游 {@code ServerModelSession:98-119} 的 baseline 门）。 */
    public static boolean hasFullBaseline(UUID playerId, int modelKey) {
        Integer baseline = playerId == null ? null : FULL_BASELINES.get(playerId);
        return baseline != null && baseline == modelKey;
    }

    /** 换连接/登录/登出/克隆时清掉该玩家的 baseline，下一次 FULL 重新建立。 */
    public static void forgetBaseline(UUID playerId) {
        if (playerId != null) {
            FULL_BASELINES.remove(playerId);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 报文编解码（N-10）
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10：见 C2SRoamingChanges#fromBytes —— 逐段先与 readableBytes() 比较，失败静默丢弃，只在完整解析后赋值。
        boolean parsedHere = false;
        UUID parsedPlayer = null;
        int parsedKey = 0;
        byte parsedMode = MODE_DELTA;
        Map<String, Double> parsedValues = Collections.emptyMap();
        try {
            if (buf.readableBytes() >= HEADER_BYTES) {
                UUID playerId = new UUID(buf.readLong(), buf.readLong());
                int key = buf.readInt();
                byte mode = buf.readByte();
                int count = buf.readUnsignedShort();
                if ((mode == MODE_DELTA || mode == MODE_FULL) && count <= ModelRoamingLimits.MAX_VARIABLES_PER_MODEL) {
                    Map<String, Double> entries = readEntries(buf, count);
                    if (entries != null) {
                        parsedHere = true;
                        parsedPlayer = playerId;
                        parsedKey = key;
                        parsedMode = mode;
                        parsedValues = entries;
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Ignoring malformed YSM roaming-state packet", e);
        }
        this.playerId = parsedPlayer;
        this.modelKey = parsedKey;
        this.mode = parsedMode;
        this.values = parsedValues;
        this.parsed = parsedHere;
    }

    /**
     * 读 {@code count} 个「后缀 + float」对；缓冲不够或任一项不合规都返回 {@code null} = 整包作废。空表（count=0）
     * 是合法的，由调用方按 mode 决定语义（FULL 空 = 清空）。
     */
    @Nullable
    private static Map<String, Double> readEntries(ByteBuf buf, int count) {
        Map<String, Double> parsed = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            if (buf.readableBytes() < 2) {
                return null;
            }
            String suffix = ByteBufUtils.readUTF8String(buf);
            if (buf.readableBytes() < 4) {
                return null;
            }
            float value = buf.readFloat();
            if (!ModelRoamingLimits.isAcceptableSuffix(suffix) || !ModelRoamingLimits.isAcceptableValue(value)) {
                return null;
            }
            if (parsed.put(suffix, (double) value) != null) {
                return null;
            }
        }
        return parsed;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        UUID id = this.playerId == null ? new UUID(0L, 0L) : this.playerId;
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
        buf.writeInt(this.modelKey);
        buf.writeByte(this.isFull() ? MODE_FULL : MODE_DELTA);
        Map<String, Double> written = new LinkedHashMap<>();
        if (this.values != null) {
            for (Map.Entry<String, Double> entry : this.values.entrySet()) {
                if (!ModelRoamingLimits.isAcceptableSuffix(entry.getKey()) || entry.getValue() == null
                    || !ModelRoamingLimits.isAcceptableValue(entry.getValue())) {
                    continue;
                }
                written.put(entry.getKey(), entry.getValue());
                if (written.size() >= ModelRoamingLimits.MAX_VARIABLES_PER_MODEL) {
                    break;
                }
            }
        }
        buf.writeShort(written.size());
        for (Map.Entry<String, Double> entry : written.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, entry.getKey());
            buf.writeFloat(entry.getValue().floatValue());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 客户端消费
    // ---------------------------------------------------------------------------------------------------------------

    public static class Handler implements IMessageHandler<S2CRoamingState, IMessage> {

        @Override
        public IMessage onMessage(S2CRoamingState message, MessageContext ctx) {
            try {
                if (message == null || !message.parsed || message.playerId == null) {
                    return null;
                }
                final UUID playerId = message.playerId;
                final int modelKey = message.modelKey;
                final boolean full = message.isFull();
                final Map<String, Double> values = new HashMap<>(message.values);
                // 客户端状态与引擎 Molang 作用域都只能在客户端线程读写。
                DeferredWork.client(() -> apply(playerId, modelKey, full, values));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM roaming-state packet", e);
            }
            return null;
        }

        /**
         * 应用一条权威状态：存储层用完整名（{@link ClientRoamingStore} 与 {@link RemoteAnimationVariables} 都以
         * {@code v.roaming.x} 键控），wire 上是后缀，所以先逐项转换；引擎那一处再按"是否当前模型"门控。
         */
        @SideOnly(Side.CLIENT)
        private static void apply(UUID playerId, int modelKey, boolean full, Map<String, Double> suffixValues) {
            Map<String, Double> fullNameValues = new HashMap<>();
            for (Map.Entry<String, Double> entry : suffixValues.entrySet()) {
                if (ModelRoamingLimits.isAcceptableSuffix(entry.getKey()) && entry.getValue() != null) {
                    fullNameValues.put(ModelRoamingLimits.fullName(entry.getKey()), entry.getValue());
                }
            }
            ClientRoamingStore.acceptState(playerId, modelKey, fullNameValues, full);
            EntityPlayer player = findLocalPlayer(playerId);
            if (player == null) {
                return;
            }
            ExtendedModelInfo info = ExtendedModelInfo.get(player);
            if (info == null || info.getModelId() == null) {
                return;
            }
            // 旧命名空间的回包不得喂给当前模型（audit RC-4）：只有报文 key 等于该玩家当前模型的 key 时才推进引擎
            // 作用域。存储本身以 modelKey 分桶，留下非当前 key 的条目是无害的。
            if (ClientRoamingKeys.keyFor(info.getModelId()) == modelKey) {
                // phase78 评审 RB-3：FULL 的"省略即删除"语义必须真的落到引擎作用域。上游 resetRoamingVars 替换的是
                // 运行时 roaming struct，而本仓引擎作用域过去只被 RemoteAnimationVariables.apply 逐键 put、没有删除
                // 路径（MolangPhysicsRuntime.applyRemoteVariables:270-278），于是 FULL 省略的变量在作用域里残留：
                // 远端玩家看到旧值永不消失，本地采样器（store ∪ 作用域）还会把服务端刚删掉的值当当前值再上报，
                // 让删除"复活"。所以 FULL 先清掉该 (player, 当前模型) 作用域里的 v.roaming.*，再灌权威值；
                // DELTA 保持合并语义，不清理。作用域键用 mainId，与渲染 pass 的 begin 和 /ysm reset 同一套。
                if (full) {
                    MolangPhysicsRuntime.clearRoamingVariables(player, ModelIdUtil.getMainId(info.getModelId()));
                }
                RemoteAnimationVariables.apply(player, fullNameValues, full);
            }
        }

        @SideOnly(Side.CLIENT)
        @Nullable
        private static EntityPlayer findLocalPlayer(UUID playerId) {
            Minecraft mc = Minecraft.getMinecraft();
            WorldClient world = mc.theWorld;
            if (world == null) {
                return null;
            }
            List<?> players = world.playerEntities;
            for (Object candidate : players) {
                if (candidate instanceof EntityPlayer entity && playerId.equals(entity.getUniqueID())) {
                    return entity;
                }
            }
            return null;
        }
    }
}
