package com.fox.ysmu.client.debug;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import com.fox.ysmu.api.EntityAnimationApi;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ClientProxy;
import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.store.AnimationStore;
import com.fox.ysmu.client.entity.CustomGuiPlayerEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
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
import com.fox.ysmu.util.ControllerUtils;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Developer-only auto-capture harness: launches a flat creative world, selects one or more models, takes third-person
 * and first-person screenshots and dumps the model/animation/bone state, then shuts the client down through the vanilla
 * save-and-quit path ({@link Minecraft#shutdown()}) so the integrated server stops before the process exits.
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
 * (front / back / wide / close-up / walking / sprinting / first-person), then the head-pose sweep of {@link #HEAD_SHOTS}
 * (neutral / look up / look down / turn left / turn right), then the feature sweep described above -&gt; exit the game.
 * Selection goes through the
 * same pair of calls the model screen uses ({@link ExtendedModelInfo#setModelAndTexture} plus the
 * {@link SetModelAndTexture} packet), so the server-side selection and its sync are exercised too; the selection is
 * re-asserted while waiting in case the login sync overwrites it.
 *
 * <p><b>Walking vs sprinting.</b> Two of the seven shots move the player: the walking shot holds forward for 20 ticks,
 * and the sprinting shot inserted after it holds forward <em>and</em> the sprint key for the same 20 ticks, so its frame
 * is taken while a real run is in progress. That distinction is the point: "walk" and "run" are two different entries of
 * YSMU's state table ({@code run} is gated on {@code EntityPlayer#isSprinting()}, {@code AnimationRegister:151}) and a
 * pack's OpenYSM controllers read the same flag through {@code query.is_sprinting} ({@code EntityQueryBindings:142}), so
 * a screenshot of the walking shot alone proves only the walk clip. The harness never calls
 * {@code mc.thePlayer.setSprinting(...)}: 1.7.10's own {@code EntityPlayerSP#onLivingUpdate} derives the flag from the
 * held key while moving forward, and that is exactly the input the animation reads. Every shot logs one
 * {@code [YSMU-DEBUG][LOCO]} line with the keys that were held, the player's actual {@code isSprinting()}, the
 * horizontal speed and the clip/state the engine has mounted; after the sprint shot the walk and sprint pose samples are
 * compared and the result is logged as
 * {@code [YSMU-DEBUG][FEATURE] locomotion walkVsSprint bonesMoved=<n>} and carried into the feature summary. Both lines
 * are log-only evidence for a run clip that a still frame cannot provide (the {@code clip=} field is what tells "the run
 * clip played" apart from "the run state fell back to the walk clip"; on the first-person shot the body is not drawn, so
 * it reports the last frame the body was processed). Neither line is gated by {@code YSMU_DEBUG_FEATURES}: they belong
 * to the shots, which are taken either way.
 *
 * <p><b>Head-pose sweep.</b> After the camera shots every model gets one screenshot per entry of {@link #HEAD_SHOTS}:
 * neutral, look up, look down, turn left, turn right. It drives the real fields the renderer reads instead of poking
 * bones: pitch comes from {@link EntityPlayer#rotationPitch} (the renderer turns it into {@code data.headPitch =
 * -rotationPitch}) and yaw from {@code rotationYawHead - renderYawOffset} (turned into {@code data.netHeadYaw} with the
 * same negation and the same 85 degree clamp). {@code EntityLivingBase#onLivingUpdate} resets {@code rotationYawHead} to
 * {@code rotationYaw} every tick, so the value is re-asserted on every client tick of the settle window; current and
 * previous values are written together so the renderer's partial-tick interpolation cannot smear the pose back.
 * The applied <em>bone</em> rotation is then compared with what {@code CustomPlayerModel#codeAnimation} must add for
 * that data ({@code locatorGroupRoots("Head")}, the same group the renderer rotates, so extra heads numbered
 * {@code Head2}/{@code Head3} are covered): a shot whose measured delta is close to the expected one is {@code PASS}, a
 * delta near zero is {@code FAIL} ("the head does not follow the camera"), and a delta in the right ballpark but off by
 * more than the tolerance is reported as {@code MISMATCH} with both numbers. A model without any head bone is reported
 * as {@code SKIP} instead of a failure. One {@code [YSMU-DEBUG][HEAD]} line per pose carries the expected/observed
 * numbers and the verdict; the per-model summary is {@code [YSMU-DEBUG][HEAD-SUMMARY]}.
 *
 * <p><b>Per-item timings.</b> Every capture item (model readiness, each camera shot, each head pose, the early-pose
 * checks, the emote/roulette/settings/model-config/radio probes, the per-model total and the whole run) is wrapped in
 * {@code beginItem}/{@code endItem}, which log {@code [YSMU-DEBUG][TIME] model='...' item=... ms=...} when the item
 * finishes, and the same rows are written to {@code <YSMU_DEBUG_CAPTURE>/timings.tsv} and printed as a table in the log
 * on exit. Durations include the settle waits the item needs (a shot's walk ticks, a head pose's interpolation window),
 * because that is the wall-clock cost of producing that item.
 *
 * <p>Launching it, e.g. under PowerShell:
 * <pre>{@code
 * $env:YSMU_DEBUG_CAPTURE='E:\ysmu-shot'
 * $env:YSMU_DEBUG_MODELS='e4ba8ce998b6e5a082'   # substring of the model id / its display name
 * $env:YSMU_DEBUG_BONES='beijing'
 * .\gradlew.bat runClient25
 * }</pre>
 * The harness shuts the game down itself when it is done, so the Gradle task returns on its own; it goes through
 * vanilla's save-and-quit path so the integrated server saves and stops first. The variables only affect that process;
 * run {@code Remove-Item Env:YSMU_DEBUG_CAPTURE} (or open a new shell) for a normal launch.
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
     * One camera setup: {@code {thirdPersonView, pitch, fov, walkTicks, sprint}}. {@code walkTicks} holds the forward
     * key for that long before the shot, which is what makes a walk/run animation show up in a still; {@code 0} is
     * idle. {@code sprint} additionally holds the sprint key for the same {@code walkTicks}, so the shot exercises the
     * run states and clips instead of only the walk ones.
     */
    private static final int[][] SHOTS = { { 2, 8, 70, 0, 0 }, // third person, front
        { 1, 8, 70, 0, 0 }, // third person, back
        { 2, 0, 110, 0, 0 }, // third person, wide (fits a large model / background geometry)
        { 2, 15, 40, 0, 0 }, // third person, close-up
        { 2, 8, 70, 20, 0 }, // walking, still moving when the shot is taken
        { 2, 8, 70, 20, 1 }, // sprinting: forward + sprint held, so the run clip is what the shot proves
        { 0, 0, 70, 0, 0 } }; // first person (arm / held item)

    /**
     * One head pose: {@code rotationPitch} in degrees (negative looks up, vanilla's sign) and the head's offset from the
     * body in degrees ({@code rotationYawHead - renderYawOffset}); see the class javadoc.
     */
    private static final float[][] HEAD_SHOTS = { { 0f, 0f }, // neutral (the baseline every other pose is compared to)
        { -40f, 0f }, // look up
        { 40f, 0f }, // look down
        { 0f, 70f }, // turn left
        { 0f, -70f } }; // turn right

    /** File-name label per {@link #HEAD_SHOTS} entry, in the same order. */
    private static final String[] HEAD_SHOT_NAMES = { "neutral", "look_up", "look_down", "turn_left", "turn_right" };

    /**
     * Ticks a head pose is held before the screenshot. Four times the yaw query's response time
     * ({@code RemotePlayerAnimationQueries.HEAD_YAW_RESPONSE = 0.45}/tick) is enough for the smoothed head yaw to settle
     * and for the animation state machine to stop blending, while keeping the sweep short.
     */
    private static final int HEAD_SETTLE_TICKS = 20;

    /** Head verification tolerance floor, in radians (a handful of degrees of animation sway is not a defect). */
    private static final double HEAD_TOLERANCE = Math.toRadians(8.0);

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
    /** Locomotion check (see the class javadoc): the walking and sprinting shot poses, and its summary fragment. */
    private double[] walkPose;
    private double[] sprintPose;
    private String locomotionResult = "";
    /** Feature sweep (roulette / emote / settings): phase counter and its saved samples. */
    private int featureStep;
    private double[] featurePose;
    private String wheelAnimation;
    private int wheelSlot = -1;
    private String featureResult = "";
    /** Settings-panel probe: the config entry the wheel would open, chosen so a real form widget exists. */
    private int probeConfigIndex = -1;
    private String probeButtonId;

    // ---- head-pose sweep (see the class javadoc) ----
    /** Whether the head sweep of the current model already ran; the feature sweep runs after it. */
    private boolean headDone;
    private int headIndex;
    private int headPhase;
    /** Bones {@code CustomPlayerModel#codeAnimation} rotates for this model, resolved once per model. */
    private List<GeoBone> headBones;
    /** Head bone rotation X/Y at the neutral pose, in bone order; the deltas of the other poses are measured against it. */
    private double[] headBaselineX;
    private double[] headBaselineY;
    /** Expected/observed rotation delta per {@link #HEAD_SHOTS} entry (radians); the verdict is computed at sweep end. */
    private double[] headExpectedRad;
    private double[] headObservedRad;
    private final List<String> headRows = new ArrayList<>();
    private String headResult = "";
    private int headPass;
    private int headFail;
    private int headMismatch;
    private int headSkip;

    /**
     * Third-person camera decoupled from the head pose. Vanilla's third-person camera reads {@code rotationPitch}, which
     * is also the head pitch source, so a pitched head would also pitch the camera (and, in the front view, flip it and
     * push the model out of frame). Swapping the <em>view</em> entity for a fixed dummy at the player's position keeps
     * the camera level while the player's own rotation drives the head: the renderer's AnimationEvent is built from the
     * rendered entity (the player), not from the view entity.
     */
    private EntityOtherPlayerMP fixedHeadCamera;
    private boolean fixedHeadCameraActive;

    // ---- per-item timings (see the class javadoc) ----
    private final List<String> timingRows = new ArrayList<>();
    private long itemStartNanos;
    private String itemName;
    private long modelStartNanos;
    private long runStartNanos = System.nanoTime();
    private String modelLabel = "<current-selection>";
    /** Guard so the timing table is written exactly once (normal end and the timeout/max-ticks path both exit). */
    private boolean timingsWritten;

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
            case 6:
                headShots();
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
                    beginItem("model-ready");
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
                endItem();
                beginItem("early-idle");
                earlyPhase = 0;
                earlyPose = null;
                state = 4;
                ticks = 0;
                return;
            }
            if (earlyDone || ticks > 100) {
                ysmu.LOG.info("[YSMU-DEBUG] model {} is ready and drawn, capturing", modelId);
                endItem();
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
        beginItem("shot" + shotIndex + "_" + shotLabel(shot));
        boolean walking = shot[3] > 0;
        boolean sprinting = shot[4] != 0;
        // The sprint shot has to hold the real key, not set the flag: 1.7.10 derives isSprinting() from the held
        // sprint key while moving forward (EntityPlayerSP#onLivingUpdate), and that flag is what the animation reads -
        // YSMU's legacy `run` state (AnimationRegister:151, onGround && isSprinting) and an OpenYSM pack's
        // query.is_sprinting (EntityQueryBindings:142). A direct setSprinting() call would also be overwritten by the
        // next onLivingUpdate, so the input state is both more faithful and the only durable route.
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), walking);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), sprinting);
        if (sprinting) {
            ysmu.LOG.info(
                "[YSMU-DEBUG][LOCO] sprint shot: holding forward+sprint (sprint key code {}); the animation reads EntityPlayer#isSprinting() via the run state (AnimationRegister:151) and query.is_sprinting (EntityQueryBindings:142), no setSprinting() call is made",
                mc.gameSettings.keyBindSprint.getKeyCode());
        }
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

    /**
     * Releases the two movement keys a shot may hold. Every path that stops moving - a finished shot, a finished model,
     * the harness's own exit - goes through here, so a run can never leave the player walking or sprinting.
     */
    private void releaseMoveKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
    }

    private void saveShot() {
        int[] shot = SHOTS[shotIndex];
        // Log the locomotion evidence while the keys are still held: this is the state the shot's frame was drawn in.
        logLocomotion();
        releaseMoveKeys();
        mc.gameSettings.hideGUI = true;
        String name = "model" + modelIndex + "_shot" + shotIndex + "_tpv" + shot[0] + "_fov" + shot[2]
            + (shot[4] != 0 ? "_sprint" : (shot[3] > 0 ? "_walk" : "")) + ".png";
        try {
            ScreenShotHelper.saveScreenshot(outputDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            ysmu.LOG.info("[YSMU-DEBUG] screenshot {}", name);
        } catch (Throwable failure) {
            ysmu.LOG.error("[YSMU-DEBUG] screenshot failed", failure);
        }
        captureLocomotionPose(shot);
        dumpState();
        endItem();
        shotIndex++;
        shotPhase = 0;
        ticks = 0;
        if (shotIndex >= SHOTS.length) {
            modelFinished();
        }
    }

    /** Human-readable suffix of a shot for the timing row and the HEAD/TIME log lines. */
    private static String shotLabel(int[] shot) {
        if (shot[4] != 0) {
            return "sprint";
        }
        if (shot[3] > 0) {
            return "walk";
        }
        if (shot[0] == 0) {
            return "first_person";
        }
        return "tpv" + shot[0] + "_fov" + shot[2];
    }

    /**
     * One locomotion-evidence line per shot (see the class javadoc): the keys that were held, whether vanilla actually
     * flagged the player as sprinting, the horizontal speed and what the animation has mounted. The speed is
     * position-delta based, times 20 for blocks per second, the same quantity as {@code query.ground_speed}
     * ({@code EntityQueryBindings#groundSpeed:359-362}): 1.7.10's local-player {@code motionX}/{@code motionZ} are not
     * the per-tick step (MovementSpeedMatcher:352-360). The clip and state fields describe the last frame the body was
     * drawn, so the first-person shot (which does not draw the body) reports that frame rather than "nothing".
     */
    private void logLocomotion() {
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            ysmu.LOG.info("[YSMU-DEBUG][LOCO] shot={} player=<null>", shotIndex);
            return;
        }
        double dx = player.posX - player.prevPosX;
        double dz = player.posZ - player.prevPosZ;
        ysmu.LOG.info(
            "[YSMU-DEBUG][LOCO] shot={} forwardHeld={} sprintHeld={} isSprinting={} hSpeed={} mainState={} clip={}",
            shotIndex,
            mc.gameSettings.keyBindForward.getIsKeyPressed(),
            mc.gameSettings.keyBindSprint.getIsKeyPressed(),
            player.isSprinting(),
            String.format(Locale.ROOT, "%.3f", Math.sqrt(dx * dx + dz * dz) * 20.0d),
            AnimationManager.getInstance()
                .mainStateOf(player),
            activeClipName());
    }

    /**
     * The clip the engine has mounted on the local player's body, or a marker when it cannot be read. The body is the
     * shared animatable the world renderer draws ({@code CustomPlayerRenderer#getCustomPlayerEntity()}), and the engine
     * keys its animation data by the rendered entity id ({@code IGeoRenderer#getUniqueID} falls back to
     * {@code Entity#hashCode()}, which is the entity id). {@code Animation#animationName} is the name the parser stored
     * ({@code JsonAnimationUtils:363}); a missing answer is reported instead of guessed.
     */
    private String activeClipName() {
        if (mc.thePlayer == null) {
            return "<no-player>";
        }
        if (ClientProxy.getInstance() == null) {
            return "<no-renderer>";
        }
        try {
            CustomPlayerEntity animatable = ClientProxy.getInstance()
                .getCustomPlayerEntity();
            if (animatable == null) {
                return "<no-animatable>";
            }
            AnimationData data = animatable.getFactory()
                .getOrCreateAnimationData(mc.thePlayer.getEntityId());
            AnimationController<?> controller = data.getAnimationControllers()
                .get(ControllerUtils.MAIN_CONTROLLER);
            Animation playing = controller == null ? null : controller.getCurrentAnimation();
            if (playing == null) {
                return "<none>";
            }
            return playing.animationName == null ? "<unnamed>" : playing.animationName;
        } catch (Throwable failure) {
            return "<unreadable:" + failure.getClass()
                .getSimpleName() + ">";
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
        walkPose = null;
        sprintPose = null;
        locomotionResult = "";
        featureStep = 0;
        featurePose = null;
        wheelAnimation = null;
        wheelSlot = -1;
        featureResult = "";
        screenHoldTicks = 0;
        headDone = false;
        headIndex = 0;
        headPhase = 0;
        headBones = null;
        headBaselineX = null;
        headBaselineY = null;
        headResult = "";
        headPass = 0;
        headFail = 0;
        headMismatch = 0;
        headSkip = 0;
        if (models.length == 0) {
            return;
        }
        // Bounds first: advanceModel() increments past the last index and calls startModel() to reach this exit.
        if (modelIndex >= models.length) {
            ysmu.LOG.info("[YSMU-DEBUG] all models captured, exiting");
            exitGame();
            return;
        }
        modelLabel = models[modelIndex];
        modelStartNanos = System.nanoTime();
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
        releaseMoveKeys();
        mc.gameSettings.thirdPersonView = 2;
        mc.gameSettings.fovSetting = 70f;
        if (models.length == 0) {
            // No model was requested: there is exactly one "whatever is selected" capture to take.
            ysmu.LOG.info("[YSMU-DEBUG] capture of the current selection done, exiting");
            exitGame();
            return;
        }
        if (!headDone) {
            // The head sweep runs after the camera shots and before the feature sweep: it needs the world visible and
            // no GUI open, exactly like the shots, and the feature sweep pauses the integrated server by opening screens.
            enterHeadShots();
            return;
        }
        if (features) {
            featureStep = 0;
            featurePose = null;
            wheelAnimation = null;
            wheelSlot = -1;
            // The locomotion comparison was sampled during the shots, before this point: carry its summary fragment
            // into the feature summary that finishFeatures logs.
            featureResult = locomotionResult;
            state = 5;
            ticks = 0;
            return;
        }
        ysmu.LOG.info(
            "[YSMU-DEBUG][SUMMARY] model={} display='{}' cameraShots={} headPass={} headFail={} headMismatch={} headSkip={} features=disabled",
            modelId,
            displayName(),
            SHOTS.length,
            headPass,
            headFail,
            headMismatch,
            headSkip);
        advanceModel();
    }

    /** Moves the capture on to the next requested model. */
    private void advanceModel() {
        endItem();
        if (models.length > 0) {
            recordTiming("model-total", modelStartNanos);
        }
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
            endItem();
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
                    endItem();
                    beginItem("early-walk");
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
                    releaseMoveKeys();
                    logPose("early-walk", model);
                    int moved = countChanged(earlyPose, later, 0.005d);
                    ysmu.LOG.info(
                        "[YSMU-DEBUG][FEATURE] early-walk bonesMoved={} (0 = frozen walk pose, the walk layer never emitted)",
                        moved);
                    featureResult = featureResult + " earlyWalkBonesMoved=" + moved;
                    saveNamedShot("model" + modelIndex + "_early_walk.png");
                    endItem();
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
                beginItem("emote");
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
                    endItem();
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
                beginItem("roulette");
                mc.displayGuiScreen(new AnimationRouletteScreen());
                screenHoldTicks = 6;
                featureStep = 5;
                ticks = 0;
                break;
            case 5:
                if (ticks >= 5) {
                    saveNamedShot("model" + modelIndex + "_roulette.png");
                    endItem();
                    mc.displayGuiScreen(null);
                    featureStep = 6;
                    ticks = 0;
                }
                break;
            case 6:
                ysmu.LOG.info("[YSMU-DEBUG][FEATURE] opening the settings screen");
                beginItem("settings");
                mc.displayGuiScreen(new ConfigScreen(null));
                screenHoldTicks = 6;
                featureStep = 7;
                ticks = 0;
                break;
            case 7:
                if (ticks >= 5) {
                    saveNamedShot("model" + modelIndex + "_settings.png");
                    endItem();
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
        endItem();
        mc.displayGuiScreen(null);
        screenHoldTicks = 0;
        ysmu.LOG.info(
            "[YSMU-DEBUG][FEATURE] summary model={} display='{}' head={} roulette=OK settings=OK{}",
            modelId,
            modelId == null ? null : ModelIdUtil.getModelDisplayName(modelId),
            headResult,
            featureResult);
        ysmu.LOG.info(
            "[YSMU-DEBUG][SUMMARY] model={} display='{}' cameraShots={} headPass={} headFail={} headMismatch={} headSkip={} features={}",
            modelId,
            modelId == null ? null : ModelIdUtil.getModelDisplayName(modelId),
            SHOTS.length,
            headPass,
            headFail,
            headMismatch,
            headSkip,
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
        beginItem("modelconfig");
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
        endItem();
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
        beginItem("radio");
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
        endItem();
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
     * Samples and compares the walking and sprinting poses (see the class javadoc), using the same pose helpers as the
     * early checks. Log-only: a model whose {@code run} clip is missing falls back to its walk clip, and the sprint
     * shot's {@code [YSMU-DEBUG][LOCO] clip=} field is what tells those apart; this line adds "the pose actually moved
     * between the two shots", ideally next to a {@code clip} that changed from a walk to a run clip.
     */
    private void captureLocomotionPose(int[] shot) {
        double[] pose = capturePose(geometry());
        if (pose.length == 0) {
            // No geometry to sample; the LOCO line already reports the clip as unreadable, so stay quiet here.
            return;
        }
        if (shot[4] != 0) {
            sprintPose = pose;
            if (walkPose == null) {
                ysmu.LOG.info("[YSMU-DEBUG][FEATURE] locomotion walkVsSprint skipped: no walking pose sample");
                return;
            }
            int moved = countChanged(walkPose, sprintPose, 0.005d);
            ysmu.LOG.info(
                "[YSMU-DEBUG][FEATURE] locomotion walkVsSprint bonesMoved={} (0 = the pose did not change between the walk and sprint shots; check the sprint shot's [YSMU-DEBUG][LOCO] clip= to tell a run clip from a walk fallback)",
                moved);
            locomotionResult = " locomotionWalkVsSprint=" + moved;
            return;
        }
        if (shot[3] > 0) {
            walkPose = pose;
        }
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

    // ----------------------------------------------------------------------------------------------------------------
    // 头部姿态截图与校验（见类注释）
    // ----------------------------------------------------------------------------------------------------------------

    /** Starts the head sweep of the current model: resolve the head bones once, then one pose per {@link #HEAD_SHOTS}. */
    private void enterHeadShots() {
        headIndex = 0;
        headPhase = 0;
        ticks = 0;
        headExpectedRad = new double[HEAD_SHOTS.length];
        headObservedRad = new double[HEAD_SHOTS.length];
        GeoModel model = geometry();
        headBones = model == null ? new ArrayList<>() : resolveHeadBones(model);
        List<String> names = new ArrayList<>();
        for (GeoBone bone : headBones) {
            names.add(bone.name);
        }
        ysmu.LOG.info(
            "[YSMU-DEBUG][HEAD] model={} display='{}' headBones={} names={} settleTicks={}",
            modelId,
            displayName(),
            headBones.size(),
            names,
            HEAD_SETTLE_TICKS);
        beginFixedHeadCamera();
        state = 6;
    }

    /**
     * One head pose per {@link #HEAD_SHOTS} entry. The same three-phase shape as the camera shots: apply the pose, hold it
     * for {@link #HEAD_SETTLE_TICKS} while re-asserting the fields every tick (vanilla resets {@code rotationYawHead} in
     * {@code EntityLivingBase#onLivingUpdate}), then screenshot and verify the bone rotation.
     */
    private void headShots() {
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            finishHeadSweep();
            return;
        }
        if (headPhase == 0) {
            float pitchDeg = HEAD_SHOTS[headIndex][0];
            float yawOffsetDeg = HEAD_SHOTS[headIndex][1];
            if (headIndex == 0) {
                // Deterministic body facing for the whole sweep: the head yaw is measured against renderYawOffset, and the
                // walking/sprinting shots may have left the body rotated.
                player.rotationYaw = 0f;
                player.prevRotationYaw = 0f;
                player.renderYawOffset = 0f;
                player.prevRenderYawOffset = 0f;
            }
            applyHeadPose(player, pitchDeg, yawOffsetDeg);
            beginItem("head" + headIndex + "_" + HEAD_SHOT_NAMES[headIndex]);
            headPhase = 1;
            ticks = 0;
            return;
        }
        if (headPhase == 1) {
            keepFixedHeadCamera();
            reassertHeadPose(player, HEAD_SHOTS[headIndex][0], HEAD_SHOTS[headIndex][1]);
            if (ticks >= HEAD_SETTLE_TICKS) {
                captureHeadShot(headIndex);
                endItem();
                headPhase = 2;
                ticks = 0;
            }
            return;
        }
        headIndex++;
        headPhase = 0;
        ticks = 0;
        if (headIndex >= HEAD_SHOTS.length) {
            finishHeadSweep();
        }
    }

    private void applyHeadPose(EntityPlayer player, float pitchDeg, float yawOffsetDeg) {
        mc.gameSettings.hideGUI = true;
        mc.gameSettings.pauseOnLostFocus = false;
        // With the decoupled camera the framing is fixed (third-person front through the dummy view entity); without it
        // (the dummy could not be created) fall back to the normal front view.
        mc.gameSettings.thirdPersonView = fixedHeadCameraActive ? 1 : 2;
        mc.gameSettings.fovSetting = 70f;
        reassertHeadPose(player, pitchDeg, yawOffsetDeg);
    }

    /** Creates/activates the decoupled camera entity; on any failure the sweep simply uses the normal camera. */
    private void beginFixedHeadCamera() {
        EntityPlayer player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        try {
            if (fixedHeadCamera == null) {
                // Fixed identity, never rendered (it is not in the world's entity list); only the camera reads it.
                fixedHeadCamera = new EntityOtherPlayerMP(
                    mc.theWorld,
                    new GameProfile(new UUID(0x59534D55_4C41_5545L, 0x4D454D4F44454CL), "ysmu_camera"));
            }
            fixedHeadCameraActive = true;
            keepFixedHeadCamera();
            ysmu.LOG.info(
                "[YSMU-DEBUG][HEAD] camera decoupled from the head pose: renderViewEntity is a fixed entity at the player position");
        } catch (Throwable failure) {
            fixedHeadCameraActive = false;
            ysmu.LOG.warn(
                "[YSMU-DEBUG][HEAD] could not decouple the camera ({}); head shots use the vanilla third-person camera",
                failure.toString());
        }
    }

    /** Keeps the dummy at the player's position with a level, front-facing view while the head pose changes. */
    private void keepFixedHeadCamera() {
        EntityPlayer player = mc.thePlayer;
        if (!fixedHeadCameraActive || fixedHeadCamera == null || player == null) {
            return;
        }
        fixedHeadCamera.prevPosX = player.prevPosX;
        fixedHeadCamera.prevPosY = player.prevPosY;
        fixedHeadCamera.prevPosZ = player.prevPosZ;
        fixedHeadCamera.lastTickPosX = player.posX;
        fixedHeadCamera.lastTickPosY = player.posY;
        fixedHeadCamera.lastTickPosZ = player.posZ;
        fixedHeadCamera.setPosition(player.posX, player.posY, player.posZ);
        // Front view: the camera sits behind the dummy, so the dummy faces away from the player's front.
        fixedHeadCamera.rotationYaw = player.rotationYaw + 180f;
        fixedHeadCamera.prevRotationYaw = fixedHeadCamera.rotationYaw;
        fixedHeadCamera.rotationPitch = 0f;
        fixedHeadCamera.prevRotationPitch = 0f;
        fixedHeadCamera.rotationYawHead = fixedHeadCamera.rotationYaw;
        fixedHeadCamera.prevRotationYawHead = fixedHeadCamera.rotationYaw;
        mc.renderViewEntity = fixedHeadCamera;
        mc.gameSettings.thirdPersonView = 1;
    }

    /** Restores the player camera and the front-view setting the normal shots use. */
    private void endFixedHeadCamera() {
        if (!fixedHeadCameraActive) {
            return;
        }
        fixedHeadCameraActive = false;
        mc.renderViewEntity = mc.thePlayer;
        mc.gameSettings.thirdPersonView = 2;
        mc.gameSettings.fovSetting = 70f;
    }

    /**
     * Writes the pose into the fields the renderer reads. Called on every tick of the settle window: 1.7.10's
     * {@code EntityLivingBase#onLivingUpdate} assigns {@code rotationYawHead = rotationYaw} for non-AI entities, which
     * includes the local player, so a one-shot assignment would be overwritten before the frame is drawn. Current and
     * previous values are written together so the renderer's partial-tick interpolation cannot mix the old pose in.
     */
    private static void reassertHeadPose(EntityPlayer player, float pitchDeg, float yawOffsetDeg) {
        player.rotationPitch = pitchDeg;
        player.prevRotationPitch = pitchDeg;
        float headYaw = player.rotationYaw + yawOffsetDeg;
        player.rotationYawHead = headYaw;
        player.prevRotationYawHead = headYaw;
    }

    /** Screenshots one head pose and verifies the rotation the head bones actually received. */
    private void captureHeadShot(int index) {
        GeoModel model = geometry();
        saveNamedShot("model" + modelIndex + "_head" + index + "_" + HEAD_SHOT_NAMES[index] + ".png");
        if (model == null || headBones.isEmpty()) {
            headSkip++;
            headRows.add(modelLabel + "\t" + HEAD_SHOT_NAMES[index] + "\t\t\tSKIP");
            ysmu.LOG.info(
                "[YSMU-DEBUG][HEAD] model={} display='{}' item={} verdict=SKIP reason=no-head-bone",
                modelId,
                displayName(),
                HEAD_SHOT_NAMES[index]);
            return;
        }
        double[] rotationX = headRotations(model, true);
        double[] rotationY = headRotations(model, false);
        if (index == 0) {
            headBaselineX = rotationX;
            headBaselineY = rotationY;
            headRows.add(modelLabel + "\tneutral\t0.00\t0.00\tBASELINE\traw=0.00");
            ysmu.LOG.info(
                "[YSMU-DEBUG][HEAD] model={} display='{}' item=neutral headBones={} verdict=BASELINE",
                modelId,
                displayName(),
                headBones.size());
            return;
        }
        float pitchDeg = HEAD_SHOTS[index][0];
        float yawOffsetDeg = HEAD_SHOTS[index][1];
        boolean pitchShot = pitchDeg != 0f;
        double expected;
        double observed;
        if (pitchShot) {
            // CustomPlayerModel#codeAnimation adds Math.toRadians(data.headPitch), and data.headPitch = -rotationPitch.
            expected = Math.toRadians(-pitchDeg);
            observed = pickLargestDelta(rotationX, headBaselineX);
        } else {
            // data.netHeadYaw = -wrap(rotationYawHead - renderYawOffset), clamped by RemotePlayerAnimationQueries; the
            // same value is what codeAnimation adds to the head's Y rotation.
            EntityPlayer player = mc.thePlayer;
            float netDeg = player == null ? yawOffsetDeg
                : MathHelper.wrapAngleTo180_float(player.rotationYawHead - player.renderYawOffset);
            netDeg = MathHelper.clamp_float(netDeg, -85f, 85f);
            expected = Math.toRadians(-netDeg);
            observed = pickLargestDelta(rotationY, headBaselineY);
        }
        double tolerance = Math.max(HEAD_TOLERANCE, Math.abs(expected) * 0.30d);
        // The absolute delta against the neutral baseline can carry a constant term when the idle animation's own head
        // pose differs between the baseline capture and the later poses (two shipped packs showed a ~33 degree pitch /
        // ~14 degree yaw term). That term cancels in the pairwise range (up-down, left-right), so the verdict is computed
        // at sweep end from the range and the per-pose values are reported after subtracting the pair's constant term.
        headExpectedRad[index] = expected;
        headObservedRad[index] = observed;
        // The report row is appended by finishHeadSweep(), once the animation offset of the pair is known.
        ysmu.LOG.info(
            "[YSMU-DEBUG][HEAD] model={} display='{}' item={} pose=pitch{}_yaw{} expectedDeg={} rawObservedDeg={} headBones={} verdict=PENDING(range-checked-at-sweep-end)",
            modelId,
            displayName(),
            HEAD_SHOT_NAMES[index],
            pitchDeg,
            yawOffsetDeg,
            formatDegrees(expected),
            formatDegrees(observed),
            headBones.size());
    }

    /** Ends the head sweep of the current model: computes the animation-offset-corrected verdicts, then hands back. */
    private void finishHeadSweep() {
        EntityPlayer player = mc.thePlayer;
        if (player != null) {
            reassertHeadPose(player, 0f, 0f);
            player.renderYawOffset = player.rotationYaw;
            player.prevRenderYawOffset = player.rotationYaw;
        }
        endFixedHeadCamera();
        mc.gameSettings.fovSetting = 70f;
        headDone = true;
        if (!headBones.isEmpty() && headExpectedRad != null) {
            // The constant term of each axis cancels in the pairwise range; estimate it from the pair so the per-pose
            // table can show a corrected value next to the raw one.
            double pitchOffset = (headObservedRad[1] - headExpectedRad[1] + headObservedRad[2] - headExpectedRad[2]) / 2.0d;
            double yawOffset = (headObservedRad[3] - headExpectedRad[3] + headObservedRad[4] - headExpectedRad[4]) / 2.0d;
            List<String> rows = new ArrayList<>();
            for (int index = 1; index < HEAD_SHOTS.length; index++) {
                boolean pitchShot = HEAD_SHOTS[index][0] != 0f;
                double offset = pitchShot ? pitchOffset : yawOffset;
                double corrected = headObservedRad[index] - offset;
                double expected = headExpectedRad[index];
                double tolerance = Math.max(HEAD_TOLERANCE, Math.abs(expected) * 0.30d);
                boolean pass = Math.abs(corrected - expected) <= tolerance;
                if (pass) {
                    headPass++;
                } else {
                    headFail++;
                }
                String row = modelLabel + "\t" + HEAD_SHOT_NAMES[index] + "\t" + formatDegrees(expected) + "\t"
                    + formatDegrees(corrected)
                    + "\t"
                    + (pass ? "PASS" : "FAIL")
                    + "\traw="
                    + formatDegrees(headObservedRad[index])
                    + ",offset="
                    + formatDegrees(offset);
                headRows.add(row);
                rows.add(row);
                ysmu.LOG.info(
                    "[YSMU-DEBUG][HEAD] model={} display='{}' item={} expectedDeg={} correctedDeg={} rawDeg={} constantOffsetDeg={} toleranceDeg={} verdict={}",
                    modelId,
                    displayName(),
                    HEAD_SHOT_NAMES[index],
                    formatDegrees(expected),
                    formatDegrees(corrected),
                    formatDegrees(headObservedRad[index]),
                    formatDegrees(offset),
                    formatDegrees(tolerance),
                    pass ? "PASS" : "FAIL");
            }
            double pitchRange = headObservedRad[1] - headObservedRad[2];
            double pitchRangeExpected = headExpectedRad[1] - headExpectedRad[2];
            double yawRange = headObservedRad[3] - headObservedRad[4];
            double yawRangeExpected = headExpectedRad[3] - headExpectedRad[4];
            boolean pitchRangePass = Math.abs(pitchRange - pitchRangeExpected)
                <= Math.max(HEAD_TOLERANCE, Math.abs(pitchRangeExpected) * 0.15d);
            boolean yawRangePass = Math.abs(yawRange - yawRangeExpected)
                <= Math.max(HEAD_TOLERANCE, Math.abs(yawRangeExpected) * 0.15d);
            headRows.add(
                modelLabel + "\t" + "PITCH_RANGE" + "\t" + formatDegrees(pitchRangeExpected) + "\t"
                    + formatDegrees(pitchRange)
                    + "\t"
                    + (pitchRangePass ? "PASS" : "FAIL")
                    + "\t(raw pair)");
            headRows.add(
                modelLabel + "\t" + "YAW_RANGE" + "\t" + formatDegrees(yawRangeExpected) + "\t"
                    + formatDegrees(yawRange)
                    + "\t"
                    + (yawRangePass ? "PASS" : "FAIL")
                    + "\t(raw pair)");
            ysmu.LOG.info(
                "[YSMU-DEBUG][HEAD-PAIR] model={} display='{}' pitchUpDown expectedDeg={} observedDeg={} verdict={} yawLeftRight expectedDeg={} observedDeg={} verdict={}",
                modelId,
                displayName(),
                formatDegrees(pitchRangeExpected),
                formatDegrees(pitchRange),
                pitchRangePass ? "PASS" : "FAIL",
                formatDegrees(yawRangeExpected),
                formatDegrees(yawRange),
                yawRangePass ? "PASS" : "FAIL");
        }
        headResult = "headPass=" + headPass
            + (headFail > 0 ? " headFail=" + headFail : "")
            + " headSkip="
            + headSkip;
        ysmu.LOG.info(
            "[YSMU-DEBUG][HEAD-SUMMARY] model={} display='{}' headBones={} pass={} fail={} skip={}",
            modelId,
            displayName(),
            headBones.size(),
            headPass,
            headFail,
            headSkip);
        modelFinished();
    }

    /**
     * The bones the renderer rotates for head look: {@code locatorGroupRoots("Head")}, the same accessor
     * {@code CustomPlayerModel#codeAnimation} uses (it covers {@code Head2}/{@code Head3} and skips nested members so a
     * rotation is not applied twice). A pack without the locator group falls back to every bone whose name contains
     * "head", which is what makes the check meaningful for non-humanoid packs as well.
     */
    private static List<GeoBone> resolveHeadBones(GeoModel model) {
        List<GeoBone> roots = model.locatorGroupRoots("Head");
        if (roots != null && !roots.isEmpty()) {
            return new ArrayList<>(roots);
        }
        List<GeoBone> fallback = new ArrayList<>();
        for (GeoBone bone : flatten(model)) {
            if (bone.name != null && bone.name.toLowerCase(Locale.ROOT)
                .contains("head")) {
                fallback.add(bone);
            }
        }
        return fallback;
    }

    /** One rotation channel of every head bone, in the order {@link #headBones} was resolved in. */
    private double[] headRotations(GeoModel model, boolean xAxis) {
        if (model == null) {
            return new double[0];
        }
        double[] values = new double[headBones.size()];
        for (int i = 0; i < values.length; i++) {
            GeoBone bone = headBones.get(i);
            values[i] = xAxis ? bone.getRotationX() : bone.getRotationY();
        }
        return values;
    }

    /** The signed delta with the largest magnitude; a multi-head model reports its most-moved head. */
    private static double pickLargestDelta(double[] observed, double[] baseline) {
        if (observed == null || baseline == null || observed.length != baseline.length) {
            return 0d;
        }
        double best = 0d;
        for (int i = 0; i < observed.length; i++) {
            double delta = observed[i] - baseline[i];
            if (Math.abs(delta) > Math.abs(best)) {
                best = delta;
            }
        }
        return best;
    }

    private static String formatDegrees(double radians) {
        return String.format(Locale.ROOT, "%.2f", Math.toDegrees(radians));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // 每一项测试的用时（见类注释）
    // ----------------------------------------------------------------------------------------------------------------

    /** Starts timing one capture item; an item left open by a skipped branch is recorded instead of lost. */
    private void beginItem(String name) {
        endItem();
        itemName = name;
        itemStartNanos = System.nanoTime();
    }

    /** Finishes the current item and logs one {@code [YSMU-DEBUG][TIME]} line with its wall-clock duration. */
    private void endItem() {
        if (itemName == null) {
            return;
        }
        recordTiming(itemName, itemStartNanos);
        itemName = null;
    }

    private void recordTiming(String name, long startNanos) {
        if (startNanos <= 0L || name == null) {
            return;
        }
        long millis = (System.nanoTime() - startNanos) / 1_000_000L;
        timingRows.add(modelLabel + "\t" + name + "\t" + millis);
        ysmu.LOG.info("[YSMU-DEBUG][TIME] model='{}' item={} ms={}", modelLabel, name, millis);
    }

    /** Prints the collected rows as a table and writes {@code timings.tsv} / {@code head_results.tsv} next to the PNGs. */
    private void logAndWriteTimings() {
        if (timingsWritten) {
            return;
        }
        timingsWritten = true;
        recordTiming("run-total", runStartNanos);
        ysmu.LOG.info("[YSMU-DEBUG][TIME] ===== per-item timings (model / item / ms) =====");
        for (String row : timingRows) {
            ysmu.LOG.info("[YSMU-DEBUG][TIME] {}", row.replace('\t', ' '));
        }
        writeReport("timings.tsv", "model\titem\tdurationMs", timingRows);
        writeReport("head_results.tsv", "model\titem\texpectedDeg\tcorrectedOrRawDeg\tverdict\tdetail", headRows);
    }

    private void writeReport(String fileName, String header, List<String> rows) {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add(header);
        lines.addAll(rows);
        try {
            Files.write(new File(outputDir, fileName).toPath(), lines, StandardCharsets.UTF_8);
            ysmu.LOG.info("[YSMU-DEBUG][TIME] wrote {}", new File(outputDir, fileName));
        } catch (Throwable failure) {
            ysmu.LOG.warn("[YSMU-DEBUG][TIME] could not write {}: {}", fileName, failure.toString());
        }
    }

    private String displayName() {
        return modelId == null ? "?" : ModelIdUtil.getModelDisplayName(modelId);
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

    /**
     * Ends the capture and shuts the client down the way vanilla "save and quit" does. The old path called
     * {@link FMLCommonHandler#exitJava(int, boolean)} from the client tick, which let the JVM tear down while the
     * integrated server thread was still inside {@code MinecraftServer#run} - the server thread then read the
     * already-emptied {@code worldServers} array and wrote a spurious "Exception in server tick loop" crash report
     * after every capture. {@link Minecraft#shutdown()} only clears the game-loop flag; the loop's own finally block
     * calls {@code Minecraft#shutdownMinecraftApplet()}, which unloads the world, asks the integrated server to stop
     * and waits for it to finish ({@code Minecraft#loadWorld(null)}), destroys the display and calls
     * {@code System.exit(0)} - so the Gradle task still returns 0, but without the shutdown race. The forced exit stays
     * as a watchdog in case that path cannot complete (a hung display teardown must not hang the Gradle task forever).
     */
    private void exitGame() {
        // A timeout or failure can happen mid-shot, so give the input keys back before the process ends.
        releaseMoveKeys();
        try {
            endItem();
            logAndWriteTimings();
        } catch (Throwable failure) {
            ysmu.LOG.warn("[YSMU-DEBUG][TIME] could not flush the timing report: {}", failure.toString());
        }
        try {
            ysmu.LOG.info("[YSMU-DEBUG] requesting the vanilla save-and-quit shutdown (world unload + integrated server stop)");
            mc.shutdown();
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(15000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread()
                        .interrupt();
                    return;
                }
                ysmu.LOG.warn("[YSMU-DEBUG] the graceful shutdown did not finish within 15 s, forcing the JVM exit");
                FMLCommonHandler.instance()
                    .exitJava(0, false);
            }, "ysmu-debug-exit-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        } catch (Throwable failure) {
            ysmu.LOG.error("[YSMU-DEBUG] graceful shutdown failed, falling back to exitJava", failure);
            try {
                FMLCommonHandler.instance()
                    .exitJava(0, false);
            } catch (Throwable forcedFailure) {
                ysmu.LOG.error("[YSMU-DEBUG] exitJava failed", forcedFailure);
            }
        }
    }
}


