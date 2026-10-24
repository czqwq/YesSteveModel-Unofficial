package com.fox.ysmu.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.api.ModelAuthorization;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.catalog.CatalogAccess;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * 生效来源决定访问级别，以及这个级别如何进入选择判定与资产门。
 *
 * <p>上游依据：{@code CatalogRecord.java:32-33} 把 {@code auth} 根以外的记录一律标 {@code CatalogAccess.PUBLIC}，
 * 只有 auth 根是 {@code AUTHORIZED}；{@code ServerModelSession.canSelect:317-322} 是 {@code PUBLIC || granted}；
 * {@code canRequest:205-221} 是资产那半边（{@code !restrictedAuth || PUBLIC || granted}，外加强制选择的例外）。</p>
 *
 * <p>本类替换掉旧的 {@code BuiltinModelAuthorizationTest}：那一个钉的是"全局开关打开时内置模型免授权"，而开关已随
 * 自创机制删除（见 {@code .agent/phase53-model-authorization.md}）。现在没有"打开"这一步——"要授权"这件事本身
 * 由模型所在根决定。</p>
 *
 * <p>这些用例依赖随 jar 发布的 {@code assets/ysmu/builtin-index.json} 可读（契约缺失时
 * {@code ServerModelManager.isBuiltinOrigin} 会退回"只看扫描根并 warn 一次"——契约缺失本身是构建错误，让它红是
 * 正确的）。</p>
 */
class ModelAccessPolicyTest {

    @AfterEach
    void resetRegistry() {
        ServerModelManager.clearModelCaches();
    }

    // -----------------------------------------------------------------------------------------------------------
    // 访问级别来自生效来源
    // -----------------------------------------------------------------------------------------------------------

    @Test
    void aModelFromAnyPublicRootNeedsNoGrant() {
        registerFromBuiltin("default", "builtin-md5");
        registerFromCustom("example");

        for (String id : new String[] { "default", "example" }) {
            ResourceLocation model = new ResourceLocation("ysmu", id);
            assertEquals(CatalogAccess.PUBLIC, ServerModelManager.accessOf(model), id);
            assertFalse(ModelAuthorization.requiresAuth(model), id);
            assertTrue(ModelAuthorization.canSelect(noGrants(), model, CatalogAccess.PUBLIC), id);
        }
    }

    @Test
    void aModelFromTheAuthRootNeedsAGrant() {
        String internalId = ModelIdUtil.getInternalModelId("misc/2_steve");
        registerFromAuth(internalId, "auth-md5");
        ResourceLocation model = new ResourceLocation("ysmu", internalId);

        assertEquals(CatalogAccess.AUTHORIZED, ServerModelManager.accessOf(model), "编码过的 id 同样按 auth 根判定");
        assertTrue(ModelAuthorization.requiresAuth(model));
        assertFalse(ModelAuthorization.canSelect(noGrants(), model, CatalogAccess.AUTHORIZED), "空授权表 = 不可选");
        assertTrue(
            ModelAuthorization.canSelect(granted(model), model, CatalogAccess.AUTHORIZED),
            "授权之后可选");
        assertTrue(
            ModelAuthorization.canSelect(null, model, CatalogAccess.AUTHORIZED),
            "登记表缺失（构建没挂上 EEP）时不能因为一个接线故障把所有人挡在外面");
    }

    @Test
    void accessFollowsTheWinningSourceAfterAConflict() {
        // 生效来源换人（内置压过 auth），访问级别跟着换：同一个 id 回到 PUBLIC。用契约声明的内置 id，因为内置根里
        // 未被声明的目录按 CUSTOM 优先级参与裁决（第 10 项），那种情况下 auth 仍然赢，测不到本命题。
        registerFromAuth("default", "auth-md5");
        assertTrue(ModelAuthorization.requiresAuth(ExtendedModelInfo.defaultModelId()));

        registerFromBuiltin("default", "builtin-md5");
        assertFalse(ModelAuthorization.requiresAuth(ExtendedModelInfo.defaultModelId()));
    }

