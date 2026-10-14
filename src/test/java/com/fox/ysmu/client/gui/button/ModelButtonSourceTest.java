package com.fox.ysmu.client.gui.button;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.format.BuiltinModelIndex;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * The source row of the model tile's hover tooltip: a model the shipped contract declares is built-in, everything else
 * arrived from the server.
 *
 * <p>There is deliberately no "local" value: on a client, the only model read off its own disk is the built-in tree,
 * which is the built-in case, and this port keeps no per-model local/server registry to read a third state from.</p>
 */
class ModelButtonSourceTest {

    private static final String BUILTIN_KEY = "gui.yes_steve_model.model.source.builtin";
    private static final String SERVER_KEY = "gui.yes_steve_model.model.source.server";

    @Test
    void aContractDeclaredModelIsBuiltInAndEverythingElseCameFromTheServer() {
        // Non-vacuity: without the shipped contract every model would answer "server" and this test would pass for the
        // wrong reason, so fail loudly if the contract is not on the test classpath.
        assertFalse(
            BuiltinModelIndex.declaredInternalIds()
                .isEmpty(),
            "内置契约不在测试 classpath 上——processResources 没把它拷进 build/resources，"
                + "或 BuiltinModelIndex.RESOURCE_PATH 写错了");

        ResourceLocation builtin = new ResourceLocation("ysmu", ModelIdUtil.getInternalModelId("default"));
        assertTrue(
            BuiltinModelIndex.declaredInternalIds()
                .contains(builtin.getResourcePath()),
            "契约应声明 default，实际声明：" + BuiltinModelIndex.declaredModelPaths());
        assertEquals(BUILTIN_KEY, ModelButton.sourceKeyFor(builtin));

        ResourceLocation synced = new ResourceLocation("ysmu", ModelIdUtil.getInternalModelId("test/not_builtin"));
        assertFalse(
            BuiltinModelIndex.declaredInternalIds()
                .contains(synced.getResourcePath()));
        assertEquals(SERVER_KEY, ModelButton.sourceKeyFor(synced));

        // No id at all is not built-in either; the tooltip then still has its source row rather than none.
        assertEquals(SERVER_KEY, ModelButton.sourceKeyFor(null));
    }

    /**
     * The keys {@code ModelButton.sourceKeyFor} returns must exist in both language files - a source row that resolves
     * to a missing key would render the raw key on the tooltip.
     */
    @Test
    void bothLanguageFilesDeclareBothSourceKeys() throws Exception {
        Path lang = Paths.get("src", "main", "resources", "assets", "ysmu", "lang");
        for (String file : List.of("en_US.lang", "zh_CN.lang")) {
            String content = new String(Files.readAllBytes(lang.resolve(file)), StandardCharsets.UTF_8);
            assertTrue(content.contains(BUILTIN_KEY + "="), file + " 里没有 " + BUILTIN_KEY);
            assertTrue(content.contains(SERVER_KEY + "="), file + " 里没有 " + SERVER_KEY);
        }
    }
}
