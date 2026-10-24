package com.fox.ysmu.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.network.message.SyncModelFiles;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * 发送侧的资产门：{@code SyncModelFiles.mayTransferAssets}——两个同步通道在把一个模型的字节交给玩家之前问的
 * 同一个问题。传统通道按 {@code CACHE_NAME_INFO} 的键问，17 通道按 {@code OpenYsmSyncInfo.getModelId()} 问，
 * 两侧拿到的都是同一个内部 id。
 *
 * <p>纯判定本身（{@code PUBLIC || 已授权}，开关关闭时一律放行，外加强制选择的例外）已由
 * {@code model/ModelAccessPolicyTest} 钉住；这里钉的是发送侧那层：两个通道持有的是<b>登记键字符串</b>
 * （扁平目录名如 {@code example}，或目录名不安全/含斜杠时的 {@code _name_<hex>}，见
 * {@code ModelIdUtil.getInternalModelId}），它必须先还原成客户端选择时用的那个 {@code ysmu:<内部 id>}
 * （{@code ModelAuthorization.allRegisteredModelIds} 的同一写法），{@code ServerModelManager.accessOf}
 * 才能解析出生效来源。发送侧真正的失败模式就是把键拼错——那样受限模型会被当成公开模型发出去。</p>
 *
 * <p>上游依据：{@code network/dispatch/ServerAssetTransfers.java:234-243} 读 {@code ServerConfig.RESTRICTED_AUTH}
 * （{@code config/ServerConfig.java:13,56-57}）之后问
 * {@code model/session/server/ServerModelSession.canRequest(modelId, AssetRef.Kind.CHUNK, restrictedAuth):205-221}；
 * 开关关闭时一切照发（默认配置下两条通道与改动前逐字节相同）。</p>
 *
 * <p>复位：{@code ServerModelManager.clearModelCaches()} 是 {@code com.fox.ysmu.model} 包内可见，本用例在
 * {@code network} 包里用不了它，因此只用本类专属的登记键（不复用别的用例用过的 {@code default}/{@code example}/
 * {@code guarded}），使先登记的来源不会影响其它测试类；登记只写内存表，不落盘。</p>
 */
class ModelAssetGateTest {

    /** 默认配置：开关关闭时没有任何模型被扣下，连服务端不认识的 id 也照发。 */
    @Test
    void withRestrictedAuthOffNothingIsWithheld() {
        registerFromAuth("gate_off_restricted");
        registerFromCustom("gate_off_public");

        assertTrue(
            SyncModelFiles.mayTransferAssets(false, noGrants(), "gate_off_restricted", false),
            "受限模型在默认配置下照发");
        assertTrue(SyncModelFiles.mayTransferAssets(false, noGrants(), "gate_off_public", false));
        assertTrue(
            SyncModelFiles.mayTransferAssets(false, null, "gate_off_restricted", false),
            "登记表缺失（EEP 没挂上）也不改变开关关闭时的行为");
        assertTrue(
            SyncModelFiles.mayTransferAssets(false, noGrants(), "gate_never_seen", false),
            "开关关闭时这一问对每个模型都是 true，这正是默认配置与改动前等价的原因");
    }

    /** 开关打开：被扣下的恰好是"受限 + 未授权 + 非强制选择"的那一类。 */
    @Test
    void withRestrictedAuthOnOnlyRestrictedUngrantedModelsAreWithheld() {
        registerFromAuth("gate_on_restricted");
        registerFromCustom("gate_on_public");

        assertFalse(
            SyncModelFiles.mayTransferAssets(true, noGrants(), "gate_on_restricted", false),
            "受限且未授权：扣下（整个包体既不发 RequestLoadModel 也不发字节）");
        assertTrue(
            SyncModelFiles.mayTransferAssets(true, granted("gate_on_restricted"), "gate_on_restricted", false),
            "已授权：照发（ServerModelSession.java:216）");
        assertTrue(
            SyncModelFiles.mayTransferAssets(true, noGrants(), "gate_on_public", false),
            "公开模型与授权无关（CatalogAccess.PUBLIC 那一支）");
        assertTrue(
            SyncModelFiles.mayTransferAssets(true, noGrants(), "gate_on_restricted", true),
            "强制选择例外：管理员用 /ysm model set ... true 设定的模型必须能到达该玩家（ServerModelSession.java:220）");
        assertTrue(
            SyncModelFiles.mayTransferAssets(true, null, "gate_on_restricted", false),
            "登记表缺失是接线故障，不是\"没有授权\"：与 canSelect 同一侧处理，不因接线错误把受限模型全部扣死");
    }

