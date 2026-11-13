package com.fox.ysmu.client.debug;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.store.AnimationStore;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetModelAndTexture;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Developer-only auto-capture harness: launches a flat creative world, selects one or more models, takes third-person
 * and first-person screenshots and dumps the model/animation/bone state, then exits the game.
 *
 * <p><b>It is inert unless an environment variable is set</b>, so a normal launch - dev or shipped - does nothing. It
 * exists because the two things this port keeps getting wrong are visual (a body part facing the wrong way, a texture
 * wrapped instead of clamped, an animation dropped by the parser) and a screenshot shows them in one look, while
 * describing them in words takes a paragraph. The harness makes that screenshot automatic: point it at model packs with
 * environment variables, collect the PNGs and the diagnostic block from the client log.
 *
 * <p>Environment variables:
 * <ul>
 * <li>{@code YSMU_DEBUG_CAPTURE} - output directory for the PNGs; <b>unset means the harness is completely
 * disabled</b>. Screenshots land in {@code <dir>/screenshots}, the diagnostic dump in the normal client log (both the
 * {@code latest.log} and the console of {@code runClient25}).</li>
 * <li>{@code YSMU_DEBUG_MODELS} - comma-separated models to capture, each matched against a registered model's display
 * name (the decoded {@code _name_} id) or its raw path/substring. When unset, no selection is made and the world is
 * captured with whatever model is already selected (the built-in default in a fresh world).</li>
 * <li>{@code YSMU_DEBUG_TEXTURE} - optional texture name from the model's own texture list (e.g. {@code texture.png}
 * or {@code blue.png}, a prefix is enough); the model's first texture is used when unset.</li>
 * <li>{@code YSMU_DEBUG_BONES} - optional comma-separated bone names whose scale/position/rotation/hidden state is
 * dumped next to every screenshot (case sensitive, e.g. {@code beijing,mingpai}).</li>
 * </ul>
 *
 * <p>Flow: main menu -&gt; delete the harness's own save folder -&gt; create a superflat creative world -&gt; for each
 * requested model: select it, wait until the client reports it ready, take one screenshot per entry of {@link #SHOTS}
 * (front / back / wide / close-up / walking / first-person) -&gt; exit the game. Selection goes through the same pair of
 * calls the model screen uses ({@link ExtendedModelInfo#setModelAndTexture} plus the {@link SetModelAndTexture}
 * packet), so the server-side selection and its sync are exercised too; the selection is re-asserted while waiting in
 * case the login sync overwrites it.
 *
 * <p>Launching it, e.g. under PowerShell:
 * <pre>{@code
 * $env:YSMU_DEBUG_CAPTURE='E:\ysmu-shot'
 * $env:YSMU_DEBUG_MODELS='e4ba8ce998b6e5a082'   # substring of the model id / its display name
 * $env:YSMU_DEBUG_BONES='beijing'
 * .\gradlew.bat runClient25
 * }</pre>
 * The harness exits the game itself when it is done, so the Gradle task returns on its own. The variables only affect
 * that process; run {@code Remove-Item Env:YSMU_DEBUG_CAPTURE} (or open a new shell) for a normal launch.
 */
public final class ModelDebugCapture {

    private static final String ENV_DIR = "YSMU_DEBUG_CAPTURE";
    private static final String ENV_MODELS = "YSMU_DEBUG_MODELS";
    private static final String ENV_TEXTURE = "YSMU_DEBUG_TEXTURE";
    private static final String ENV_BONES = "YSMU_DEBUG_BONES";

    /** The save folder the harness owns, so a run never touches a human's world. */
    private static final String WORLD_FOLDER = "ysmu_debug_capture";
    private static final long WORLD_SEED = 12345L;

    /**
     * One camera setup: {@code {thirdPersonView, pitch, fov, walkTicks}}. {@code walkTicks} holds the forward key for
     * that long before the shot, which is what makes a walk/run animation show up in a still; {@code 0} is idle.
     */
    private static final int[][] SHOTS = { { 2, 8, 70, 0 }, // third person, front
        { 1, 8, 70, 0 }, // third person, back
        { 2, 0, 110, 0 }, // third person, wide (fits a large model / background geometry)
        { 2, 15, 40, 0 }, // third person, close-up
        { 2, 8, 70, 20 }, // walking, still moving when the shot is taken
        { 0, 0, 70, 0 } }; // first person (arm / held item)

    /**
     * Safety net: a run that cannot reach the world or a model exits instead of hanging the Gradle task forever. It is
     * deliberately generous, because the login model-sync storm is not: a cold client receives the whole catalogue at
     * once and installs it at a bounded rate, so the first requested model can sit behind a five-minute queue (a real
     * run logged an on-demand build reporting {@code pending=141}) - the second model of the same run is then ready in
     * seconds. Every wait is logged every ten seconds, so a stuck run is visible in the log rather than silent.
     */
    private static final int MAX_TICKS = 20 * 1800;

    /** How long one requested model may take to become drawable (cold sync included) before the harness gives up on it. */
    private static final int MODEL_READY_TIMEOUT_TICKS = 20 * 600;

    private final File outputDir;
    private final String[] models;
    private final String texture;
    private final String[] bones;
    private final Minecraft mc = Minecraft.getMinecraft();

    private int state;
    private int ticks;
    private int modelIndex;
    private int modelWait;
    private int shotIndex;
    private int shotPhase;
    private int shotWait;

    private ResourceLocation modelId;
    private ResourceLocation mainId;

    private ModelDebugCapture(File outputDir, String[] models, String texture, String[] bones) {
        this.outputDir = outputDir;
        this.models = models;
        this.texture = texture;
        this.bones = bones;
    }

    /** Registers the harness if {@code YSMU_DEBUG_CAPTURE} is set; does nothing otherwise. */
    public static void startIfEnabled() {
        String dir = System.getenv(ENV_DIR);
        if (dir == null || dir.trim()
            .isEmpty()) {
            return;
        }
        File outputDir = new File(dir.trim());
        // noinspection ResultOfMethodCallIgnored
        outputDir.mkdirs();
        String[] models = splitEnv(ENV_MODELS);
        String[] bones = splitEnv(ENV_BONES);
        String texture = System.getenv(ENV_TEXTURE);
        ysmu.LOG.info(
            "[YSMU-DEBUG] auto capture enabled: dir={} models={} texture={} bones={}",
            outputDir,
            models.length == 0 ? "<current selection>" : String.join(",", models),
            texture,
            bones.length == 0 ? "<none>" : String.join(",", bones));
        Minecraft.getMinecraft().gameSettings.pauseOnLostFocus = false;
        FMLCommonHandler.instance()
            .bus()
            .register(new ModelDebugCapture(outputDir, models, texture, bones));
    }

    private static String[] splitEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim()
            .isEmpty()) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.trim()
                .isEmpty()) {
                parts.add(part.trim());
            }
        }
        return parts.toArray(new String[0]);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            tick();
        } catch (Throwable failure) {
            ysmu.LOG.error("[YSMU-DEBUG] harness failed, exiting", failure);
            exitGame();
        }
    }

    private void tick() {
        // A screen opening on its own (the loading overlay, chat, ...) would freeze the capture; keep the world visible.
        if (mc.theWorld != null && mc.thePlayer != null && mc.currentScreen != null) {
            mc.displayGuiScreen(null);
        }
        ticks++;
        if (ticks > MAX_TICKS) {
            ysmu.LOG.warn("[YSMU-DEBUG] timeout after {} ticks, exiting", ticks);
            exitGame();
            return;
        }
        switch (state) {
            case 0:
                if (mc.currentScreen != null && mc.theWorld == null) {
                    deleteWorldFolder();
                    WorldSettings settings = new WorldSettings(
                        WORLD_SEED,
                        WorldSettings.GameType.CREATIVE,
                        false,
                        false,
                        WorldType.FLAT).enableCommands();
                    ysmu.LOG.info("[YSMU-DEBUG] creating the flat capture world");
                    mc.launchIntegratedServer(WORLD_FOLDER, "YSMU Debug Capture", settings);
                    state = 1;
                    ticks = 0;
                }
                break;
            case 1:
                if (mc.theWorld != null && mc.thePlayer != null) {
                    enterCaptureCamera();
                    startModel();
                    state = 2;
                    ticks = 0;
                }
                break;
            case 2:
                waitForModel();
                break;
            case 3:
                takeShots();
                break;
            default:
                break;
        }
    }

    private void waitForModel() {
        if (models.length == 0) {
            // Nothing was requested: give the world a moment to settle and capture the current selection.
            if (ticks >= 100) {
                dumpState();
                enterShots();
            }
            return;
        }
        if (modelId == null) {
            // The catalogue fills in asynchronously after login - the client's own folder scan runs on a worker and the
            // server's model sync arrives after that - so a fresh world can legitimately have zero models in it for a
            // while. Keep asking instead of treating "not there yet" as "not requested".
            modelWait++;
            if (ticks % 20 == 0) {
                ResourceLocation found = findModel(models[modelIndex]);
                if (found != null) {
                    modelId = found;
                    mainId = ModelIdUtil.getMainId(found);
                    ysmu.LOG.info(
                        "[YSMU-DEBUG] capturing model '{}' -> {} (display name '{}')",
                        models[modelIndex],
                        modelId,
                        ModelIdUtil.getModelDisplayName(modelId));
                    selectModel(modelId);
                    ticks = 0;
                } else {
                    ysmu.LOG.info(
                        "[YSMU-DEBUG] waiting for '{}': the client catalogue has {} model(s)",
                        models[modelIndex],
                        ClientModelManager.MODELS.size());
                }
            }
            // A name that never shows up once the catalogue is populated is a typo, not a slow login: skip it rather
            // than spending the whole run on it.
            if (modelWait > 20 * 60 && !ClientModelManager.MODELS.isEmpty()) {
                ysmu.LOG.error("[YSMU-DEBUG] no registered model matches '{}', skipping it", models[modelIndex]);
                modelIndex++;
                startModel();
                ticks = 0;
            } else if (ticks > MAX_TICKS) {
                ysmu.LOG.error("[YSMU-DEBUG] '{}' never appeared in the client catalogue, exiting", models[modelIndex]);
                exitGame();
            }
            return;
        }
        if (ClientModelManager.isModelReady(modelId) && ticks > 100) {
            if (!isDrawnModel()) {
                // Ready means the geometry/texture/animation target exists; the renderer installs it on its next draw and
                // only then stops falling back to the built-in default. Capture the first frame after that switch, or
                // the first screenshot shows the default model instead of the one that was asked for.
                if (ticks % 200 == 0) {
                    ysmu.LOG.info("[YSMU-DEBUG] {} is ready but not drawn yet; waiting for the switch", modelId);
                }
                return;
            }
            ysmu.LOG.info("[YSMU-DEBUG] model {} is ready and drawn, capturing", modelId);
            dumpState();
            enterShots();
            return;
        }
        if (ticks % 200 == 0) {
            ysmu.LOG.info("[YSMU-DEBUG] waiting for {} to become drawable ({}s)", modelId, ticks / 20);
        }
        if (ticks > MODEL_READY_TIMEOUT_TICKS) {
            ysmu.LOG.error("[YSMU-DEBUG] model {} never became ready, skipping it", modelId);
            modelFinished();
            return;
        }
        if (ticks % 20 == 0) {
            // The login sync writes the server's selection into the client EEP; keep re-asserting ours.
            ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
            if (eep == null || !modelId.equals(eep.getModelId())) {
                ysmu.LOG.info(
                    "[YSMU-DEBUG] selection was overwritten ({}), re-applying",
                    eep == null ? "no-eep" : eep.getModelId());
                selectModel(modelId);
            }
        }
    }

    /**
     * Whether the world renderer has actually installed the selected model on the local player, i.e. the next frame
     * draws it rather than the built-in default fallback ({@code AppliedModelRegistry#applied} is written by
     * {@code CustomPlayerRenderer} once the geometry is resolved).
     */
    private boolean isDrawnModel() {
        if (mainId == null) {
            return false;
        }
        com.fox.ysmu.client.render.AppliedModelRegistry.Applied applied = com.fox.ysmu.client.render.AppliedModelRegistry
            .applied(mc.thePlayer);
        return applied != null && mainId.equals(applied.mainModelId());
    }

    private void enterShots() {
        shotIndex = 0;
        shotPhase = 0;
        ticks = 0;
        state = 3;
    }

    private void takeShots() {
        if (shotPhase == 0) {
            applyCamera(SHOTS[shotIndex]);
            shotPhase = 1;
            ticks = 0;
            return;
        }
        if (shotPhase == 1 && ticks >= shotWait) {
            shotPhase = 2;
            ticks = 0;
            return;
        }
        if (shotPhase == 2 && ticks >= 1) {
            saveShot();
        }
    }

    private void applyCamera(int[] shot) {
        boolean walking = shot[3] > 0;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), walking);
        // Re-asserted per shot, not just once on world entry: F1 toggles hideGUI, and a harness run that happens to
        // catch a stray F1 should still produce HUD-free frames. pauseOnLostFocus likewise, in case the window is not
        // focused while the automation runs.
        mc.gameSettings.hideGUI = true;
        mc.gameSettings.pauseOnLostFocus = false;
        mc.gameSettings.thirdPersonView = shot[0];
        mc.gameSettings.fovSetting = shot[2];
        mc.thePlayer.rotationPitch = shot[1];
        mc.thePlayer.prevRotationPitch = shot[1];
        // Walking needs its ticks before the still is worth anything; an idle shot only needs the camera to settle.
        shotWait = walking ? shot[3] + 2 : 3;
    }

    private void saveShot() {
        int[] shot = SHOTS[shotIndex];
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        mc.gameSettings.hideGUI = true;
        String name = "model" + modelIndex + "_shot" + shotIndex + "_tpv" + shot[0] + "_fov" + shot[2]
            + (shot[3] > 0 ? "_walk" : "") + ".png";
        try {
            ScreenShotHelper.saveScreenshot(outputDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            ysmu.LOG.info("[YSMU-DEBUG] screenshot {}", name);
        } catch (Throwable failure) {
            ysmu.LOG.error("[YSMU-DEBUG] screenshot failed", failure);
        }
        dumpState();
        shotIndex++;
        shotPhase = 0;
        ticks = 0;
        if (shotIndex >= SHOTS.length) {
            modelFinished();
        }
    }

    /** Starts the next requested model; exits when every model has been captured. */
    private void startModel() {
        modelId = null;
        mainId = null;
        shotIndex = 0;
        modelWait = 0;
        if (models.length == 0) {
            return;
        }
        if (modelIndex >= models.length) {
            ysmu.LOG.info("[YSMU-DEBUG] all models captured, exiting");
            exitGame();
        }
    }

    /**
     * The registered model a {@code YSMU_DEBUG_MODELS} entry names, matched against the display name (the decoded
     * {@code _name_} id) or the raw path - a substring is enough, so a short ASCII fragment works for a Chinese folder
     * name and the environment variable never has to carry non-ASCII text.
     */
    private static ResourceLocation findModel(String wanted) {
        for (ResourceLocation id : ClientModelManager.MODELS.keySet()) {
            String display = ModelIdUtil.getModelDisplayName(id);
            String path = id.getResourcePath();
            if (display.equals(wanted) || path.equals(wanted) || display.contains(wanted) || path.contains(wanted)) {
                return id;
            }
        }
        return null;
    }

    private void selectModel(ResourceLocation id) {
        ResourceLocation textureId = resolveTexture(id);
        ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
        if (eep != null) {
            eep.setModelAndTexture(id, textureId);
        } else {
            ysmu.LOG.warn("[YSMU-DEBUG] the local player has no ExtendedModelInfo");
        }
        NetworkHandler.CHANNEL.sendToServer(new SetModelAndTexture(id, textureId));
        ysmu.LOG.info("[YSMU-DEBUG] selected model {} with texture {}", id, textureId);
    }

    private ResourceLocation resolveTexture(ResourceLocation id) {
        List<ResourceLocation> textures = ClientModelManager.MODELS.get(id);
        if (textures != null && !textures.isEmpty()) {
            if (texture != null) {
                for (ResourceLocation candidate : textures) {
                    String name = ModelIdUtil.getSubNameFromId(candidate);
                    if (name.equals(texture) || name.startsWith(texture)) {
                        return candidate;
                    }
                }
                ysmu.LOG.warn("[YSMU-DEBUG] texture '{}' is not in {}; using the first texture", texture, textures);
            }
            return textures.get(0);
        }
        return ModelIdUtil.getSubModelId(id, texture == null ? "texture" : texture);
    }

    private void modelFinished() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        mc.gameSettings.thirdPersonView = 2;
        mc.gameSettings.fovSetting = 70f;
        if (models.length == 0) {
            // No model was requested: there is exactly one "whatever is selected" capture to take.
            ysmu.LOG.info("[YSMU-DEBUG] capture of the current selection done, exiting");
            exitGame();
            return;
        }
        modelIndex++;
        startModel();
        state = 2;
        ticks = 0;
    }

    private void enterCaptureCamera() {
        mc.gameSettings.pauseOnLostFocus = false;
        mc.gameSettings.hideGUI = true;
        mc.theWorld.setWorldTime(6000L);
        // Third person from the very first tick: the client launches in first person, and in first person the world
        // renderer never draws the player's body - the harness would then wait for a model that is never asked for (and
        // the first-person arm alone does not install the full applied-model record). Every shot starts from the third
        // person state anyway, so this is also one less camera change to settle.
        mc.gameSettings.thirdPersonView = 2;
        mc.gameSettings.fovSetting = 70f;
        mc.thePlayer.rotationYaw = 0f;
        mc.thePlayer.prevRotationYaw = 0f;
        mc.thePlayer.rotationPitch = 8f;
        mc.thePlayer.prevRotationPitch = 8f;
        ysmu.LOG.info(
            "[YSMU-DEBUG] world ready at {}; third person forced; the client catalogue has {} model(s)",
            mc.thePlayer.getPosition(1f),
            ClientModelManager.MODELS.size());
    }

    /**
     * The diagnostic block that accompanies every screenshot: model readiness, the texture list the selection resolves
     * against, the animation store's parallel family (the clips that hide/show pack parts live there), and the state of
     * the bones named in {@code YSMU_DEBUG_BONES}.
     */
    private void dumpState() {
        ysmu.LOG.info(
            "[YSMU-DEBUG] ---- model={} main={} ready={} available={} ----",
            modelId,
            mainId,
            ClientModelManager.isModelReady(modelId),
            ClientModelManager.isModelAvailable(modelId));
        ysmu.LOG.info("[YSMU-DEBUG] textures={}", ClientModelManager.MODELS.get(modelId));
        ysmu.LOG.info(
            "[YSMU-DEBUG] applied={}",
            com.fox.ysmu.client.render.AppliedModelRegistry.applied(mc.thePlayer));
        AnimationStore store = ClientModelManager.animationStoreFor(mainId);
        if (store == null) {
            ysmu.LOG.info("[YSMU-DEBUG] main animation store: <null>");
        } else {
            List<String> parallelFamily = new ArrayList<>();
            for (String name : store.names()) {
                if (name.startsWith("pre_parallel") || name.startsWith("parallel")) {
                    parallelFamily.add(name);
                }
            }
            ysmu.LOG.info(
                "[YSMU-DEBUG] animation store: names={} parallelFamily={}",
                store.names()
                    .size(),
                parallelFamily);
        }
        GeoModel model = mainId == null ? null
            : GeckoLibCache.getInstance()
                .getGeoModels()
                .get(mainId);
        if (model == null) {
            ysmu.LOG.info("[YSMU-DEBUG] the geometry of {} is not in the engine cache", mainId);
            return;
        }
        for (String boneName : bones) {
            GeoBone bone = model.getBone(boneName)
                .orElse(null);
            if (bone == null) {
                ysmu.LOG.info("[YSMU-DEBUG] bone '{}' does not exist in this model", boneName);
                continue;
            }
            ysmu.LOG.info(
                "[YSMU-DEBUG] bone {} scale=({},{},{}) pos=({},{},{}) rot=({},{},{}) hidden={} cubesHidden={} cubes={}",
                boneName,
                bone.getScaleX(),
                bone.getScaleY(),
                bone.getScaleZ(),
                bone.getPositionX(),
                bone.getPositionY(),
                bone.getPositionZ(),
                bone.getRotationX(),
                bone.getRotationY(),
                bone.getRotationZ(),
                bone.isHidden(),
                bone.cubesAreHidden(),
                bone.childCubes == null ? -1 : bone.childCubes.size());
        }
    }

    private void deleteWorldFolder() {
        deleteRecursively(new File(new File(mc.mcDataDir, "saves"), WORLD_FOLDER));
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void exitGame() {
        try {
            FMLCommonHandler.instance()
                .exitJava(0, false);
        } catch (Throwable failure) {
            ysmu.LOG.error("[YSMU-DEBUG] exitJava failed", failure);
        }
    }
}
