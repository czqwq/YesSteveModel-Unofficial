package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The animation half of on-demand loading, driven through the real install path with a real pack file.
 * <p>
 * {@code ClientModelManagerDeferralTest} covers the bookkeeping - park once, install once - but not what installing
 * actually does. This does: it registers a payload whose animation map holds 艾莲·乔's real `main.animation.json` and
 * asks for the model's animations the way the engine does, then checks that the animations arrive and that the
 * controller files beside them are not merged into the set.
 * <p>
 * The second half matters because of how the install loop decides what a controller file is. It first asks by name,
 * and for anything else it parses the file and looks for an `animation_controllers` key. That parse is the one place
 * where the same JSON used to be read twice; the loop now reads it once and uses the same object for both questions,
 * and a mistake there would silently merge a controller file's contents into the model's animation set.
 */
class ClientModelManagerAnimationInstallTest {

    private static final ResourceLocation MODEL_ID = new ResourceLocation(ysmu.MODID, "animation_install_probe");

    /** The clip the installed set must contain; declared by {@link #ANIMATION_FILE} and also present in the pack. */
    private static final String REAL_ANIMATION = "idle";

    /**
     * A minimal animation document declaring {@link #REAL_ANIMATION}.
     * <p>
     * The test used to read the reference pack's own animation file and skip itself when the pack was absent - which
     * is every fresh clone and every CI run, so the install path ran no assertions there. What this test is about is
     * the install and the classification of controller files, not the parser, and the parser has its own coverage; so
     * the payload is written here and the test always runs.
     */
    private static final String ANIMATION_FILE = "{\"format_version\":\"1.8.0\",\"animations\":{\"" + REAL_ANIMATION
        + "\":{\"loop\":true,\"animation_length\":1.0,\"bones\":{}}}}";

    /** Never appears in the real pack, so finding it would prove a payload was merged that should not have been. */
    private static final String MARKER = "should_never_be_merged";

    private static final String MARKER_FILE = "{\"animations\":{\"" + MARKER
        + "\":{\"loop\":true,\"animation_length\":1.0,\"bones\":{}}}}";

    private static ResourceLocation mainId() {
        return ModelIdUtil.getMainId(MODEL_ID);
    }

    @AfterEach
    void release() {
        GeckoLibCache.getInstance()
            .getAnimations()
            .remove(mainId());
        ClientModelManager.MODELS.remove(MODEL_ID);
    }

    private static ModelData payload(Map<String, byte[]> animations) {
        // No geometry and no textures: this test is about the animation install, and an empty geometry map also keeps
        // the model out of the geometry queue, so nothing here can be published by another test's tick.
        return new ModelData(
            MODEL_ID.getResourcePath(),
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            animations);
    }

    @Test
    void aRealAnimationFileIsInstalledOnFirstRequestAndItsControllersAreNot() throws Exception {
        Map<String, byte[]> animations = new LinkedHashMap<>();
        animations.put("main.animation.json", ANIMATION_FILE.getBytes(StandardCharsets.UTF_8));
        // A controller file under a name the engine recognises without opening it. Its marker animation must not end
        // up in the model's set.
        animations.put("__ysm_controller__probe.animation_controllers", MARKER_FILE.getBytes(StandardCharsets.UTF_8));
        // A controller file under a name that is NOT recognised, so the install loop has to open it and look for the
        // `animation_controllers` key. This is the branch that used to parse the file a second time.
        animations.put(
            "extra.animation.json",
            ("{\"animation_controllers\":{},\"animations\":{\"" + MARKER
                + "\":{\"loop\":true,\"animation_length\":1.0,\"bones\":{}}}}").getBytes(StandardCharsets.UTF_8));

        ClientModelManager.registerAll(payload(animations), false);

        assertTrue(
            ClientModelManager.animationsPending(mainId()),
            "registration must park the animation payload; parsing it there is the join-time cost this removes");
        assertNullCacheEntry();

        AnimationFile installed = ClientModelManager.animationFileFor(mainId());

        assertNotNull(installed, "asking for the model's animations must install them");
        assertTrue(
            installed.animations.containsKey(REAL_ANIMATION),
            "the real animation file must have been converted, not skipped");
        assertFalse(
            installed.animations.containsKey(MARKER),
            "a file that declares animation_controllers is a controller file, whatever it is named, and its "
                + "contents must not be merged into the model's animation set");
        assertFalse(
            ClientModelManager.animationsPending(mainId()),
            "installing must clear the parked payload so later requests are a single lookup");
    }

    @Test
    void aModelWithNoAnimationFilesStaysAbsent() {
        ClientModelManager.registerAll(payload(new LinkedHashMap<>()), false);
        assertFalse(
            ClientModelManager.animationsPending(mainId()),
            "a payload with no animations has nothing to park");
        assertNullCacheEntry();
    }

    private static void assertNullCacheEntry() {
        assertFalse(
            GeckoLibCache.getInstance()
                .getAnimations()
                .containsKey(mainId()),
            "the engine's animation cache must stay empty until something asks for an animation");
    }
}
