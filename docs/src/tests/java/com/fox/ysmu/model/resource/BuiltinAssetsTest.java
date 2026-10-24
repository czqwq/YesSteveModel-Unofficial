package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * 用**随模组发布的内置模型**当作解析层的回归夹具：它们跟着仓库走，不依赖任何下载模型，
 * 因此"手头没有可测试的模型"时也能锁住加载链。
 *
 * <p>覆盖两种格式，两种都由 {@link #builtinModelDirs(Path)} 按目录实际内容扫出来，而不是在这里
 * 维护一份名字清单：现代 {@code ysm.json} 包（{@code builtin/default}、{@code builtin/misc/1_alex}
 * …）、旧扁平布局（{@code main.json}+{@code arm.json}+{@code *.png} 同层，例如
 * {@code builtin/misc/5_qingluka}）。{@code 4_default_controllers} 另有一条自己的断言——它必须携带
 * 控制器集，那份控制器就是角色拿武器时挥砍动作的来源。</p>
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

    /**
     * **目录扫描式**断言：{@code assets/ysmu/builtin/**} 里实际存在的每一个模型都必须能解析并桥接。
     * 名字清单在这里没有意义——内置集合会随上游变动（本仓就比上游 {@code builtin/misc} 多
     * {@code 5_qingluka}、{@code 6_wine_fox} 两个），硬编码清单只会让"资产加了但没验证"或
     * "清单过时"两种失败轮流出现。
     *
     * <p>扫描规则照抄加载链自己的认领规则，不另写一套：{@code OpenYsmFormat.cacheAllModels:43-58}
     * （目录有 {@code ysm.json} 且 {@link YSMFolderDeserializer#isModelFolder} 成立就认领）、
     * {@code FolderFormat.cacheAllModels:63-81}（旧扁平布局按 {@code main.json}+{@code arm.json}
     * 认领）、判定谓词本身 {@code YSMFolderDeserializer.isModelFolder:1201-1209}。两处都返回
     * {@code SKIP_SUBTREE}，这一条是必须复现的：现代包的 {@code models/} 子目录里也有
     * {@code main.json}+{@code arm.json}，不下钻跳过的话每个模型都会被数成两个。</p>
     */
    @Test
    void everyBuiltinModelUnderTheResourceTreeParsesAndIsBridgeable() throws Exception {
        List<Path> models = builtinModelDirs(BUILTIN);
        assertFalse(
            models.isEmpty(),
            "扫描 " + BUILTIN.toAbsolutePath() + " 没找到任何内置模型——扫描根或认领规则不对");

        for (Path dir : models) {
            String name = BUILTIN.relativize(dir)
                .toString()
                .replace('\\', '/');
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
     * 包根本身不是模型。{@code misc/} 带 {@code ysm-pack.json}（包根标记）和 {@code ysm-pack.png}
     * （包封面），{@code OpenYsmFormat.cacheAllModels:47-50} 与 {@code FolderFormat.cacheAllModels:57-62}
     * 都靠这个标记把它排除在模型之外——否则会多出一个与包同名的模型，并把封面当成模型贴图。
     * 扫描必须复现这条规则，所以这里单独钉住它。
     */
    @Test
    void theMiscPackRootIsNotClaimedAsAModel() throws Exception {
        Path packRoot = BUILTIN.resolve("misc");
        assertTrue(
            Files.isRegularFile(packRoot.resolve("ysm-pack.json")),
            "内置 misc 包根缺少 ysm-pack.json: " + packRoot.toAbsolutePath());

        List<Path> models = builtinModelDirs(BUILTIN);
        assertFalse(models.contains(packRoot), "包根 misc/ 被当成了模型；它有 ysm-pack.json，只是容器");
    }

    /**
     * 按加载链的规则扫出 {@code root} 下的模型目录。
     *
     * <p>与 {@code OpenYsmFormat.cacheAllModels} / {@code FolderFormat.cacheAllModels} 同一形状：
     * 跳过包根并继续下钻（包根是容器，里面的模型要照常登记），认领一个目录后整棵子树不再看。
     * 返回顺序按相对路径排序，失败信息才稳定。</p>
     */
    private static List<Path> builtinModelDirs(Path root) throws IOException {
        List<Path> models = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (Files.isRegularFile(dir.resolve("ysm-pack.json"))) {
                    return FileVisitResult.CONTINUE;
                }
                if (!YSMFolderDeserializer.isModelFolder(dir)) {
                    return FileVisitResult.CONTINUE;
                }
                models.add(dir);
                return FileVisitResult.SKIP_SUBTREE;
            }
        });
        models.sort(Comparator.comparing(Path::toString));
        return models;
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