    /**
     * 发送侧真正传进来的 id 是登记键：目录名不安全时它是 {@code _name_<hex>}，这一层必须与登记表用同一个键，
     * 否则 {@code accessOf} 查不到生效来源，受限模型会被当成公开模型发出去。
     */
    @Test
    void theRegistryKeySpellingOfAnEncodedIdResolvesTheSameSource() {
        String authId = ModelIdUtil.getInternalModelId("Gate/Encoded Auth");
        String publicId = ModelIdUtil.getInternalModelId("Gate/Encoded Public");
        assertTrue(authId.startsWith("_name_"), "目录名不安全时内部 id 是编码形式：" + authId);
        assertTrue(publicId.startsWith("_name_"), "同一个编码规则：" + publicId);
        registerFromAuth(authId);
        registerFromCustom(publicId);

        assertFalse(
            SyncModelFiles.mayTransferAssets(true, noGrants(), authId, false),
            "编码过的 auth 模型同样被扣下——accessOf 走的是同一个登记键");
        assertTrue(
            SyncModelFiles.mayTransferAssets(true, granted(authId), authId, false),
            "授权表的键与发送侧的键是同一个内部 id（ysmu:<内部 id>）");
        assertTrue(
            SyncModelFiles.mayTransferAssets(true, noGrants(), publicId, false),
            "编码过的 custom 模型是公开的");
    }

    /** 同一个 id 的生效来源换人后，发送侧立刻跟着换：门按生效来源判，不按上次问过的答案。 */
    @Test
    void theGateFollowsTheWinningSourceWhenTheSameIdMovesIntoTheAuthRoot() {
        String id = "gate_moved_model";
        registerFromCustom(id);
        assertTrue(SyncModelFiles.mayTransferAssets(true, noGrants(), id, false), "custom 里的它是公开的");

        // auth 优先级 1 接过 custom 的 2（ReloadableModelCatalog.rootPriority:636-642）。
        registerFromAuth(id);
        assertFalse(
            SyncModelFiles.mayTransferAssets(true, noGrants(), id, false),
            "同名候选进入 auth 根后，同一个 id 对未授权玩家立刻不可外发");
    }

    // -----------------------------------------------------------------------------------------------------------
    // 夹具（与 ModelAccessPolicyTest 同一套：只登记来源，不碰磁盘）
    // -----------------------------------------------------------------------------------------------------------

    private static void registerFromCustom(String modelId) {
        ServerModelManager.registerModel(modelId, info(), null,
            ServerModelManager.CUSTOM.resolve(modelId), ServerModelManager.CUSTOM);
    }

    private static void registerFromAuth(String modelId) {
        ServerModelManager.registerModel(modelId, info(), null,
            ServerModelManager.AUTH.resolve(modelId), ServerModelManager.AUTH);
    }

    private static ServerModelInfo info() {
        Set<String> textures = new LinkedHashSet<>();
        textures.add("skin.png");
        ServerModelInfo info = new ServerModelInfo(textures, Type.FOLDER);
        info.setMd5("00000000000000000000000000000000");
        return info;
    }

    private static Set<ResourceLocation> noGrants() {
        return Collections.emptySet();
    }

    private static Set<ResourceLocation> granted(String internalModelId) {
        Set<ResourceLocation> grants = new LinkedHashSet<>();
        grants.add(new ResourceLocation("ysmu", internalModelId));
        return grants;
    }
}
