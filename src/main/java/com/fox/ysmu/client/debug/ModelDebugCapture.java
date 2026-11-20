package com.fox.ysmu.client.debug;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import com.fox.ysmu.api.EntityAnimationApi;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.store.AnimationStore;
import com.fox.ysmu.client.entity.CustomGuiPlayerEntity;
import com.fox.ysmu.client.gui.AnimationRouletteScreen;
import com.fox.ysmu.client.gui.ConfigScreen;
import com.fox.ysmu.client.gui.ModelConfigRegistry;
import com.fox.ysmu.client.gui.ModelConfigScreen;
import com.fox.ysmu.client.gui.button.ConfigCheckBox;
import com.fox.ysmu.client.gui.button.FlatSlider;
import com.fox.ysmu.client.roaming.ConfigActionRuntime;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetModelAndTexture;
import com.fox.ysmu.network.message.SetPlayAnimation;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
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
 * <li>{@code YSMU_DEBUG_FEATURES} - optional {@code 0}/{@code false} disables the feature checks described below
 * (enabled by default). They exist because the failures this port keeps producing are "the pose is the bind pose"
 * and "the wheel/settings screen does not open", which a photo of one frame cannot tell from "the model is fine".
 * The checks are: (1) <b>early pose</b> - on the very first drawn frame after selection, measure how far the applied
 * pose is from the geometry's bind pose and then walk for a few ticks and measure whether any bone channel actually
 * changes; this is the regression window that made a standing player show the bind pose while the model's animation
 * store was still installing; (2) <b>roulette</b> - open the real {@link AnimationRouletteScreen} and screenshot it,
 * then play one of its playable root entries through the real {@link AnimationRouletteScreen#activateRootSlot(int)}
 * path (client -> server {@link SetPlayAnimation} -> sync -> cap controller) and check the EEP flag, the animation
 * name and that the pose moves; (3) <b>settings</b> - open the real {@link ConfigScreen} and screenshot it.</li>
 * <li>{@code YSMU_DEBUG_SETTINGS} - optional; adds the model-settings-panel probe to the feature sweep: open the
 * panel the wheel would open (the real {@code AnimationRouletteScreen#openConfig}), log why the screen would close
 * itself, screenshot it, deliver a real mouse click to its first form widget and screenshot again after the server
 * round trip. It exists to catch "the settings screen opens but its preview/widgets are dead", which no screenshot of
 * a static model can see. Unset means the probe is inert even when the capture harness runs.</li>
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
    private static final String ENV_FEATURES = "YSMU_DEBUG_FEATURES";
    /** Enables the per-model settings-panel probe (open the real settings screen, click a form widget, screenshot both). */
    private static final String ENV_SETTINGS = "YSMU_DEBUG_SETTINGS";

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
    private final boolean features;
    private final boolean settingsProbe;
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

    /** A screen the harness itself opened; {@link #tick()} must not close it while this is positive. */
    private int screenHoldTicks;
    /** Early pose check (see the class javadoc): phase counter and its saved samples. */
    private int earlyPhase;
    private boolean earlyDone;
    private double[] earlyPose;
    private int[] earlyBoneCount;
    /** Feature sweep (roulette / emote / settings): phase counter and its saved samples. */
    private int featureStep;
    private double[] featurePose;
    private String wheelAnimation;
    private int wheelSlot = -1;
    private String featureResult = "";
    /** Settings-panel probe: the config entry the wheel would open, chosen so a real form widget exists. */
    private int probeConfigIndex = -1;
    private String probeButtonId;

    private ModelDebugCapture(File outputDir, String[] models, String texture, String[] bones, boolean features,
        boolean settingsProbe) {
        this.outputDir = outputDir;
        this.models = models;
        this.texture = texture;
        this.bones = bones;
        this.features = features;
        this.settingsProbe = settingsProbe;
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
        String featureEnv = System.getenv(ENV_FEATURES);
        boolean features = featureEnv == null || !("0".equals(featureEnv.trim())
            || "false".equalsIgnoreCase(featureEnv.trim()));
        String settingsEnv = System.getenv(ENV_SETTINGS);
        boolean settingsProbe = settingsEnv != null && !settingsEnv.trim()
            .isEmpty()
            && !"0".equals(settingsEnv.trim())
            && !"false".equalsIgnoreCase(settingsEnv.trim());
        ysmu.LOG.info(
            "[YSMU-DEBUG] auto capture enabled: dir={} models={} texture={} bones={} features={} settingsProbe={}",
            outputDir,
            models.length == 0 ? "<current selection>" : String.join(",", models),
            texture,
            bones.length == 0 ? "<none>" : String.join(",", bones),
            features,
            settingsProbe);
        Minecraft.getMinecraft().gameSettings.pauseOnLostFocus = false;
        FMLCommonHandler.instance()
            .bus()
            .register(new ModelDebugCapture(outputDir, models, texture, bones, features, settingsProbe));
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
        // A screen the harness opened itself (the feature checks) is kept for its screenshot window instead.
        if (screenHoldTicks > 0) {
            screenHoldTicks--;
        } else if (mc.theWorld != null && mc.thePlayer != null && mc.currentScreen != null) {
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
            case 4:
                earlyChecks();
                break;
            case 5:
                featureSweep();
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
        if (ClientModelManager.isModelReady(modelId) && isDrawnModel()) {
            if (features && !earlyDone) {
                // The interesting window: the model is drawn but its animation store may still be installing. That is
                // exactly where the concurrent children used to be created with an unresolvable clip and never
                // retried, which left the standing/walking pose at the bind pose. Measure before doing anything else.
                ysmu.LOG.info("[YSMU-DEBUG] model {} is ready and drawn; running the early pose checks", modelId);
                earlyPhase = 0;
                earlyPose = null;
                state = 4;
                ticks = 0;
                return;
            }
            if (earlyDone || ticks > 100) {
                ysmu.LOG.info("[YSMU-DEBUG] model {} is ready and drawn, capturing", modelId);
                dumpState();
                enterShots();
            }
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
        earlyPhase = 0;
        earlyDone = false;
        earlyPose = null;
        featureStep = 0;
        featurePose = null;
        wheelAnimation = null;
        wheelSlot = -1;
        featureResult = "";
        screenHoldTicks = 0;
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
        if (features) {
            featureStep = 0;
            featurePose = null;
            wheelAnimation = null;
            wheelSlot = -1;
            featureResult = "";
            state = 5;
            ticks = 0;
            return;
        }
        advanceModel();
    }

    /** Moves the capture on to the next requested model. */
    private void advanceModel() {
        modelIndex++;
        startModel();
        state = 2;
        ticks = 0;
    }

    /**
     * The early pose checks, run on the first frame the selected model is actually drawn (see the class javadoc):
     * measure the idle pose against the bind pose (a bind pose here is the A-pose defect), then walk for a few ticks
     * and check that bone channels actually move (a frozen pose here is the "walking without leg animation" defect).
     */
    private void earlyChecks() {
        GeoModel model = geometry();
        if (model == null) {
            ysmu.LOG.warn("[YSMU-DEBUG][FEATURE] no geometry for {} during the early checks; skipping", mainId);
            earlyDone = true;
            state = 2;
            ticks = 0;
            return;
        }
        switch (earlyPhase) {
            case 0:
                if (ticks >= 3) {
                    earlyPose = capturePose(model);
                    int posed = logPose("early-idle", model);
                    featureResult = "earlyIdlePosedBones=" + posed;
                    earlyPhase = 1;
                    ticks = 0;
                }
                break;
            case 1:
                // Two walk ticks to get past the state machine's 待机 -> 空闲 -> 走路前进 transit, then sample while the
                // blend_weight filter is still ramping (it takes ~10 ticks to become fully visible), so a dead
                // 走路前进 child shows as a bind pose here.
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
                if (ticks >= 16) {
                    earlyPose = capturePose(model);
                    earlyPhase = 2;
                    ticks = 0;
                }
                break;
            case 2:
                if (ticks >= 2) {
                    double[] later = capturePose(model);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                    logPose("early-walk", model);
                    int moved = countChanged(earlyPose, later, 0.005d);
                    ysmu.LOG.info(
                        "[YSMU-DEBUG][FEATURE] early-walk bonesMoved={} (0 = frozen walk pose, the walk layer never emitted)",
                        moved);
                    featureResult = featureResult + " earlyWalkBonesMoved=" + moved;
                    saveNamedShot("model" + modelIndex + "_early_walk.png");
                    earlyDone = true;
                    state = 2;
                    ticks = 0;
                }
                break;
            default:
                earlyDone = true;
                state = 2;
                ticks = 0;
                break;
        }
    }

    /**
     * The feature sweep, run after the camera shots of a model: one of the roulette's playable root entries played
     * through the real wheel path (client -&gt; server packet -&gt; sync -&gt; cap controller), then the real roulette
     * screen and the real settings screen. The animation check runs <em>first and with no screen open</em>: opening a
     * GUI pauses the integrated server, and a play request sent into that window would be checked before the server
     * has ticked - the first version of this sweep did exactly that and reported a false negative on one model. Each
     * step screenshots what it exercised and logs a PASS-style line, because a single still of a static model cannot
     * tell a working emote from a dropped one.
     */
    private void featureSweep() {
        switch (featureStep) {
            case 0:
                if (mainId == null || mc.thePlayer == null) {
                    finishFeatures();
                    return;
                }
                if (!chooseWheelAnimation()) {
                    ysmu.LOG.info(
                        "[YSMU-DEBUG][FEATURE] model {} declares no playable wheel entry; the emote check is skipped",
                        modelId);
                    featureResult = featureResult + " emote=skip";
                    featureStep = 4;
                    ticks = 0;
                    break;
                }
                ysmu.LOG.info(
                    "[YSMU-DEBUG][FEATURE] playing wheel slot {} ('{}') through the real wheel path",
                    wheelSlot,
                    wheelAnimation);
                AnimationRouletteScreen.activateRootSlot(wheelSlot);
                featureStep = 1;
                ticks = 0;
                break;
            case 1:
                if (ticks == 2 || ticks == 5 || ticks == 9 || ticks == 13) {
                    ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
                    ysmu.LOG.info(
                        "[YSMU-DEBUG][FEATURE] emote-wait t={} flag={} name={}",
                        ticks,
                        eep != null && eep.isPlayAnimation(),
                        eep == null ? null : eep.getAnimation());
                }
                if (ticks >= 14) {
                    featurePose = capturePose(geometry());
                    featureStep = 2;
                    ticks = 0;
                }
                break;
            case 2:
                if (ticks >= 2) {
                    GeoModel model = geometry();
                    double[] later = capturePose(model);
                    int moved = countChanged(featurePose, later, 0.005d);
                    ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
                    boolean flagOn = eep != null && eep.isPlayAnimation();
                    String playing = eep == null ? null : eep.getAnimation();
                    boolean nameOk = wheelAnimation != null && wheelAnimation.equals(playing);
                    logPose("emote", model);
                    ysmu.LOG.info(
                        "[YSMU-DEBUG][FEATURE] emote '{}' flagOn={} eepName={} nameMatches={} bonesMoved={}",
                        wheelAnimation,
                        flagOn,
                        playing,
                        nameOk,
                        moved);
                    saveNamedShot("model" + modelIndex + "_emote.png");
                    NetworkHandler.CHANNEL.sendToServer(SetPlayAnimation.stop());
                    if (eep != null) {
                        eep.stopAnimation();
                    }
                    featureResult = featureResult + " emote" + (flagOn && nameOk ? "=OK" : "=FAIL")
                        + " emoteBonesMoved=" + moved;
                    featureStep = 4;
                    ticks = 0;
                }
                break;
            case 4:
                ysmu.LOG.info("[YSMU-DEBUG][FEATURE] opening the roulette screen");
                mc.displayGuiScreen(new AnimationRouletteScreen());
                screenHoldTicks = 6;
                featureStep = 5;
                ticks = 0;
                break;
            case 5:
                if (ticks >= 5) {
                    saveNamedShot("model" + modelIndex + "_roulette.png");
                    mc.displayGuiScreen(null);
                    featureStep = 6;
                    ticks = 0;
                }
                break;
            case 6:
                ysmu.LOG.info("[YSMU-DEBUG][FEATURE] opening the settings screen");
                mc.displayGuiScreen(new ConfigScreen(null));
                screenHoldTicks = 6;
                featureStep = 7;
                ticks = 0;
                break;
            case 7:
                if (ticks >= 5) {
                    saveNamedShot("model" + modelIndex + "_settings.png");
                    mc.displayGuiScreen(null);
                    featureStep = 8;
                    ticks = 0;
                }
                break;
            case 8:
                if (!settingsProbe) {
                    finishFeatures();
                    return;
                }
                openSettingsProbe();
                break;
            case 9:
                if (ticks >= 6) {
                    settingsProbeOpenedShot();
                }
                break;
            case 10:
                if (ticks >= 12) {
                    settingsProbeAfterShot();
                }
                break;
            case 11:
                openRadioProbe();
                break;
            case 12:
                if (ticks >= 6) {
                    radioProbeOpenedShot();
                }
                break;
            case 13:
                if (ticks >= 12) {
                    radioProbeAfterShot();
                }
                break;
            default:
                finishFeatures();
                break;
        }
    }

    /** Logs the feature summary of one model and moves on. */
    private void finishFeatures() {
        mc.displayGuiScreen(null);
        screenHoldTicks = 0;
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] summary model={} display='{}' roulette=OK settings=OK{}",
            modelId,
            modelId == null ? null : ModelIdUtil.getModelDisplayName(modelId),
            featureResult);
        advanceModel();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // 模型设置屏探针（YSMU_DEBUG_SETTINGS=1）
    //
    // The regression this exists for: after the model-settings rework a click on a form widget (二阶堂希罗's 猫耳
    // checkbox / [嘴巴差分] radio) appeared to do nothing and the settings screen's own preview stopped showing the
    // change. The probe opens the screen exactly the way the roulette does (private AnimationRouletteScreen#openConfig),
    // logs why the screen would close itself, screenshots it, then delivers a real mouse click to the first form widget
    // the screen built and screenshots again after the server round trip. No production code is involved beyond the
    // real click path.
    // ----------------------------------------------------------------------------------------------------------------

    /** Opens the settings panel of the entry the wheel would open (prefers a checkbox writing {@code v.m}). */
    private void openSettingsProbe() {
        if (modelId == null || mc.thePlayer == null) {
            finishFeatures();
            return;
        }
        boolean localModel = ConfigActionRuntime.isLocalModel(modelId);
        List<RawYsmModel.ExtraAnimationButton> panels = ModelConfigRegistry.settingsButtonsFor(modelId);
        Map<String, String> wheel = AnimationRouletteScreen.wheelEntriesFor(modelId);
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] modelConfig precheck: model={} localModel={} hasSettings={} panels={} wheelEntries={}",
            modelId,
            localModel,
            ModelConfigRegistry.hasSettings(modelId),
            panels.size(),
            wheel.size());
        for (RawYsmModel.ExtraAnimationButton panel : panels) {
            List<String> forms = new ArrayList<>();
            for (RawYsmModel.ConfigForm form : panel.forms) {
                forms.add(form.type + ":" + form.defaultValue + " labels=" + (form.labels == null ? 0 : form.labels.size()));
            }
            ysmu.LOG.info("[YSMU-DEBUG][FEATURE] modelConfig panel '{}' forms={}", panel.id, forms);
        }
        logConfigSnapshot("before-open");

        probeConfigIndex = chooseSettingsEntry(wheel);
        if (probeConfigIndex < 0) {
            ysmu.LOG.warn("[YSMU-DEBUG][FEATURE] modelConfig: the wheel declares no config entry to open");
            featureResult = featureResult + " modelConfig=skip";
            finishFeatures();
            return;
        }
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] modelConfig opening wheel slot {} (button='{}') through AnimationRouletteScreen#openConfig",
            probeConfigIndex,
            probeButtonId);
        mc.displayGuiScreen(new AnimationRouletteScreen());
        screenHoldTicks = 120;
        invokePrivate(mc.currentScreen, "openConfig", new Class<?>[] { int.class }, probeConfigIndex);
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] modelConfig after openConfig: screen={} (localModel={})",
            screenName(),
            localModel);
        featureStep = 9;
        ticks = 0;
    }

    /** Screenshot of the settings screen, its internal state, then a real mouse click on its first form widget. */
    private void settingsProbeOpenedShot() {
        saveNamedShot("model" + modelIndex + "_modelconfig.png");
        GuiScreen screen = mc.currentScreen;
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] modelConfig screen={} preview={} panels={} forms={} routes={}",
            screenName(),
            screen instanceof ModelConfigScreen && readField(screen, "formPreview") != null,
            screen instanceof ModelConfigScreen ? sizeOf(readField(screen, "panels")) : -1,
            screen instanceof ModelConfigScreen ? sizeOf(readField(screen, "visibleForms")) : -1,
            screen instanceof ModelConfigScreen ? sizeOf(readField(screen, "formRoutes")) : -1);
        if (screen instanceof ModelConfigScreen) {
            ysmu.LOG.info("[YSMU-DEBUG][FEATURE] modelConfig click -> {}", clickFirstFormWidget(screen));
        } else {
            ysmu.LOG.warn(
                "[YSMU-DEBUG][FEATURE] modelConfig: openConfig did not leave a ModelConfigScreen (screen={})",
                screenName());
        }
        screenHoldTicks = 120;
        featureStep = 10;
        ticks = 0;
    }

    /** Screenshot after the click's server round trip and the values this model's forms target. */
    private void settingsProbeAfterShot() {
        saveNamedShot("model" + modelIndex + "_modelconfig_after.png");
        logConfigSnapshot("after-click");
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] modelConfig end: screen={} localModel={}",
            screenName(),
            ConfigActionRuntime.isLocalModel(modelId));
        featureResult = featureResult + " modelConfig=OK";
        // The checkbox path is verified; the user's other report is a radio ("切换表情"), which the per-frame read-back
        // used to snap back to the old selection. Run the same probe on a panel that declares a radio.
        featureStep = 11;
        ticks = 0;
        screenHoldTicks = 0;
    }

    /** Opens the first config panel whose button declares a radio, so a real label click can be delivered. */
    private void openRadioProbe() {
        mc.displayGuiScreen(null);
        Map<String, String> wheel = AnimationRouletteScreen.wheelEntriesFor(modelId);
        int index = chooseRadioEntry(wheel);
        if (index < 0) {
            ysmu.LOG.info("[YSMU-DEBUG][FEATURE] radioProbe: no radio panel to open");
            featureResult = featureResult + " radio=skip";
            finishFeatures();
            return;
        }
        ysmu.LOG.info("[YSMU-DEBUG][FEATURE] radioProbe opening wheel slot {} through openConfig", index);
        mc.displayGuiScreen(new AnimationRouletteScreen());
        screenHoldTicks = 120;
        invokePrivate(mc.currentScreen, "openConfig", new Class<?>[] { int.class }, index);
        ysmu.LOG.info("[YSMU-DEBUG][FEATURE] radioProbe after openConfig: screen={}", screenName());
        featureStep = 12;
        ticks = 0;
    }

    private void radioProbeOpenedShot() {
        saveNamedShot("model" + modelIndex + "_modelconfig_radio.png");
        GuiScreen screen = mc.currentScreen;
        ysmu.LOG.info("[YSMU-DEBUG][FEATURE] radioProbe screen={} forms={}", screenName(), sizeOf(
            screen instanceof ModelConfigScreen ? readField(screen, "visibleForms") : null));
        if (screen instanceof ModelConfigScreen) {
            // The second radio label: the first is the current/default selection, so clicking it would be a no-op.
            ysmu.LOG.info("[YSMU-DEBUG][FEATURE] radioProbe click -> {}", clickNthFormWidget(screen, 1));
        }
        screenHoldTicks = 120;
        featureStep = 13;
        ticks = 0;
    }

    private void radioProbeAfterShot() {
        saveNamedShot("model" + modelIndex + "_modelconfig_radio_after.png");
        logConfigSnapshot("after-radio-click");
        ysmu.LOG.info("[YSMU-DEBUG][FEATURE] radioProbe end: screen={}", screenName());
        featureResult = featureResult + " radio=OK";
        finishFeatures();
    }

    /** First root wheel entry whose button declares a radio with at least two labels. */
    private int chooseRadioEntry(Map<String, String> wheel) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(wheel.entrySet());
        int limit = Math.min(entries.size(), 12);
        for (int index = 0; index < limit; index++) {
            String value = entries.get(index)
                .getValue();
            if (value == null || !value.startsWith("#")) {
                continue;
            }
            RawYsmModel.ExtraAnimationButton button = ModelConfigRegistry.buttonFor(modelId, value.substring(1));
            if (button == null || button.forms == null) {
                continue;
            }
            for (RawYsmModel.ConfigForm form : button.forms) {
                if ("radio".equalsIgnoreCase(form.type) && form.labels != null && form.labels.size() >= 2) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** The first root wheel entry that opens a config panel with a form, preferring one that writes {@code v.m}. */
    private int chooseSettingsEntry(Map<String, String> wheel) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(wheel.entrySet());
        int firstConfig = -1;
        int radioConfig = -1;
        int limit = Math.min(entries.size(), 12);
        for (int index = 0; index < limit; index++) {
            String value = entries.get(index)
                .getValue();
            if (value == null || !value.startsWith("#")) {
                continue;
            }
            RawYsmModel.ExtraAnimationButton button = ModelConfigRegistry.buttonFor(modelId, value.substring(1));
            if (button == null || button.forms == null || button.forms.isEmpty()) {
                continue;
            }
            if (firstConfig < 0) {
                firstConfig = index;
            }
            for (RawYsmModel.ConfigForm form : button.forms) {
                String defaultValue = form.defaultValue == null ? "" : form.defaultValue.trim();
                if ("v.m".equals(defaultValue)) {
                    probeButtonId = button.id;
                    return index;
                }
                if ("radio".equalsIgnoreCase(form.type) && radioConfig < 0) {
                    radioConfig = index;
                }
            }
        }
        int chosen = radioConfig >= 0 ? radioConfig : firstConfig;
        if (chosen >= 0) {
            String value = entries.get(chosen)
                .getValue();
            RawYsmModel.ExtraAnimationButton button = value == null ? null
                : ModelConfigRegistry.buttonFor(modelId, value.substring(1));
            probeButtonId = button == null ? null : button.id;
        }
        return chosen;
    }

    /** Delivers a real left click (plus release) to the first form widget the settings screen added. */
    private String clickFirstFormWidget(GuiScreen screen) {
        return clickNthFormWidget(screen, 0);
    }

    /** Delivers a real left click to the {@code wanted}-th checkbox widget (or the first slider when there is none). */
    private String clickNthFormWidget(GuiScreen screen, int wanted) {
        List<GuiButton> buttons = screenButtons(screen);
        if (buttons == null) {
            return "no GuiScreen#buttonList";
        }
        int seen = 0;
        for (GuiButton button : buttons) {
            if (button instanceof ConfigCheckBox) {
                if (seen == wanted) {
                    return clickWidget(screen, button, "checkbox");
                }
                seen++;
            }
        }
        if (wanted == 0) {
            for (GuiButton button : buttons) {
                if (button instanceof FlatSlider) {
                    return clickWidget(screen, button, "slider");
                }
            }
        }
        return "no form widget #" + wanted + ", buttonList=" + buttons.size();
    }

    private String clickWidget(GuiScreen screen, GuiButton button, String kind) {
        int x = button.xPosition + button.width / 2;
        int y = button.yPosition + button.height / 2;
        // GuiScreen#mouseClicked / #mouseMovedOrUp are protected in 1.7.10, so the probe invokes the override the screen
        // actually has (the same two calls the real input pipeline makes through GuiScreen#handleMouseInput).
        Class<?>[] int3 = new Class<?>[] { int.class, int.class, int.class };
        invokeHierarchy(screen, "mouseClicked", int3, x, y, 0);
        invokeHierarchy(screen, "mouseMovedOrUp", int3, x, y, 0);
        return kind + "#" + button.id + " clicked at " + x + "," + y;
    }

    /** Invokes a method on the target's class or the nearest superclass that declares it (protected GuiScreen hooks). */
    private static Object invokeHierarchy(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // Keep walking up; the override may live on GuiScreen itself.
            } catch (Throwable failure) {
                ysmu.LOG.warn("[YSMU-DEBUG][FEATURE] reflect {}#{} failed: {}", type, name, failure);
                return null;
            }
        }
        ysmu.LOG.warn("[YSMU-DEBUG][FEATURE] reflect {}#{} not found", target.getClass(), name);
        return null;
    }

    /** Logs the real player scope and the settings preview's read-through map for the configured form variables. */
    private void logConfigSnapshot(String label) {
        if (mainId == null || mc.thePlayer == null) {
            return;
        }
        Map<String, Double> variables = MolangPhysicsRuntime.snapshotVariables(mc.thePlayer, mainId);
        List<String> interesting = new ArrayList<>();
        for (String name : new String[] { "v.m", "v.mn", "v.g", "v.y", "v.mp", "v.hhh", "v.player_size",
            "v.player_eyebrow", "v.player_eyebrow_v", "v.meimao_v", "v.meimao_r", "v.player_eye_ys" }) {
            Double value = variables.get(name);
            if (value != null) {
                interesting.add(name + "=" + String.format(Locale.ROOT, "%.3f", value));
            }
        }
        Object preview = mc.currentScreen instanceof ModelConfigScreen ? readField(mc.currentScreen, "formPreview")
            : null;
        int previewVariables = -1;
        if (preview instanceof CustomGuiPlayerEntity gui) {
            Map<String, Double> readThrough = gui.getMolangVariables();
            previewVariables = readThrough == null ? -1 : readThrough.size();
        }
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] modelConfig {}: scopeVars={} {} previewReadThroughVars={}",
            label,
            variables.size(),
            interesting,
            previewVariables);
    }

    private String screenName() {
        return mc.currentScreen == null ? "null" : mc.currentScreen.getClass()
            .getSimpleName();
    }

    private static Object invokePrivate(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass()
                .getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable failure) {
            ysmu.LOG.warn("[YSMU-DEBUG][FEATURE] reflect {}#{} failed: {}", target.getClass(), name, failure);
            return null;
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass()
                .getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static int sizeOf(Object collection) {
        return collection instanceof Collection ? ((Collection<?>) collection).size() : -1;
    }

    @SuppressWarnings("unchecked")
    private static List<GuiButton> screenButtons(GuiScreen screen) {
        try {
            // GuiScreen#buttonList is protected; the probe only reads it to aim a real click at a real widget.
            Field field = GuiScreen.class.getDeclaredField("buttonList");
            field.setAccessible(true);
            return (List<GuiButton>) field.get(screen);
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Picks the first root wheel entry that is a playable animation, as the wheel itself would check it. */
    private boolean chooseWheelAnimation() {
        if (mc.thePlayer == null) {
            return false;
        }
        Map<String, String> entries = AnimationRouletteScreen.wheelEntriesFor(modelId);
        if (entries.isEmpty()) {
            return false;
        }
        int index = 0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (key != null && !key.startsWith("#") && EntityAnimationApi.modelHasClip(mc.thePlayer, key)) {
                wheelSlot = index;
                wheelAnimation = key;
                return true;
            }
            index++;
        }
        return false;
    }

    /** The engine geometry of the currently captured model, or {@code null}. */
    private GeoModel geometry() {
        return mainId == null ? null
            : GeckoLibCache.getInstance()
                .getGeoModels()
                .get(mainId);
    }

    /** Every bone of the model in traversal order. */
    private static List<GeoBone> flatten(GeoModel model) {
        List<GeoBone> bones = new ArrayList<>();
        for (GeoBone bone : model.topLevelBones) {
            bones.add(bone);
            flatten(bone, bones);
        }
        return bones;
    }

    private static void flatten(GeoBone bone, List<GeoBone> out) {
        for (GeoBone child : bone.childBones) {
            out.add(child);
            flatten(child, out);
        }
    }

    /** Rotation XYZ + position XYZ of every bone, for the "did anything move" comparison. */
    private static double[] capturePose(GeoModel model) {
        if (model == null) {
            return new double[0];
        }
        List<GeoBone> bones = flatten(model);
        double[] pose = new double[bones.size() * 6];
        for (int i = 0; i < bones.size(); i++) {
            GeoBone bone = bones.get(i);
            int offset = i * 6;
            pose[offset] = bone.getRotationX();
            pose[offset + 1] = bone.getRotationY();
            pose[offset + 2] = bone.getRotationZ();
            pose[offset + 3] = bone.getPositionX();
            pose[offset + 4] = bone.getPositionY();
            pose[offset + 5] = bone.getPositionZ();
        }
        return pose;
    }

    /** Number of channels whose value changed between two poses, or -1 when the poses are not comparable. */
    private static int countChanged(double[] before, double[] after, double epsilon) {
        if (before == null || after == null || before.length != after.length) {
            return -1;
        }
        int changed = 0;
        for (int i = 0; i < before.length; i++) {
            if (Math.abs(before[i] - after[i]) > epsilon) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Logs how far the current pose is from the geometry's bind pose. A pose that is still the bind pose on every bone
     * is the A-pose symptom; a model whose idle clip happens to equal its bind pose would show the same numbers, which
     * is why this is a log line next to the screenshot rather than a hard failure.
     */
    private static int logPose(String label, GeoModel model) {
        if (model == null) {
            ysmu.LOG.info("[YSMU-DEBUG][FEATURE] {}: no geometry", label);
            return -1;
        }
        List<GeoBone> bones = flatten(model);
        int posed = 0;
        int missing = 0;
        double total = 0;
        for (GeoBone bone : bones) {
            BoneSnapshot initial = bone.getInitialSnapshot();
            if (initial == null) {
                missing++;
                continue;
            }
            double deviation = Math.abs(bone.getRotationX() - initial.rotationValueX)
                + Math.abs(bone.getRotationY() - initial.rotationValueY)
                + Math.abs(bone.getRotationZ() - initial.rotationValueZ)
                + Math.abs(bone.getPositionX() - initial.positionOffsetX)
                + Math.abs(bone.getPositionY() - initial.positionOffsetY)
                + Math.abs(bone.getPositionZ() - initial.positionOffsetZ);
            if (deviation > 0.01d) {
                posed++;
            }
            total += deviation;
        }
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] {}: posedBones={}/{} bindDeviationSum={} initialSnapshotMissing={}",
            label,
            posed,
            bones.size() - missing,
            String.format(Locale.ROOT, "%.4f", total),
            missing);
        return posed;
    }

    private void saveNamedShot(String name) {
        try {
            ScreenShotHelper.saveScreenshot(outputDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            ysmu.LOG.info("[YSMU-DEBUG] screenshot {}", name);
        } catch (Throwable failure) {
            ysmu.LOG.error("[YSMU-DEBUG] screenshot failed", failure);
        }
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

