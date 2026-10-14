package com.fox.ysmu.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.api.ModelAuthorization;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * 第 7 项：内置层免授权。上游 {@code CatalogRecord.java:32-33} 把 {@code auth} 根以外的记录一律标
 * {@code CatalogAccess.PUBLIC}，{@code ServerModelSession.java:317-322} 的 {@code canSelect} 就是
 * {@code PUBLIC || granted}，所以内置模型永不需要 grant。移植的判定接在第 6 项的登记表上
 * （{@link ServerModelManager#isBuiltinModel(ResourceLocation)}：生效来源优先级 == 内置），本类钉住它经
 * {@code ModelAuthorization.canSelect} 的**四参入口**（服务端真正调用的那个）确实生效。
 *
 * <p>这里用登记表而不是直接读契约：扫描器登记时已经把"内置根 + 契约声明"判过一次，且内置在冲突中恒胜，所以
 * "登记表里生效来源是内置"就等于"客户端拿到的这份模型是内置那份"。</p>
 *
 * <p>这些用例依赖随 jar 发布的 {@code assets/ysmu/builtin-index.json} 可读（契约缺失时
 * {@code ServerModelManager.isBuiltinOrigin} 会退回"只看扫描根并 warn 一次"，那样内置根下的未声明目录也会被算成
 * 内置——契约缺失本身是构建错误，让它红是正确的）。</p>
 */
class BuiltinModelAuthorizationTest {

    /** 故意不等于内置 default：命中的只可能是"内置免授权"分支，而不是 intrinsic default 分支。 */
    private static final ResourceLocation OTHER_DEFAULT = new ResourceLocation("ysmu", "some_other_default");

    @AfterEach
    void resetRegistry() {
        ServerModelManager.clearModelCaches();
    }

    @Test
    void aDeclaredBuiltinModelNeedsNoGrant() {
        registerFromBuiltin("default");

        assertTrue(
            ModelAuthorization.canSelect(true, noGrants(), new ResourceLocation("ysmu", "default"), OTHER_DEFAULT),
            "契约声明的内置模型在开关打开、授权表为空时仍然可选");
    }

    @Test
    void aDeclaredBuiltinModelWithAnEncodedIdNeedsNoGrant() {
        // misc/2_steve 的登记键是 _name_<hex>；客户端发回来的也是这个内部 id（AGENTS.md 记过按裸名比恒不命中的坑）。
        String internalId = ModelIdUtil.getInternalModelId("misc/2_steve");
        registerFromBuiltin(internalId);

        assertTrue(
            ModelAuthorization.canSelect(true, noGrants(), new ResourceLocation("ysmu", internalId), OTHER_DEFAULT),
            "编码过的内置 id 同样免授权");
    }

    @Test
    void anUndeclaredDirectoryInTheBuiltinRootStillNeedsAGrant() {
        // 第 10 项的要求：用户手放进 config/ysmu/builtin、契约没声明的目录不是内置模型，因此不享受免授权。
        registerFromBuiltin("user_dropped");

        assertFalse(
            ModelAuthorization.canSelect(true, noGrants(), new ResourceLocation("ysmu", "user_dropped"), OTHER_DEFAULT),
            "未声明的目录仍需 grant");
        assertTrue(
            ModelAuthorization.canSelect(true, granted(new ResourceLocation("ysmu", "user_dropped")),
                new ResourceLocation("ysmu", "user_dropped"), OTHER_DEFAULT),
            "授权之后照常可选");
    }

    @Test
    void aCustomModelStillNeedsAGrant() {
        registerFromCustom("example");

        assertFalse(ModelAuthorization.canSelect(true, noGrants(), new ResourceLocation("ysmu", "example"), OTHER_DEFAULT));
        assertTrue(
            ModelAuthorization.canSelect(true, granted(new ResourceLocation("ysmu", "example")),
                new ResourceLocation("ysmu", "example"), OTHER_DEFAULT));
    }

    // -----------------------------------------------------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------------------------------------------------

    private static void registerFromBuiltin(String modelId) {
        ServerModelManager.registerModel(modelId, info(), null,
            ServerModelManager.BUILT.resolve(modelId), ServerModelManager.BUILT);
    }

    private static void registerFromCustom(String modelId) {
        ServerModelManager.registerModel(modelId, info(), null,
            ServerModelManager.CUSTOM.resolve(modelId), ServerModelManager.CUSTOM);
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
