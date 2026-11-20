package com.fox.ysmu.network.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.data.ConfigDeclarations;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.eep.ExtendedRoamingVariables;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 → 服务端：本地玩家某个模型命名空间的 roaming 变量上报，语义是上游 {@code PlayerStateReport.roaming}
 * （{@code tmp/YesSteveModel-dev-1.20} 的 {@code network/protocol/player_state.proto:6-13,45-49}；移植把它的 roaming
 * 段单独装进一个 {@code SimpleNetworkWrapper} 报文，理由见契约 §8 第 1 条）。
 *
 * <p><b>布局（NETWORK_PROTOCOL 13 起）。</b>变量名是<b>后缀</b>（{@code car}，不带 {@code v.roaming.}），与上游
 * wire 一致；值用 float32，与上游 {@code RoamingState.variables[].value} 一致：</p>
 *
 * <pre>
 * int modelKey, byte mode(0=DELTA,1=FULL), unsigned short count, count × [UTF8 suffix, float value]
 * </pre>
 *
 * <p><b>mode。</b>{@link S2CRoamingState#MODE_FULL FULL} = 整命名空间替换（{@code count=0} 表示清空）；
 * {@link S2CRoamingState#MODE_DELTA DELTA} = 合并。首条必须是 FULL，DELTA 只有在本连接该 modelKey 已有被接受的
 * FULL baseline 后才收（上游 {@code ServerModelSession.acceptPlayerStateReport}，{@code
 * model/session/server/ServerModelSession.java:98-119}）。旧布局里"零条 DELTA = 请把权威值发给我"是移植特有语义，
 * phase78 已删除：baseline 改由服务端在登录/换模型/维度克隆时主动推 FULL。</p>
 *
 * <p><b>服务端校验（照上游 {@code PlayerStateHandler.applyReport}，{@code
 * network/forge/PlayerStateHandler.java:150-240,271-286}）。</b>主体必须是发送者自己的当前模型；modelKey 必须等于
 * 该模型内容哈希派生的 roaming key；名字后缀非空、UTF-8 ≤32 字节、无控制字符、不重复；值有限；条数 ≤64；
 * DELTA 需要已接受的 FULL baseline。任何一步失败都静默丢弃（最多 debug 日志），handler 体包在 try/catch 里。</p>
 */
public class C2SRoamingChanges implements IMessage {

    /** 头：int modelKey + byte mode + short count。 */
    private static final int HEADER_BYTES = 4 + 1 + 2;

    private int modelKey;
    private byte mode = S2CRoamingState.MODE_DELTA;
    /** 后缀 → 值；{@link #fromBytes} 失败时保持空表，{@link #parsed} 为 false，handler 因此丢弃整包。 */
    private Map<String, Double> changes = Collections.emptyMap();
    /**
     * 解码是否完整成功。{@code count=0} 是合法的 FULL（清空），所以"空表"不能当作失败；handler 必须看这个标志，
     * 否则一条坏的 DELTA 会被当成"空过渡"写进服务端状态（audit RC-3/RC-4）。
     */
    private boolean parsed;

    public C2SRoamingChanges() {}

    private C2SRoamingChanges(int modelKey, byte mode, Map<String, Double> changes, boolean parsed) {
        this.modelKey = modelKey;
        this.mode = mode;
        this.changes = changes == null ? Collections.emptyMap() : new HashMap<>(changes);
        this.parsed = parsed;
    }

    /** 客户端 tick 构造：{@code full} 决定 mode；{@code changes} 为空且 {@code full} 时表示清空该命名空间。 */
    public static C2SRoamingChanges of(int modelKey, boolean full, @Nullable Map<String, Double> changes) {
        return new C2SRoamingChanges(modelKey, full ? S2CRoamingState.MODE_FULL : S2CRoamingState.MODE_DELTA, changes,
            true);
    }

    public int getModelKey() {
        return this.modelKey;
    }

    /** {@code true} = FULL，{@code false} = DELTA。 */
    public boolean isFull() {
        return this.mode == S2CRoamingState.MODE_FULL;
    }

    public Map<String, Double> getChanges() {
        return this.changes;
    }

    /** 解码是否完整成功；{@code false} 时 handler 必须丢弃整包。 */
    public boolean isParsed() {
        return this.parsed;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10：解码抛异常会被 FMLProxyPacket.processPacket 变成断线，所以每个长度先与 readableBytes() 比较、
        // 失败就静默丢弃；字段只在完整解析后赋值，半成品不会流到 handler。
        boolean parsedHere = false;
        int parsedKey = 0;
        byte parsedMode = S2CRoamingState.MODE_DELTA;
        Map<String, Double> parsedChanges = Collections.emptyMap();
        try {
            if (buf.readableBytes() >= HEADER_BYTES) {
                int key = buf.readInt();
                byte mode = buf.readByte();
                int count = buf.readUnsignedShort();
                if ((mode == S2CRoamingState.MODE_DELTA || mode == S2CRoamingState.MODE_FULL)
                    && count <= ModelRoamingLimits.MAX_VARIABLES_PER_MODEL) {
                    Map<String, Double> entries = readEntries(buf, count);
                    if (entries != null) {
                        parsedHere = true;
                        parsedKey = key;
                        parsedMode = mode;
                        parsedChanges = entries;
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Ignoring malformed YSM roaming-change packet", e);
        }
        this.modelKey = parsedKey;
        this.mode = parsedMode;
        this.changes = parsedChanges;
        this.parsed = parsedHere;
    }

    /**
     * 读 {@code count} 个「后缀 + float」对；缓冲不够或任一项不合规（空名、超长、控制字符、重复、值非有限）都返回
     * {@code null} = 整包作废，而不是跳过某项——跳项会让包头 count 与实际内容语义不一致。
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
        buf.writeInt(this.modelKey);
        buf.writeByte(this.isFull() ? S2CRoamingState.MODE_FULL : S2CRoamingState.MODE_DELTA);
        // 先过滤再写 count：count 在条目之前，跳过一项会让解码错位。
        Map<String, Double> written = new LinkedHashMap<>();
        if (this.changes != null) {
            for (Map.Entry<String, Double> entry : this.changes.entrySet()) {
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

    public static class Handler implements IMessageHandler<C2SRoamingChanges, IMessage> {

        @Override
        public IMessage onMessage(C2SRoamingChanges message, MessageContext ctx) {
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null || message == null || !message.parsed) {
                    return null;
                }
                final int modelKey = message.modelKey;
                final boolean full = message.isFull();
                final Map<String, Double> changes = new HashMap<>(message.changes);
                // 存档数据是普通 map、handler 跑在 Netty 线程：写操作交给服务端线程，与相邻 EEP 写入口同一约定
                // （SetModelAndTexture / C2SConfigAction）。
                DeferredWork.server(() -> apply(sender, modelKey, full, changes));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM roaming-change packet", e);
            }
            return null;
        }

        /** 服务端线程：校验主体/模型/modelKey/条目，然后写存档并广播同 mode 的权威状态。 */
        private static void apply(EntityPlayerMP sender, int modelKey, boolean full, Map<String, Double> changes) {
            try {
                if (sender.isDead || sender.worldObj == null) {
                    return;
                }
                ExtendedModelInfo info = ExtendedModelInfo.get(sender);
                if (info == null || info.getModelId() == null) {
                    return;
                }
                ResourceLocation modelId = info.getModelId();
                ConfigDeclarations declarations = ServerModelManager.configDeclarationsFor(modelId);
                if (declarations == null) {
                    // 服务端没有该模型的声明快照：未知模型，或只在客户端本地注册的模型（移植特有 LocalModelLoader /
                    // /ysmclient 场景）。没有快照就无法回答"modelKey 是不是发送者当前模型的"，按上游"服务端只接受
                    // 自己 catalog 里的模型"的形态丢弃；这是契约 §8 第 3/4 条登记的移植边界。
                    return;
                }
                if (modelKey != declarations.roamingKey(modelId.toString())) {
                    // 上游 PlayerStateHandler.validRoaming（:271-286）：modelKey 必须等于当前模型的 roaming hash。
                    return;
                }
                if (!validChanges(changes)) {
                    return;
                }
                ExtendedRoamingVariables store = ExtendedRoamingVariables.get(sender);
                if (store == null) {
                    return;
                }
                if (full) {
                    store.replace(modelKey, changes);
                    S2CRoamingState.acceptFullBaseline(sender.getUniqueID(), modelKey);
                } else {
                    // DELTA 之前必须有一条被完整接受的 FULL 建立 baseline（上游 ServerModelSession:98-119）。
                    if (!S2CRoamingState.hasFullBaseline(sender.getUniqueID(), modelKey)) {
                        return;
                    }
                    store.apply(modelKey, changes);
                }
                // 把同一 mode 的权威状态广播给跟踪者 + 自己；FULL 时收件端整命名空间替换，DELTA 时合并。
                S2CRoamingState.broadcast(sender, modelKey, full, changes);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to apply a YSM roaming change", e);
            }
        }

        /**
         * 服务端对消息内容的第二次校验（decoder 已经做过一次，这里按契约 §6.3 明文再确认）：条数 ≤64、后缀合规、
         * 值有限。任何一项失败都整包丢弃，不做部分写入。
         */
        private static boolean validChanges(Map<String, Double> changes) {
            if (changes == null || changes.size() > ModelRoamingLimits.MAX_VARIABLES_PER_MODEL) {
                return false;
            }
            for (Map.Entry<String, Double> entry : changes.entrySet()) {
                if (!ModelRoamingLimits.isAcceptableSuffix(entry.getKey()) || entry.getValue() == null
                    || !ModelRoamingLimits.isAcceptableValue(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }
}
