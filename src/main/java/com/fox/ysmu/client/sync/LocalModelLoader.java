package com.fox.ysmu.client.sync;

import java.io.File;
import java.util.Map;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import org.apache.commons.io.FileUtils;

/**
 * 客户端本地模型加载（仅自己可见）。
 *
 * 由客户端命令 /ysmclient load 触发（{@code CommandYsmClient}），并在客户端 init 阶段由
 * {@code ClientProxy#startLocalModelRegistration} 先跑一次；把客户端本地扫描到的模型
 * （config/ysmu/custom + builtin，由 {@code ServerModelManager.reloadPacks()} 在客户端也构建）
 * 注册为客户端模型，仅自己可见——其他玩家没有这些模型，看到你仍为默认模型。这也是连上一个
 * **没有装 YSMU 的服务器**时唯一的模型来源：对端不会发任何同步包，客户端能画的只有自己这份目录。
 *
 * 隔离性：
 * - 完全复用真实同步的「转码/解密/解析/apply」路径
 *   （{@link OpenYsmModelSyncClient#registerLocalModel(OpenYsmSyncInfo, byte[])}），不经过网络；
 * - 已经有**可构建内容**的模型（已发布或已停放）跳过不覆盖，避免本地/服务端同名版本混淆；
 * - 本地注册与真实同步通过 OpenYsmModelSyncClient 的类锁隔离，绝不污染同步状态；
 * - 懒加载通过 {@code readClientCacheToClearBytes} 的本地密钥回退保持可用（不白模）。
 *
 * <p><b>跳过条件问的是「这个客户端手上还有没有可构建的负载」，不是「这个 id 在不在
 * {@link ClientModelManager#MODELS} 里」。</b>两者在正常路径上等价，但在一个方向上不等价，而那个方向正是本方法
 * 唯一有用的地方：连接边界（{@code ClientModelManager#clearConnectionState}）会把对端投递的负载状态丢掉，而
 * {@code MODELS} 是渲染线程读的普通 HashMap、由下一次同步在客户端线程上整体清掉——所以「还在列表里、但已经没有
 * 任何可构建负载」是一个真实状态。{@code ClientModelManager#ensureGeometry} 把「没有 PENDING_GEOMETRY 条目」读作
 * 「这个 id 我根本不认识，别再等」，因此那种模型永远不会再被构建、每帧都退到内置兜底；按 id 跳过的旧条件又让
 * 本方法帮不上忙（重新注册才是让它重新可画的唯一途径）。上游对应物是目录索引保持可重新打开的背衬
 * （{@code CatalogIndexSnapshot}）加「显式 retry/reload 才给被拒内容新的尝试资格」
 * （{@code docs/architecture/model-management/failure-and-recovery.md:13,28}），这里就是「凡是没有可构建内容的
 * 都重新登记一次」；与被拒模型的对应关系由 {@code clearConnectionState} 对 {@code GEOMETRY_FAILED} 的处理承担。
 */
@SideOnly(Side.CLIENT)
public final class LocalModelLoader {

    private LocalModelLoader() {}

    /**
     * 把本地扫描索引中的模型全部注册为客户端模型（后台线程，渐进式）。
     *
     * @return int[3] = { 新注册数, 已存在跳过数, 失败数 }
     */
    public static int[] registerLocalModels() {
        int registered = 0, skipped = 0, failed = 0;
        Map<String, OpenYsmSyncInfo> index = ServerModelManager.OPEN_YSM_SYNC_INFO;
        if (index == null || index.isEmpty()) {
            ysmu.LOG.info("[YSMU-LOCAL] No locally scanned models to register");
            return new int[] { 0, 0, 0 };
        }
        for (OpenYsmSyncInfo info : index.values()) {
            // 已经有可构建内容的模型（服务器同名模型 / default / 之前已加载的本地模型）跳过：重新登记只会让
            // 停放负载与贴图再走一遍，还可能让同名的本地版本顶掉对端已经发布的那一份。
            ResourceLocation mainId = ModelIdUtil.getMainId(new ResourceLocation(ysmu.MODID, info.getModelId()));
            if (ClientModelManager.isModelAvailable(mainId)) {
                skipped++;
                continue;
            }
            File serverCache = ServerModelManager.CACHE_SERVER.resolve(info.getCacheFileName())
                .toFile();
            if (!serverCache.isFile()) {
                failed++;
                continue;
            }
            try {
                byte[] serverBytes = FileUtils.readFileToByteArray(serverCache);
                if (OpenYsmModelSyncClient.registerLocalModel(info, serverBytes)) {
                    registered++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                ysmu.LOG.warn("[YSMU-LOCAL] Failed to register local model {}: {}",
                    info.getModelId(), e.getMessage());
            }
        }
        ysmu.LOG.info("[YSMU-LOCAL] Local model load complete: registered={}, skipped={}, failed={}",
            registered, skipped, failed);
        return new int[] { registered, skipped, failed };
    }
}
