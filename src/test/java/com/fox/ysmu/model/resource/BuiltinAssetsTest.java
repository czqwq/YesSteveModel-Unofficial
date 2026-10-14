package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * 用**随模组发布的内置模型**当作解析层的回归夹具：它们跟着仓库走，不依赖任何下载模型，
 * 因此"手头没有可测试的模型"时也能锁住加载链。
 *
 * <p>覆盖两种格式：{@code builtin/default}（现代 {@code ysm.json} 包）、{@code builtin/misc/*}
 * （既有扁平格式，也有 {@code 4_default_controllers} 这种现代包——它必须携带控制器集，
 * 那份控制器就是角色拿武器时挥砍动作的来源）。</p>
 */
class BuiltinAssetsTest {

    private static final Path BUILTIN = Paths.get("src", "main", "resources", "assets", "ysmu", "builtin");

    @Test
    void modernBuiltinPackParsesAndIsBridgeable() throws Exception {
        Path dir = BUILTIN.resolve("default");
        assertTrue(Files.isDirectory(dir), "内置 default 包不在预期位置: " + dir.toAbsolutePath());

        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
            RawYsmModel raw = deserializer.deserialize();
            assertNotNull(raw.mainEntity.mainModel, "default 主模型几何缺失");
            assertNotNull(raw.mainEntity.armModel, "default 手臂几何缺失");
            assertFalse(raw.mainEntity.textures.isEmpty(), "default 没有贴图");
            assertFalse(raw.mainEntity.animationFiles.isEmpty(), "default 没有动画文件");
            assertFalse(raw.metadata.name.isEmpty(), "default 缺少 metadata.name");
            assertTrue(RawYsmModelAdapter.isBridgeable(raw), "default 应可桥接成 legacy ModelData");
        }
    }

    @Test
    void legacyBuiltinModelsParseAndAreBridgeable() throws Exception {
        String[] models = { "1_alex", "2_steve", "3_default_boy", "4_default_controllers", "5_qingluka",
            "6_wine_fox" };
        for (String name : models) {
            Path dir = BUILTIN.resolve("misc").resolve(name);
            assertTrue(Files.isDirectory(dir), "内置旧格式模型缺失: " + dir.toAbsolutePath());
            try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
                RawYsmModel raw = deserializer.deserialize();
                assertNotNull(raw.mainEntity.mainModel, name + " 主模型几何缺失");
                assertNotNull(raw.mainEntity.armModel, name + " 手臂几何缺失");
                assertFalse(raw.mainEntity.textures.isEmpty(), name + " 没有贴图");
                assertTrue(RawYsmModelAdapter.isBridgeable(raw), name + " 应可桥接成 legacy ModelData");
            }
        }
    }

    /**
     * 控制器必须真的被解析出来，而不只是"文件夹能打开"。{@code 4_default_controllers} 是上游那个只提供控制器的
     * 模型：它的 {@code player.post_swing} 状态机用 {@code v.swing_sword}（由 {@code swing:sword} 片段写入）
     * 推进连段，因此缺少这份文件时，拿武器的挥砍动作只剩一次变量赋值。
     */
    @Test
    void theDefaultControllersModelStillBridgesItsControllerSet() throws Exception {
        Path dir = BUILTIN.resolve("misc")
            .resolve("4_default_controllers");
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
            RawYsmModel raw = deserializer.deserialize();
            assertFalse(
                raw.mainEntity.animationControllerFiles.isEmpty(),
                "4_default_controllers 没有解出任何控制器文件——它正是靠这份文件提供挥砍连段");

            boolean hasSwordChain = false;
            for (RawYsmModel.RawAnimationControllerFile file : raw.mainEntity.animationControllerFiles) {
                if (file.controllers != null && file.controllers.containsKey("player.post_swing")) {
                    hasSwordChain = true;
                }
            }
            assertTrue(hasSwordChain, "4_default_controllers 缺少上游的 player.post_swing 挥砍状态机");
        }
    }
}