    @Test
    void anUnknownModelIsTreatedAsPublic() {
        // 服务端没有它的来源，就无从说它受限；客户端本地模型（/ysmclient load）走这条。
        assertEquals(CatalogAccess.PUBLIC, ServerModelManager.accessOf(new ResourceLocation("ysmu", "never_seen")));
        assertFalse(ModelAuthorization.requiresAuth(null));
    }

    @Test
    void theIntrinsicDefaultIsNeverGatedEvenWhenItLivesInTheAuthRoot() {
        // 上游无条件接受 Selection.IntrinsicDefault（ServerModelSession.java:153-156）并由
        // ModelSelectionService:15-31 无授权解析它。它是每次拒绝后回退的目标，把它挡住会让回退死锁。
        String defaultId = ExtendedModelInfo.defaultModelId().getResourcePath();
        registerFromAuth(defaultId, "auth-md5");

        assertEquals(CatalogAccess.AUTHORIZED, ServerModelManager.accessOf(ExtendedModelInfo.defaultModelId()));
        assertTrue(ModelAuthorization.canSelect(noGrants(), ExtendedModelInfo.defaultModelId(), CatalogAccess.AUTHORIZED));
    }

    // -----------------------------------------------------------------------------------------------------------
    // 资产门（上游 canRequest:205-221）
    // -----------------------------------------------------------------------------------------------------------

    @Test
    void withRestrictedAuthOffEveryAssetIsTransferable() {
        registerFromAuth("guarded", "auth-md5");
        ResourceLocation model = new ResourceLocation("ysmu", "guarded");

        assertTrue(ModelAuthorization.canRequest(false, noGrants(), model, false));
        assertTrue(
            ModelAuthorization.canRequest(false, null, model, false),
            "默认配置下资产可共享，这正是所有客户端都能画出别人穿的模型的前提");
    }

    @Test
    void withRestrictedAuthOnOnlyAPublicOrGrantedOrForcedModelIsTransferable() {
        registerFromAuth("guarded", "auth-md5");
        registerFromCustom("example");
        ResourceLocation guarded = new ResourceLocation("ysmu", "guarded");
        ResourceLocation example = new ResourceLocation("ysmu", "example");

        assertFalse(ModelAuthorization.canRequest(true, noGrants(), guarded, false), "未授权的受限模型不外发");
        assertTrue(ModelAuthorization.canRequest(true, granted(guarded), guarded, false), "有授权就能拿");
        assertTrue(ModelAuthorization.canRequest(true, noGrants(), example, false), "公开模型照常外发");
        assertTrue(
            ModelAuthorization.canRequest(true, noGrants(), guarded, true),
            "上游的 CHUNK 例外：管理员强制设定的模型必须能到达那个玩家（ServerModelSession.java:220）");
        assertTrue(
            ModelAuthorization.canRequest(true, null, guarded, false),
            "登记表缺失 = 接线故障：与 canSelect 同一侧处理，不让一个接线错误把受限模型全部挡死");
    }

    // -----------------------------------------------------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------------------------------------------------

    private static void registerFromBuiltin(String modelId, String md5) {
        ServerModelInfo info = info();
        info.setMd5(md5);
        ServerModelManager.registerModel(modelId, info, null,
            ServerModelManager.BUILT.resolve(modelId), ServerModelManager.BUILT);
    }

    private static void registerFromCustom(String modelId) {
        ServerModelManager.registerModel(modelId, info(), null,
            ServerModelManager.CUSTOM.resolve(modelId), ServerModelManager.CUSTOM);
    }

    private static void registerFromAuth(String modelId, String md5) {
        ServerModelInfo info = info();
        info.setMd5(md5);
        ServerModelManager.registerModel(modelId, info, null,
            ServerModelManager.AUTH.resolve(modelId), ServerModelManager.AUTH);
    }

    private static ServerModelInfo info() {
        Set<String> textures = new LinkedHashSet<>();
        textures.add("skin.png");
        return new ServerModelInfo(textures, Type.FOLDER);
    }

    private static Set<ResourceLocation> noGrants() {
        return Collections.emptySet();
    }

    private static Set<ResourceLocation> granted(ResourceLocation modelId) {
        Set<ResourceLocation> grants = new LinkedHashSet<>();
        grants.add(modelId);
        return grants;
    }
}
