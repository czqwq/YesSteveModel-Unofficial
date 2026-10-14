package com.fox.ysmu.client;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import com.fox.ysmu.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.AnimationDomain;
import com.fox.ysmu.client.animation.condition.ConditionManager;
import com.fox.ysmu.client.animation.controller.ModDependency;
import com.fox.ysmu.client.animation.controller.ModDependencyRegistry;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;
import com.fox.ysmu.client.texture.CustomTextureManager;
import com.fox.ysmu.client.texture.ModelTextureRegistry;
import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.client.texture.TextureHolder;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.YsmControllerResources;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SyncModelFiles;
import com.fox.ysmu.util.GsonHelper;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.Pair;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.raw.pojo.FormatVersion;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

public class ClientModelManager {

    public static Map<ResourceLocation, List<ResourceLocation>> MODELS = Maps.newHashMap();
    public static Map<ResourceLocation, Pair<Double, Double>> SCALE_INFO = Maps.newHashMap();
    /**
     * Whether a model declares {@code render_layers_first}: its held item and armor are drawn before the model rather
     * than after it, because the model geometry covers them otherwise. Upstream reads the same flag from the model's
     * player settings and orders its layer pass by it ({@code geckolib3/geo/GeoReplacedEntityRenderer.java:92,98,118}).
     * The port parsed the flag into {@code RawProperties#renderLayersFirst} but never consumed it anywhere under
     * {@code client/}, so the setting silently did nothing.
     */
    public static Map<ResourceLocation, Boolean> RENDER_LAYERS_FIRST = Maps.newHashMap();
    /**
     * The texture each model declares as its own default, as the full sub-model id, carried in the packed payload
     * under {@code ysm_default_texture} and read back through the geometry's properties.
     * <p>
     * Upstream's ladder for the texture that gets drawn is the requested name, then the model's own
     * {@code settings.defaultTexture} - which {@code RawModelAssembler:189-196} fills with the first declared
     * texture when the pack declares none - and only then the first entry
     * ({@code format/schema/model/ModelManifestLookup.java:10-29}). The port parsed and synced that field but the
     * client ladder put the global {@code Config.DEFAULT_MODEL_TEXTURE} in that rung, so a pack that declares a
     * default was ignored. A value that is not one of the model's own textures is skipped when it is used, which is
     * what upstream's {@code containsTexture} does.
     */
    public static Map<ResourceLocation, ResourceLocation> DECLARED_DEFAULT_TEXTURES = Maps.newHashMap();
    /**
     * Texture ids whose model art is drawn translucently, which is the port's storage for the question upstream asks
     * before it picks a render type: whether the model has a translucent vertex
     * ({@code GeoModelState#hasTranslucentVertices}, {@code com/elfmcys/ysm/geckolib3/geo/animated/GeoModelState.java
     * :73-74}). A translucent model is drawn with upstream's {@code CustomTranslucentRenderType} - blending and
     * culling - and a cutout one with {@code entityCutoutNoCull}
     * ({@code geckolib3/geo/IGeoRenderer.java:33-39}). Filled at publish from the decoded image and the built
     * geometry, and read by {@code CustomPlayerEntity#hasTranslucentVertices}.
     */
    public static Set<ResourceLocation> TRANSLUCENT_TEXTURES = Sets.newHashSet();
    public static Map<ResourceLocation, List<IChatComponent>> EXTRA_INFO = Maps.newHashMap();
    public static Map<ResourceLocation, String[]> EXTRA_ANIMATION_NAME = Maps.newHashMap();

    /** The image id the parser files a pack's {@code gui_background} under ({@code YSMFolderDeserializer.loadGuiImage}). */
    public static final String GUI_IMAGE_BACKGROUND = "gui_background";
    /** The image id the parser files a pack's {@code gui_foreground} under. */
    public static final String GUI_IMAGE_FOREGROUND = "gui_foreground";
    /**
     * The GUI presentation images a model declares, keyed by the model id the selection screen uses and then by the
     * image's own id ({@link #GUI_IMAGE_BACKGROUND} / {@link #GUI_IMAGE_FOREGROUND}).
     * <p>
     * They are the pack's {@code gui_background} / {@code gui_foreground} (an OpenYSM-format model's
     * {@code ysm.json}), which upstream reads into the model's settings as presentation image blobs and its catalog
     * card draws behind and over the model preview
     * ({@code format/parser/ModelInfoBuilder.java:57-64}, {@code client/gui/button/CatalogModelCardState.java:238-246},
     * {@code client/gui/button/CatalogModelButton.java:104-106}). They are neither model textures nor geometry, so
     * they are neither in {@link #MODELS} nor in the geometry map - see {@code ModelData#getGuiImages}.
     */
    private static final Map<ResourceLocation, Map<String, byte[]>> GUI_IMAGES = Maps.newHashMap();
    /**
     * The published texture of each GUI image, keyed by model id + image id, plus the holder that keeps its
     * registration alive; see {@link #guiImageTextureFor}.
     */
    private static final Map<String, GuiImageTexture> GUI_IMAGE_TEXTURES = Maps.newHashMap();

    /**
     * Records the GUI presentation images a model declares.
     * <p>
     * Called from {@link #registerAll} with the payload's own map, i.e. from the same registration the texture list
     * and the sub-entity indexes come from, and keyed the same way. Self-healing like those: a payload that declares
     * none removes the entry instead of leaving the previous payload's images in place.
     */
    private static void indexGuiImages(ResourceLocation modelId, Map<String, byte[]> images) {
        if (images == null || images.isEmpty()) {
            GUI_IMAGES.remove(modelId);
            return;
        }
        GUI_IMAGES.put(modelId, new LinkedHashMap<>(images));
    }

    /** The declared bytes of one GUI image, or {@code null} when the model declares no such image. */
    @Nullable
    public static byte[] guiImageBytesFor(@Nullable ResourceLocation modelId, @Nullable String imageId) {
        if (modelId == null || imageId == null) {
            return null;
        }
        Map<String, byte[]> images = GUI_IMAGES.get(modelId);
        return images == null ? null : images.get(imageId);
    }

    /**
     * The published texture of one GUI image, created on first use; {@code null} when the model declares none or its
     * bytes cannot be decoded.
     * <p>
     * The lifecycle is upstream's: its catalog card creates the texture from the image source, registers it with
     * {@code CustomTextureManager.register(texture, 10 * 20)} and keeps the returned holder so the registration stays
     * alive while the card does, releasing it on close
     * ({@code client/gui/button/CatalogModelCardState.java:261-274} and {@code :276-296}). This port keeps the holder
     * in {@link #GUI_IMAGE_TEXTURES} instead, so a model's image is uploaded once per session rather than once per
     * tile - a page flip rebuilds every button - and drops them all in {@link #clearRuntimeModelCaches} together with
     * an explicit release, the same two steps {@code ModelTextureRegistry#clear} uses and for the same reason: the
     * platform's texture map keeps the texture object reachable, so the reachability trigger cannot fire on its own.
     */
    @Nullable
    public static ResourceLocation guiImageTextureFor(@Nullable ResourceLocation modelId, @Nullable String imageId) {
        byte[] bytes = guiImageBytesFor(modelId, imageId);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String key = modelId + "/" + imageId;
        GuiImageTexture cached = GUI_IMAGE_TEXTURES.get(key);
        if (cached != null) {
            return cached.id;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                ysmu.LOG.warn("Unsupported YSM GUI image {} of model {}", imageId, modelId);
                return null;
            }
            ResourceLocation id = new ResourceLocation(
                ysmu.MODID,
                "gui_image/" + modelId.getResourcePath() + "/" + imageId);
            DynamicTexture texture = new DynamicTexture(image);
            GUI_IMAGE_TEXTURES.put(key, new GuiImageTexture(id, texture, CustomTextureManager.register(texture, id)));
            return id;
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to decode YSM GUI image {} of model {}", imageId, modelId, e);
            return null;
        }
    }

    /** Drops every GUI image and starts the delayed release of the textures they were published as. */
    private static void clearGuiImages() {
        GUI_IMAGES.clear();
        for (GuiImageTexture image : GUI_IMAGE_TEXTURES.values()) {
            CustomTextureManager.release(image.texture);
        }
        GUI_IMAGE_TEXTURES.clear();
    }

    /**
     * One published GUI image texture. The holder is kept only so the registration stays alive - dropping it is what
     * arms {@code CustomTextureManager}'s release - which is why it is a field and not a local.
     */
    private static final class GuiImageTexture {

        private final ResourceLocation id;
        private final DynamicTexture texture;
        private final TextureHolder holder;

        private GuiImageTexture(ResourceLocation id, DynamicTexture texture, TextureHolder holder) {
            this.id = id;
            this.texture = texture;
            this.holder = holder;
        }
    }

    /**
     * The projectile sub-entity ids a model declares - the {@code match} entries of its
     * {@code files.projectiles} - keyed by the model id.
     * <p>
     * A pack's arrow model is not drawn as part of the player: it replaces a vanilla entity's renderer, and the
     * renderer has to find which of the model's sub-entities answers to the entity type it is drawing (a pack's
     * {@code #arrow} covers {@code minecraft:arrow} and {@code minecraft:spectral_arrow} at once). This is that
     * lookup.
     */
    public static Map<ResourceLocation, List<String>> PROJECTILE_MODEL_IDS = Maps.newHashMap();
    /**
     * The textures belonging to a model's projectile sub-entities, as full sub-model ids.
     * <p>
     * Their ids carry both the match id and the file name ({@code <model>/projectile_<matchId>_<name>.png}), which
     * is how the renderer picks the projectile's own texture rather than one of the player's.
     */
    public static Map<ResourceLocation, List<ResourceLocation>> PROJECTILE_TEXTURE_IDS = Maps.newHashMap();
    /** The key prefix a bridged projectile sub-entity uses inside a {@link ModelData} payload. */
    public static final String PROJECTILE_KEY_PREFIX = "projectile_";
    /** The key prefix a bridged projectile animation-controller file uses. */
    private static final String PROJECTILE_CONTROLLER_KEY_PREFIX = "projectile_ctrl_";

    /**
     * The vehicle sub-entities a model declares ({@code files.vehicles} - keyed by the model id), same shape and same
     * reason as {@link #PROJECTILE_MODEL_IDS}: a vehicle model replaces a vanilla entity's renderer, and the renderer
     * has to find which of the model's sub-entities answers to the entity type it is drawing (the built-in default
     * declares {@code #minecraft:boat}, {@code minecraft:chest_boat} and {@code minecraft:minecart}).
     */
    public static Map<ResourceLocation, List<String>> VEHICLE_MODEL_IDS = Maps.newHashMap();
    /**
     * The textures belonging to a model's vehicle sub-entities, as full sub-model ids, carrying both the match id and
     * the file name ({@code <model>/vehicle_<matchId>_<name>.png}) - the same rule as
     * {@link #PROJECTILE_TEXTURE_IDS}.
     */
    public static Map<ResourceLocation, List<ResourceLocation>> VEHICLE_TEXTURE_IDS = Maps.newHashMap();
    /** The key prefix a bridged vehicle sub-entity uses inside a {@link ModelData} payload. */
    public static final String VEHICLE_KEY_PREFIX = "vehicle_";
    /** The key prefix a bridged vehicle animation-controller file uses. */
    private static final String VEHICLE_CONTROLLER_KEY_PREFIX = "vehicle_ctrl_";
    /**
     * The built-in default model's animations for the main domain, i.e. the fallback a model's own set is completed
     * from. Upstream resolves the fallback per domain ({@code defaultAnimations.fallback(domain)},
     * {@code ModelRenderTargetLoader.java:376}), so the first-person domain has its own entry below.
     */
    public static AnimationFile DEFAULT_ANIMATION_FILE = new AnimationFile();
    /**
     * The built-in default model's {@code fp_arm} set, the fallback for the first-person domain.
     * <p>
     * {@code builtin/default/animations/fp.arm.animation.json} declares {@code parallel0..7} with no bones at all,
     * which is the control case for the domain split: as long as the default's own {@code main} clips are merged into
     * the main domain only, those stubs can no longer replace the built-in {@code parallel0}/{@code parallel1} that
     * drive the default model's eyes and ribbon.
     */
    public static AnimationFile DEFAULT_FIRST_PERSON_ANIMATION_FILE = new AnimationFile();
    public static List<String> CACHE_MD5 = Collections.synchronizedList(Lists.newArrayList());
    public static volatile byte[] PASSWORD;
    public static volatile UUID PASSWORD_UUID;
    // ── Lazy reloading from encrypted client cache ────────────────────────
    /** Maps main model ID → cache file path (relative to CACHE_CLIENT) for re-reading from the encrypted client cache.
     *  <p>并发：写发生在同步/解析路径（含后台解析线程），读发生在
     *  {@link #loadRawModelFromCache}（{@code YSMSoundManager} 首次播放时调用），
     *  所以必须是并发 map —— 普通 HashMap 会在扩容时被并发读撕出错误结果甚至死循环。 */
    private static final Map<ResourceLocation, String> CACHED_MODEL_MD5 =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** Main model IDs whose encrypted client cache uses the OpenYSM (YsmCrypt) format.
     *  Those files are decrypted with the session client key, not the legacy password. */
    private static final java.util.Set<ResourceLocation> OPENYSM_CACHE_FORMAT =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Whether the model preview GUI (PlayerModelScreen) is currently open.
     * While true, all idle resource eviction (geo/anim/texture GPU + heap bytes)
     * is suppressed to prevent re-load thrash when the user briefly closes and
     * reopens the preview.
     * <p>
     * Reference {@code ClientModelManager.java:187-214}. It is only read by the
     * {@code client/asset} eviction subsystem, which is off unless
     * {@code com.fox.ysmu.Config.ENABLE_IDLE_ASSET_EVICTION} is turned on.
     */
    private static volatile boolean GUI_OPEN = false;
    /**
     * Timestamp (System.currentTimeMillis) of the last GUI close.
     * Used with {@code com.fox.ysmu.Config.GUI_POST_CLOSE_GRACE_SECONDS} to suppress eviction
     * for a grace period after the GUI closes.
     */
    private static volatile long GUI_CLOSE_TIME = 0L;

    /**
     * Called by {@link com.fox.ysmu.client.gui.PlayerModelScreen} on open/close.
     * <p>
     * Ported from the reference branch ({@code ClientModelManager.java:198-203}). It is
     * recorded unconditionally, and no behaviour follows from the flag while the eviction
     * subsystem is off.
     */
    public static void setGuiOpen(boolean open) {
        GUI_OPEN = open;
        if (!open) {
            GUI_CLOSE_TIME = System.currentTimeMillis();
        }
    }

    /**
     * Returns true if idle eviction should be suppressed right now: either the
     * GUI is currently open, or it was closed within the configured grace period.
     */
    public static boolean isIdleEvictionSuppressed() {
        if (GUI_OPEN) return true;
        long graceMs = (long) Config.GUI_POST_CLOSE_GRACE_SECONDS * 1000L;
        if (graceMs <= 0L) return false;
        return System.currentTimeMillis() - GUI_CLOSE_TIME < graceMs;
    }

    /** The built-in fallback model's path inside this mod's domain; see {@link #isDefaultModelId}. */
    private static final String BUILT_IN_DEFAULT_MODEL = "default";

    /**
     * True when {@code id} — a base id, a mainId, or any sub-model id such as
     * {@code ysmu:default/arm} — belongs to the <b>built-in fallback model</b>, the one this client materialises for
     * itself ({@code loadDefaultModel}, read from {@code builtin/default} or a legacy {@code custom/default}) and
     * draws whenever an entity's own model is not ready.
     * <p>
     * The pinned id is a fixed one, not the configurable default: upstream's own rule is the literal built-in
     * default ({@code ClientModelRenderTargetManager#isDefault:620-624} -
     * {@code rootKind == BUILTIN && path.equals("default")}) and it pins exactly those leases for the process
     * ({@code RenderTargetLeaseOwnership.addRequired}). {@code Config.DEFAULT_MODEL_ID} is a different thing in
     * upstream too - the <b>selection</b> default a fresh player starts with
     * ({@code ModelSelectionService.selectDefault}) - and that model is an ordinary catalog entry which may be
     * absent. Following the config here used to protect the wrong model whenever a player set
     * {@code DefaultModelId} to something else, while the fallback the renderer actually draws
     * ({@code CustomPlayerModel#DEFAULT_MAIN_MODEL}) stayed releasable.
     * <p>
     * Used to pin the fallback's geo entries against idle eviction (it has no {@code CACHED_MODEL_MD5} entry, so a
     * released entry can never be reloaded through the lazy asset path) and its textures against the connection-level
     * release ({@code ModelTextureRegistry#clear}).
     * <p>
     * Reference {@code ClientModelManager.java:347} (whose body is {@code isDefaultModelBundle}, {@code :329-336})
     * strips the last path segment with {@code ModelIdUtil.getModelIdFromSubId}; this port has no such helper and uses
     * {@link ModelIdUtil#getParentModelId} ({@code ModelIdUtil.java:45-52}), which is the same rule — "the model a
     * sub-model id belongs to" — with the same behaviour for an id that has no segment to strip (it is returned
     * unchanged).
     */
    public static boolean isDefaultModelId(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        ResourceLocation base = ModelIdUtil.getParentModelId(id);
        return ysmu.MODID.equals(base.getResourceDomain())
            && BUILT_IN_DEFAULT_MODEL.equals(base.getResourcePath());
    }

    /**
     * Whether the render paths may fall back on the built-in default model; see the field's own note. {@code false}
     * means this client has no substitute to draw for a model that is not published yet, and the callers that would
     * otherwise cancel the vanilla renderer must not do so ({@code CustomPlayerRenderer#canDrawFor}).
     */
    public static boolean isFallbackAvailable() {
        return fallbackAvailable;
    }

    /**
     * Whether this model's payload can be re-read from the encrypted client cache — the
     * precondition for the lazy asset eviction's reload half.
     * <p>
     * Port-specific addition, with a port-specific reason: the reference's
     * {@code loadLegacyModelData} ({@code ClientModelManager.java:1484-1508}) re-reads both the
     * legacy ({@code EncryptTools.decryptModel}) and the OpenYSM format, and the reference calls
     * it from every provider {@code load}. This port's {@link #loadLegacyModelData} is that bridge
     * minus the legacy channel ({@link #loadRawModelFromCache}, {@code :2200-2227}) because the
     * model → cache-file association for the legacy channel is recorded inside
     * {@code network/message/RequestLoadModel}, which this task may not edit. Registering a
     * geometry or an animation the reload cannot restore would mean evicting it into a permanent
     * fallback to the built-in default, so the eviction must not adopt those models at all — see
     * {@code .agent/phase32-asset-eviction-subsystem.md} and
     * {@code .agent/phase40-eviction-members.md} (the animation half uses this same gate).
     */
    public static boolean hasReloadableClientCache(@Nullable ResourceLocation modelOrSubId) {
        if (modelOrSubId == null) {
            return false;
        }
        ResourceLocation mainId = ModelIdUtil.getMainId(ModelIdUtil.getParentModelId(modelOrSubId));
        return CACHED_MODEL_MD5.containsKey(mainId) && OPENYSM_CACHE_FORMAT.contains(mainId);
    }
    /** Layout versions already reported, so an unsupported geometry format is logged once instead of 150 times. */
    private static final Map<String, Boolean> WARNED_UNSUPPORTED_LAYOUTS = Maps.newConcurrentMap();
    /**
     * Content signature of every model currently installed by {@link #registerAll}, so the same model arriving on
     * both sync channels is registered once instead of twice. Cleared together with the runtime caches it
     * describes.
     */
    private static final Map<ResourceLocation, String> REGISTERED_MODEL_CONTENT = Maps.newConcurrentMap();

    /**
     * Payloads whose animations have been received but not yet installed, keyed by the model's {@code main} id.
     * <p>
     * Animations are the largest part of a payload by a wide margin - across the reference catalog they are about
     * 28.7 MB of JSON against about 13.7 MB of geometry - and they are also the only part nothing needs in order to
     * *list* the models: the selection screen builds its tiles from {@link #MODELS}, its scales from {@link #SCALE_INFO}
     * and its extra-animation wheel from {@link #EXTRA_ANIMATION_NAME}, all of which come from the geometry. So the
     * payload is parked here at registration and installed by {@link #ensureAnimations} the first time anything asks
     * the engine for one of that model's animations, which is the moment the model is first drawn or first played.
     * <p>
     * Upstream does the same thing per animation rather than per model: its animation set is one baked file whose
     * individual animations are read and bound on first request ({@code resource/client/AnimationStore.java}).
     * <p>
     * A model that is never drawn keeps its payload here for the life of the connection, which is the intended
     * trade: a byte array the client already holds costs a fraction of the object graph the parse would build.
     * <p>
     * Concurrent, unlike the geometry parking lot: {@link #ensureAnimations} is reachable from
     * {@code EntityAnimationApi}, which a host mod may call from the server thread while the client thread is
     * rendering - on an integrated server those are two different threads in one JVM.
     */
    private static final Map<ResourceLocation, ModelData> PENDING_ANIMATIONS = Maps.newConcurrentMap();

    /**
     * Payloads whose geometry and textures have been received but not yet built, keyed by both the model id and the
     * model's {@code main} id.
     * <p>
     * Geometry is the second half of the join cost and, unlike animations, it is needed the moment a model is drawn:
     * the renderer asks the cache for the model, and the cache either has it or the model is substituted with the
     * built-in default. Building it is what {@link #ensureGeometry} arranges, on the loader thread, so that the
     * caller never waits: it queues the work and answers "not ready", and the model appears on the frame the result
     * is published by {@link #tick()}.
     */
    private static final Map<ResourceLocation, ModelData> PENDING_GEOMETRY = Maps.newConcurrentMap();

    /** Models whose geometry and textures are installed and drawable. Read from the render path, written by the tick. */
    private static final Set<ResourceLocation> GEOMETRY_READY = ConcurrentHashMap.newKeySet();

    /** Models already handed to the loader, so one model is not built twice while its first build is in flight. */
    private static final Set<ResourceLocation> GEOMETRY_REQUESTED = ConcurrentHashMap.newKeySet();

    /**
     * Builds finished on the loader thread and waiting to be installed.
     * <p>
     * Writing the result into the engine's caches and uploading its textures both have to happen on the client
     * thread, so the loader only produces objects and this queue carries them across. It is unbounded in principle
     * and bounded in practice by the number of models a server can send, which the sync layer already limits.
     */
    private static final Queue<BuiltModel> COMPLETED_BUILDS = new ConcurrentLinkedQueue<>();

    /**
     * How many finished models may be installed per client tick.
     * <p>
     * Installing a model uploads its textures and hands its geometry to the engine, which is fast but not free, and
     * doing all of a large catalog in one tick is the freeze this plan exists to remove. Four per tick is upstream's
     * budget on desktop ({@code render/ModelRenderTargetCache.java:71}); at twenty ticks a second it fills a
     * thirty-three model catalog in under half a second while leaving the frame time alone.
     */
    private static final int MAX_PUBLISH_PER_TICK = 4;

    /**
     * How far the loader may run ahead of the publisher, derived from the publish budget rather than chosen: four
     * ticks of slack, so the loader never idles waiting for the publisher, while a finished build's decoded textures
     * cannot accumulate without bound. See {@link #requestBuild}, which rejects transiently at this limit.
     */
    private static final int MAX_COMPLETED_BUILDS = MAX_PUBLISH_PER_TICK * 4;

    /**
     * Models whose build threw, so they are never asked for again.
     * <p>
     * A build that throws is a deterministic failure - the same bytes will throw again - so retrying it on every
     * frame that draws the model would re-parse broken input forever. Upstream makes the same distinction between a
     * deterministic and a transient failure and quarantines the first. A failed model behaves from then on exactly
     * like one this client never received: it is drawn as the built-in default, with the default's texture.
     */
    private static final Set<ResourceLocation> GEOMETRY_FAILED = ConcurrentHashMap.newKeySet();

    /**
     * Whether the built-in fallback's resources are usable on this client: its geometry was installed and its own
     * textures uploaded at least once, and no later refusal has taken them away again.
     *
     * <p>This is the port's form of upstream's {@code defaultInitialization} future: upstream materialises the whole
     * intrinsic default at startup and every path that would need it goes through
     * {@code ClientModelRenderTargetManager#requireAvailable} ({@code :626-632}), which throws until that initialization
     * succeeded - "a blocking error is a default-initialization failure; an incomplete fallback must not be published"
     * ({@code docs/product-decisions/decisions/model-fallback.md:29-39}). The port cannot fail the client's startup, so
     * it answers the same question with this predicate and the render paths degrade explicitly instead; see
     * {@code CustomPlayerRenderer#canDrawFor}.
     *
     * <p>Deliberately <b>not</b> cleared by {@link #clearRuntimeModelCaches}: the fallback's assets are independent of
     * the model list being dropped ("ordinary model hot-loading, catalog switches, network interruption and cache
     * eviction must not replace, modify or withdraw the default model assets",
     * {@code model-fallback.md:41-43}), and the geometry and textures themselves survive a sync - the same reason the
     * texture registry pins them and the asset eviction pins that geometry.
     */
    private static volatile boolean fallbackAvailable;

    /** The one thread that parses geometry. Created on first use so a dedicated server never starts it. */
    private static volatile ExecutorService modelLoader;

    /**
     * The client's tick/render thread, recorded by the client-side entry points rather than looked up.
     * <p>
     * Deliberately not {@code Minecraft.isCallingFromMinecraftThread()}: that would load the client's
     * {@code Minecraft} class, which references LWJGL and does not exist on a dedicated server, so asking the
     * question would be more dangerous than the race it guards against. {@code null} means "not recorded yet", and
     * every check fails open in that case - which is safe, because nothing is ever parked on a server and so the
     * install this guards has nothing to do there.
     */
    private static volatile Thread clientThread;
    /** Bedrock cache files are named after the hex MD5 of their bytes, which is always 32 characters long. */
    private static final int MD5_HEX_LENGTH = 32;
    private static final int CACHE_HASH_BUFFER_BYTES = 8192;
    /** How long a model sync waits for the client world before it gives up instead of blocking a thread. */
    private static final long SYNC_WORLD_WAIT_MILLIS = 30000L;
    /** Bumped on every sync request and on disconnect, so a superseded verification never sends its reply. */
    private static volatile long SYNC_GENERATION;
    /**
     * The reply waiting for {@code theWorld} to exist. Only the client thread writes it; a disconnect or a newer
     * sync request clears it, which cancels the waiting reply.
     */
    private static volatile PendingModelSync pendingModelSync;
    /** At most one retry hop off the client thread, so a stalled client tick cannot pile up submissions. */
    private static final AtomicBoolean SYNC_RETRY_QUEUED = new AtomicBoolean();

    public static void registerAll(ModelData data) {
        registerAll(data, false);
    }

    /**
     * Takes a synced payload.
     * <p>
     * With {@code eager} false - the path every synced model takes - nothing is parsed here. The model is indexed
     * so the model-selection screen can list it, its animations are parked for first use, and its geometry and
     * textures are parked to be built on the loader thread the first time something asks to draw it. What
     * registration does cost, on the client thread, is hashing the whole payload for the duplicate check; the parse
     * is what it avoids, and that is what removes the nine second freeze at join.
     * <p>
     * With {@code eager} true the payload is installed before returning. Only the built-in default model uses that,
     * because it is the substitute every not-yet-built model falls back to.
     */
    public static void registerAll(ModelData data, boolean eager) {
        ResourceLocation modelId = getModelId(data);
        // N-02: the legacy channel and the OpenYSM channel both end here, and a second registration of identical
        // content rebuilds every GeoModel and re-uploads every texture for no gain.
        String signature = contentSignature(data);
        if (signature != null && signature.equals(REGISTERED_MODEL_CONTENT.get(modelId))) {
            ysmu.LOG.info("YSM client skipping model {}: identical content is already registered", modelId);
            return;
        }
        ysmu.LOG.info(
            "YSM client registering model {}: geometry={}, textures={}, animations={}",
            modelId,
            data.getModel()
                .keySet(),
            data.getTexture()
                .keySet(),
            data.getAnimation()
                .keySet());
        // The texture *list* is the model index the selection screen builds its tiles from, so it stays here; only
        // the uploads are deferred.
        indexTextures(modelId, data.getTexture());
        // The model's GUI presentation images are indexed here for the same reason the texture list is: the selection
        // screen needs them to draw a tile, and nothing about them needs the geometry build.
        indexGuiImages(modelId, data.getGuiImages());
        com.fox.ysmu.data.WheelDeclarations.Values declarations = data.getWheelDeclarations().values();
        com.fox.ysmu.client.gui.ModelConfigRegistry.accept(
            modelId, declarations.buttons, declarations.classifies, declarations.animations);
        com.fox.ysmu.client.roaming.ClientRoamingKeys.accept(modelId, declarations.sha256);
        // D2: the model's metadata links, indexed beside the wheel declarations and for the same reason - this is the
        // one funnel both sync channels register a model through (the legacy container's LINKS_MODEL_VERSION section
        // and the OpenYSM payload's metadata.links both end up in ModelData#getLinks), and the information screen is
        // the only reader. Before this line the field was parsed and synced but nothing on the client consumed it.
        com.fox.ysmu.client.gui.ModelConfigRegistry.acceptLinks(modelId, data.getLinks());
        // The model's GUI preview declaration: which clip its selection tile plays and whether the tile keeps
        // upstream's default framing. It used to be registered by the OpenYSM sync channel alone, so a payload that
        // arrived any other way - the built-in default is read from builtin/ locally - reached the GUI with nothing
        // declared and fell back to `idle`. Registering it here is the same funnel argument as the links above: this
        // is the one place both sync channels (and the local built-in read) hand a payload to the client, and the
        // selection screen is the only reader. Upstream resolves both off the loaded model where the card needs them
        // (client/gui/button/CatalogModelPreviewAnimationState.java:21-24 takes the declared name, defaulting to
        // `idle` exactly as ModelPreviewRegistry.previewFor does; CatalogModelCardState.java:228,
        // CatalogModelButton.java:160 read them off the model's settings).
        com.fox.ysmu.client.gui.ModelPreviewRegistry
            .accept(modelId, data.getPreviewAnimation(), data.isDisablePreviewRotation());
        // A model's projectile sub-entities are indexed here rather than at publish time, because an arrow can be
        // drawn by a client whose player model has not been built yet (someone else's arrow), and the renderer needs
        // the index to decide whether this model has one at all.
        indexProjectiles(modelId, data);
        // Vehicles are indexed for the same reason and at the same moment: a boat can be drawn by a client whose
        // passenger's model was never the local player's, and the renderer needs the index to decide whether this
        // model has a vehicle sub-entity at all.
        indexVehicles(modelId, data);
        if (eager) {
            installNow(data);
        } else {
            parkGeometry(modelId, data);
            // The animations are the largest part of the payload and the only part nothing needs in order to list
            // the models, so they are parked and installed on the first request for one of them.
            deferAnimations(modelId, data);
        }
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_PARSE) {
            ysmu.LOG.info(
                "YSM client registered model {}: totalModelEntries={}, textureCount={}",
                modelId,
                MODELS.size(),
                MODELS.get(modelId) == null ? 0 : MODELS.get(modelId).size());
        }
        if (signature != null) {
            REGISTERED_MODEL_CONTENT.put(modelId, signature);
        }
    }

    /**
     * Serialises the four pieces of geometry state so no transition can be interleaved with another.
     * <p>
     * They are written from two threads - the loader refuses a model whose build threw, the client thread parks a
     * payload, publishes one and refuses one whose main geometry could not be built - and every one of those
     * transitions is a read followed by a write over several structures. Without the lock, a refusal that decided it
     * still owned the id can evict a payload parked in between and mark a live model dead, and the eager path can
     * leave an id published and refused at once. Held only for map and set operations, never across a call that can
     * block, and reentrant so the composite transitions can call each other.
     */
    private static final Object GEOMETRY_STATE_LOCK = new Object();

    /** Every id a caller can hold for one model: the bare model id (the GUI and {@code eep.getModelId()}), the
     * {@code main} sub-model id (the engine's lookup and the texture guard), and the {@code arm} sub-model id (the
     * first-person hand).
     * <p>
     * The readiness markers are written for all three, because otherwise the same question gets three answers
     * depending on which handle the caller happens to hold - and the arm id in particular would bypass every guard,
     * since it appears in no other structure.
     */
    private static ResourceLocation[] allIdsOf(ResourceLocation modelId) {
        return new ResourceLocation[] { modelId, ModelIdUtil.getMainId(modelId), ModelIdUtil.getArmId(modelId) };
    }

    /** Parks geometry and textures under every id a caller can hold. */
    private static void parkGeometry(ResourceLocation modelId, ModelData data) {
        synchronized (GEOMETRY_STATE_LOCK) {
            // A fresh payload for this id is a fresh attempt, so every decision about the previous bytes is dropped
            // with them. Without this, a model re-registered with different bytes stays "ready": ensureGeometry
            // short-circuits, the payload is never built, the engine's cache keeps serving the old geometry - and if
            // the two payloads name their textures differently, the texture the entity asks for was never uploaded.
            // Dropping the request marker additionally lets the new payload be requested at all, which is what a
            // payload arriving while an earlier one for the same id is still building needs; the earlier build's
            // result is discarded by the identity check in publishBuiltModel.
            GEOMETRY_REQUESTED.remove(modelId);
            for (ResourceLocation id : allIdsOf(modelId)) {
                GEOMETRY_READY.remove(id);
                GEOMETRY_FAILED.remove(id);
                // These describe the previous bytes too, and two of them are read without a publish gate.
                EXTRA_ANIMATION_NAME.remove(id);
                EXTRA_INFO.remove(id);
                SCALE_INFO.remove(id);
                RENDER_LAYERS_FIRST.remove(id);
                DECLARED_DEFAULT_TEXTURES.remove(id);
            }
            if (data.getModel()
                .isEmpty()) {
                // Nothing to build, but the markers above are still dropped: this payload is the newest statement
                // about these ids, and a stale refusal or publish must not outlive it. Any payload parked here
                // belongs to those older bytes, so it goes too - leaving it would let a later frame build and
                // publish the previous payload under this one's texture list.
                for (ResourceLocation id : allIdsOf(modelId)) {
                    PENDING_GEOMETRY.remove(id);
                }
                return;
            }
            for (ResourceLocation id : allIdsOf(modelId)) {
                PENDING_GEOMETRY.put(id, data);
            }
        }
    }

    /** The built geometry for a model's {@code main} file, or {@code null} when it could not be produced. */
    @Nullable
    private static BuiltGeo findMainGeometry(List<BuiltGeo> built, ResourceLocation modelId) {
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        for (BuiltGeo geo : built) {
            if (geo != null && geo.id.equals(mainId)) {
                return geo;
            }
        }
        return null;
    }

    /** Builds and installs a payload without going through the loader; used for the built-in default model. */
    private static void installNow(ModelData data) {
        try {
            installNowChecked(data);
        } catch (Throwable failure) {
            // Same guard as the on-demand publish, for the same reason: this runs on the client thread, and an Error
            // escaping here would take the tick down rather than leaving the model refused.
            ResourceLocation modelId = getModelId(data);
            ysmu.LOG.warn("Failed to install model {} eagerly; it is quarantined", modelId, failure);
            rollbackPublishedGeometry(modelId);
            refuseGeometry(modelId);
        }
    }

    private static void installNowChecked(ModelData data) {
        ResourceLocation modelId = getModelId(data);
        List<BuiltGeo> built = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : data.getModel()
            .entrySet()) {
            built.add(buildGeo(ModelIdUtil.getSubModelId(modelId, entry.getKey()), entry.getValue()));
        }
        // Same rule as the on-demand path: a model counts as ready only when its main geometry exists, because that
        // is the file the player renderer looks up. Marking an arm-only build ready would advertise a model that
        // renders nothing.
        if (findMainGeometry(built, modelId) == null) {
            ysmu.LOG.warn(
                "YSM client cannot draw the built-in default model: its main geometry could not be built");
            refuseGeometry(modelId);
            return;
        }
        for (BuiltGeo geo : built) {
            if (geo != null) {
                publishGeo(geo);
            }
        }
        if (!uploadTextures(modelId, data.getTexture(), Collections.emptyMap(), data.getPbrComponents(), Collections.emptyMap())) {
            ysmu.LOG.warn(
                "YSM client cannot draw the built-in default model: one of its textures could not be uploaded");
            rollbackPublishedGeometry(modelId);
            refuseGeometry(modelId);
            return;
        }
        // The eager path is only used for a model that must be drawable immediately, so it is ready as soon as the
        // synchronous build returns.
        markGeometryReady(modelId);
        deferAnimations(modelId, data);
        ensureAnimations(ModelIdUtil.getMainId(modelId));
    }

    /**
     * Whether a model can be drawn or is on its way to being drawable: this is the question "should this entity stay
     * with the YSM renderer", and a model that is merely parked answers yes so the entity does not flap to the
     * vanilla renderer between its registration and its publish.
     * <p>
     * There is deliberately no separate "is the geometry installed" predicate. Since a publish installs the geometry
     * and uploads the textures in one step, and a refusal clears the published marker, the two questions have the
     * same answer in every reachable state - so a second name for it would only invite the two to drift apart.
     */
    public static boolean isModelAvailable(@Nullable ResourceLocation id) {
        return id != null && (isModelPublished(id) || PENDING_GEOMETRY.containsKey(id));
    }

    /**
     * Whether a model has been published on this connection, which is also when its geometry was installed and its
     * textures uploaded. This is the single "can this be drawn" predicate; {@link #isModelAvailable} is the wider
     * "or is it on its way" question and {@link #isGeometryFailed} the refused one.
     * <p>
     * Marker-only on purpose: the engine's geometry cache outlives a connection, so consulting it would report a
     * model as drawable because the *previous* server had one by that name - and the renderer would then draw that
     * foreign geometry as this connection's model, while {@code EntityModelRenderApi.isModelLoaded} answered yes
     * about a model this client was never sent. It is also the predicate a texture bind needs, because binding an id
     * that was never uploaded makes the texture manager fall back to a resource-pack lookup that cannot succeed: it
     * logs {@code Failed to load texture} with a {@code FileNotFoundException} and draws the shared missing texture,
     * so every model in that state looks the same.
     */
    public static boolean isModelPublished(@Nullable ResourceLocation id) {
        return id != null && GEOMETRY_READY.contains(id);
    }

    /**
     * Whether this connection has a payload for this model at all: the server sent it and it was registered, whether
     * or not its geometry was built - or could be built. Accepts either the model's own id or a sub-model id
     * ({@code .../main}, {@code .../arm}).
     * <p>
     * This is the port's form of upstream's catalog lookup ({@code ClientCatalogSnapshot#find}, empty for a model the
     * client never received) and it is the question that tells "wait, and keep what is on screen" apart from "there is
     * nothing to wait for": a <em>Pending</em> or <em>Failed</em> model the client has keeps the model the entity is
     * already shown with, while an id with no content at all goes to the built-in default
     * ({@code EntityModelBinding:106-112} releases the primary and installs the fallback for that case,
     * {@code :297-302} does the same when the catalog loses the applied model's content).
     */
    public static boolean hasModelContent(@Nullable ResourceLocation modelOrSubId) {
        if (modelOrSubId == null) {
            return false;
        }
        // The texture list is keyed by the model's own id, so a sub-model id is answered by its parent - while an id
        // that already is the model's own is answered directly (its path may itself contain segments).
        return MODELS.containsKey(modelOrSubId) || MODELS.containsKey(ModelIdUtil.getParentModelId(modelOrSubId));
    }

    /** Whether this model's build failed, so it is treated as one this client never received. */
    public static boolean isGeometryFailed(@Nullable ResourceLocation id) {
        return id != null && GEOMETRY_FAILED.contains(id);
    }

    /**
     * Asks for a model's geometry, returning whether the caller should stop waiting for it.
     * <p>
     * Read the answer as "nothing more will happen for this id", not as "it is drawable": {@code true} covers a
     * published model, a refused one, and an id this class has never heard of, and only {@code false} means a build
     * has just been submitted. A caller that goes on to look the geometry up must therefore cope with finding
     * nothing (see {@code CustomPlayerRenderer.doRenderModel} and {@code CustomPlayerModel.getModel}), and a caller
     * that wants to know whether to draw should ask {@link #isModelPublished} or {@link #isModelPublished}.
     * <p>
     * This is called from the render path on every frame for whatever is being drawn, so the settled case is one set
     * lookup. The first call submits the build to the loader thread and answers false; the caller draws the built-in
     * default model until {@link #tick()} publishes the result, which is how a model that has been synced but never
     * drawn costs nothing at join and appears without a hitch the first time it is needed.
     */
    public static boolean ensureGeometry(@Nullable ResourceLocation id) {
        if (id == null) {
            return true;
        }
        if (GEOMETRY_READY.contains(id)) {
            // Reference wiring, adapted to this port's single geometry choke point. The reference's
            // render paths each ask the framework for the geometry they are about to draw
            // (CustomPlayerRenderer.java:144 `.get()`, :166 `.touch()`, FirstPersonHandRenderer
            // :430/:437, RenderUtil:492, ModelButton:122) and that is what keeps a live model out of
            // the idle eviction. This port has one shared entry point for every geometry reader
            // (world, HUD overlay and GUI tiles all come through here), so the same two answers are
            // asked here instead of at each draw site.
            //
            // Both calls are gated on the switch: with it off this branch is exactly what it was.
            // The seam only applies to an engine geometry key, i.e. a sub-model id of a model whose
            // bare id is registered in MODELS - the readiness markers also carry the bare model id,
            // which no publish ever puts in the engine cache, and for a model inside a pack folder
            // that bare id still has a path segment (so the sub-name test alone would not exclude
            // it). `touch()` is a no-op for a key the manager does not know
            // (AssetCache.java:112-117), so an unmanaged id costs nothing; `get()` is only asked for
            // an engine geometry key whose geometry is really missing from the cache, which is what
            // an eviction leaves behind.
            if (com.fox.ysmu.client.asset.AssetManager.isEnabled()
                && !MODELS.containsKey(id)
                && !ModelIdUtil.getSubNameFromId(id)
                    .isEmpty()) {
                if (GeckoLibCache.getInstance()
                    .getGeoModels()
                    .get(id) == null) {
                    com.fox.ysmu.client.asset.AssetManager.geo(id)
                        .get();
                } else {
                    com.fox.ysmu.client.asset.AssetManager.geo(id)
                        .touch();
                }
            }
            return true;
        }
        if (GEOMETRY_FAILED.contains(id)) {
            // Quarantined: answer "nothing to wait for" so the caller stops asking, and let its own cache lookup
            // decide what to draw. Answering false would make every frame request this model again, forever.
            return true;
        }
        ModelData data = PENDING_GEOMETRY.get(id);
        if (data == null) {
            // Not a model this manager knows about; nothing to wait for, so do not hold the caller back.
            return true;
        }
        requestBuild(data);
        return false;
    }

    private static void requestBuild(ModelData data) {
        ResourceLocation modelId = getModelId(data);
        // Transient rejection, which is upstream's shape for the same problem: it caps how much work may be
        // outstanding and refuses to add more until the publisher catches up. It matters here because a finished build
        // carries its decoded textures until it is published, and the publisher drains MAX_PUBLISH_PER_TICK per tick
        // while the loader can finish models faster than that - so without a bound a large catalog requested at once
        // would hold decoded images for every model the loader ran ahead on. Four ticks of slack is enough that the
        // loader never idles waiting for the publisher, and a rejected request is not lost: the render path and the
        // GUI ask every frame, and the request marker is deliberately not set here so that they can.
        if (COMPLETED_BUILDS.size() >= MAX_COMPLETED_BUILDS) {
            return;
        }
        // The marker is written under the same lock as every other transition, so the generation drop below cannot
        // clear a marker another request has just set and cost a duplicate build.
        synchronized (GEOMETRY_STATE_LOCK) {
            if (!GEOMETRY_REQUESTED.add(modelId)) {
                return;
            }
        }
        // Remember which connection asked for this build. A build outlives the connection it was queued on - the
        // loader has a queue - so without this the next server could have its model replaced by the previous
        // server's geometry, simply because it happens to use the same model id.
        long generation = SYNC_GENERATION;
        modelLoader().submit(() -> buildModel(modelId, data, generation));
    }

    /**
     * Parses and bakes one model's geometry on the loader thread.
     * <p>
     * Nothing here touches the engine's caches or the GPU: {@code buildGeo} produces a plain object graph, and the
     * result is handed to the client thread through {@link #COMPLETED_BUILDS}. That is also why the engine's caches
     * are still plain maps - the client thread remains their only writer.
     */
    private static void buildModel(ResourceLocation modelId, ModelData data, long generation) {
        try {
            List<BuiltGeo> built = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : data.getModel()
                .entrySet()) {
                built.add(buildGeo(ModelIdUtil.getSubModelId(modelId, entry.getKey()), entry.getValue()));
            }
            // Decode the textures here too, on the loader thread. Reading a PNG header, checking the limits against a
            // decompression bomb and decoding the image are all CPU work; only the upload that follows is a GL call,
            // and that is the part the client thread has to do. It is upstream's split - a worker prepares the images
            // and the render thread publishes them - and it matters because the per-tick publish budget counts models,
            // not textures: a model with several large skins would otherwise decode all of them inside one tick.
            Map<String, BufferedImage> images = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : data.getTexture()
                .entrySet()) {
                try {
                    images.put(entry.getKey(), OuterFileTexture.decode(entry.getValue()));
                } catch (Exception failure) {
                    // Leave it to the client thread, which registers the id and lets the texture manager substitute
                    // its shared missing texture - the behaviour this had before the decode moved here.
                    ysmu.LOG.warn(
                        "YSM client could not decode texture {} of model {}; it will be uploaded as a missing texture",
                        entry.getKey(),
                        modelId,
                        failure);
                    // Upstream's PARTIAL outcome: the model still renders, one of its resources does not. Reported
                    // from here (the loader thread) is fine - the notifier batches and flushes on the client tick.
                    com.fox.ysmu.client.render.ModelFailureNotifier.report(
                        modelId,
                        com.fox.ysmu.client.render.ModelFailureNotifier.Outcome.PARTIAL);
                }
            }
            Map<String, Map<Integer, BufferedImage>> components = new LinkedHashMap<>();
            for (Map.Entry<String, Map<Integer, byte[]>> base : data.getPbrComponents().entrySet()) {
                Map<Integer, BufferedImage> roles = new LinkedHashMap<>();
                for (Map.Entry<Integer, byte[]> role : base.getValue().entrySet()) {
                    try { roles.put(role.getKey(), OuterFileTexture.decode(role.getValue())); }
                    catch (Exception failure) { ysmu.LOG.warn("Could not decode PBR component {} of {}", role.getKey(), base.getKey(), failure); }
                }
                components.put(base.getKey(), roles);
            }
            COMPLETED_BUILDS.add(new BuiltModel(modelId, built, data.getTexture(), images, components, data, generation));
        } catch (Throwable failure) {
            if (isTransientBuildFailure(failure)) {
                // Environmental, not content: an OOM or a linkage error says nothing about the bytes, and the same
                // payload may well build on the next attempt. Upstream keeps this kind out of its deterministic
                // negative cache ({@code ResourceFailure.Kind.TRANSIENT}, ModelRenderTargetLoader:118-131) and gives
                // the expensive attempt no new eligibility until the content or the connection changes
                // (failure-and-recovery.md:28). Leaving the payload parked with its request marker set is exactly
                // that here: nothing re-submits the build per frame, the model is not reported as missing
                // (isModelAvailable still answers true, so an entity keeps the renderer and its applied model), and a
                // new payload or a reconnect - parkGeometry's GEOMETRY_REQUESTED.remove - makes it retryable.
                ysmu.LOG.warn(
                    "YSM client could not build model {} ({}); this is an environment failure, so the model stays "
                        + "parked instead of being quarantined - a new payload or a reconnect may build it",
                    modelId,
                    failure.getClass()
                        .getName(),
                    failure);
                com.fox.ysmu.client.render.ModelFailureNotifier.report(
                    modelId,
                    com.fox.ysmu.client.render.ModelFailureNotifier.Outcome.FALLBACK);
                return;
            }
            ysmu.LOG.warn("Failed to build geometry for model {}", modelId, failure);
            // A build that threw is a deterministic failure - the same bytes will throw again - so the model is
            // quarantined rather than retried. Upstream makes the same distinction and quarantines deterministic
            // failures; retrying here would re-parse the same input on every frame that draws the model. The payload
            // is passed along because this task may already have been superseded.
            markGeometryFailed(modelId, data);
        }
    }

    /**
     * Whether a failed build is an <b>environment</b> failure rather than a content failure.
     *
     * <p>Upstream makes exactly this split and carries it into the failure kind it records: a resource whose reason is
     * {@code AssetLoadException.Reason.CONTENT} becomes {@code ResourceFailure.Kind.DETERMINISTIC} and everything else
     * {@code TRANSIENT} ({@code model/resource/client/render/ModelRenderTargetLoader.java:107-131}); deterministic
     * failures are frozen per content version in the negative cache, while a transient one only costs the current
     * attempt. The port's payload is already in memory (no disk access to fail), so the environmental half is the JVM
     * itself: {@link Error} - an {@code OutOfMemoryError} while baking a large model, a {@code LinkageError} from a
     * half-loaded engine, a {@code StackOverflowError} from a pathological bone tree. Everything an
     * {@code Exception} - a malformed payload, an unsupported geometry version, a build that cannot be represented -
     * is content, and the same bytes will fail again.
     *
     * <p>Neither kind is retried per frame; the difference is that a transient one does not mark the model dead for
     * the rest of the connection. Package private so the rule itself can be pinned without running a loader thread.
     */
    static boolean isTransientBuildFailure(Throwable failure) {
        return failure instanceof Error;
    }

    /**
     * Installs at most {@link #MAX_PUBLISH_PER_TICK} finished models.
     * <p>
     * Called from the client tick, so the work that has to happen on this thread - putting the geometry where the
     * renderer looks for it, and uploading textures - is spread over frames instead of being done in one.
     */
    public static void tick() {
        // Reference wiring: the reference drives AssetManager.tick() from its periodic cache sweep,
        // which runs every 60 s (reference ClientModelManager.java:1750, called from
        // unloadUnusedCaches). This port's client tick is the equivalent hook; AssetManager
        // rate-limits its own scan to the same order of magnitude and no-ops entirely while
        // Config.ENABLE_IDLE_ASSET_EVICTION is off.
        com.fox.ysmu.client.asset.AssetManager.tick();
        // The render-thread step of the texture release mechanism: drain the cleanup events that texture holders
        // becoming unreachable produced, then perform the releases whose grace delay has run out. Upstream drives
        // the same call from its client tick (upstream client/event/ClientTickEvent.java:28 ->
        // CustomTextureManager.tick, upstream :38-41). No-op when nothing is registered.
        ModelTextureRegistry.tick();
        for (int published = 0; published < MAX_PUBLISH_PER_TICK; published++) {
            BuiltModel built = COMPLETED_BUILDS.poll();
            if (built == null) {
                return;
            }
            publishBuiltModel(built);
        }
    }

    /**
     * Installs one finished build, refusing the model if anything goes wrong.
     * <p>
     * The guard is here rather than around the polling loop because a throw out of the publish would otherwise escape
     * into the client tick, and because it must leave the model in a settled state: without it the id would be neither
     * ready nor refused while its request marker stays set, so every later frame would be told to keep waiting for a
     * build that already happened, over a half-written engine cache.
     */
    private static void publishBuiltModel(BuiltModel built) {
        try {
            publishBuiltModelChecked(built);
        } catch (Throwable failure) {
            // Publication is all-or-nothing, which is also upstream's rule: its cache publishes a candidate and only
            // then commits the Ready entry, and a failure at any step - a texture component, the host adoption check -
            // rejects the candidate and leaves the previous Ready target and the intrinsic fallback untouched. So a
            // throw part-way through here must take back what it already installed rather than refusing a model whose
            // geometry and tables are still there for the readers that do not ask the markers.
            ysmu.LOG.warn("Failed to publish model {}; it is quarantined", built.modelId, failure);
            rollbackPublishedGeometry(built.modelId);
            refuseGeometry(built.modelId);
        }
    }

    private static void publishBuiltModelChecked(BuiltModel built) {
        // A build that was queued on a connection that has since ended describes a model the next server may not
        // have, or may have in a different version. Its payload was dropped with that connection, so publishing it
        // would install the wrong model *and*, because the id then looks installed, stop the right one from ever
        // being built on this connection. The generation is the only thing that can tell the two apart: the id alone
        // cannot, since the next server is free to reuse it.
        if (built.generation != SYNC_GENERATION) {
            // The build belonged to a connection that has ended. Dropping it is not enough: the request marker was set
            // for this build and nothing else clears it while the payload stays parked, so leaving it would make every
            // later frame ask for a build that can never be submitted again. Clearing it lets the id be requested
            // afresh, and the new request carries the new generation.
            synchronized (GEOMETRY_STATE_LOCK) {
                GEOMETRY_REQUESTED.remove(built.modelId);
            }
            return;
        }
        if (!PENDING_GEOMETRY.containsKey(built.modelId)) {
            return;
        }
        // A newer payload for this id may have replaced the bytes this build was made from - both sync channels can
        // carry the same model id - and publishing the older ones would install a model nobody asked for and, because
        // the id then looks ready, stop the newer payload from ever being built.
        if (PENDING_GEOMETRY.get(built.modelId) != built.data) {
            return;
        }
        // A model is drawable only when its *main* geometry is, because that is the file the player renderer looks
        // up; a build that produced only the arm would otherwise be reported ready and then render nothing at all.
        // This also covers the case where every file was refused.
        if (findMainGeometry(built.geometry, built.modelId) == null) {
            ysmu.LOG.warn(
                "YSM client cannot draw model {}: its main geometry could not be built; it is quarantined",
                built.modelId);
            markGeometryFailed(built.modelId, built.data);
            return;
        }
        for (BuiltGeo geo : built.geometry) {
            if (geo != null) {
                publishGeo(geo);
            }
        }
        // A texture that could not be uploaded must stop the publish, because the ready marker is the promise that
        // every texture of this model can be bound: marking it ready anyway would let the renderer bind an id that
        // never reached the texture manager, which is the resource-pack fallback and the shared missing texture.
        // TextureManager absorbs an ordinary bad image by substituting the missing texture and still registering the
        // id, so this only triggers on something abnormal.
        if (!uploadTextures(built.modelId, built.textures, built.textureImages, built.data.getPbrComponents(), built.componentImages)) {
            ysmu.LOG.warn(
                "YSM client cannot draw model {}: one of its textures could not be uploaded; it is quarantined",
                built.modelId);
            rollbackPublishedGeometry(built.modelId);
            refuseGeometry(built.modelId);
            return;
        }
        // Which of this model's textures upstream would draw as translucent, decided from the same input its bake uses
        // (the texture's pixels). Assigned, not merely added, so a re-registration that changes the art corrects it.
        indexTranslucentTextures(built);
        markGeometryReady(built.modelId);
        ysmu.LOG.info(
            "YSM client built model {} on demand: pending={}",
            built.modelId,
            PENDING_GEOMETRY.size());
    }

    /**
     * Records, per texture of a just-published model, whether any face of it samples a non-opaque texel.
     * <p>
     * This is how the port answers the question upstream answers before it draws a model: "does it have a translucent
     * vertex?" Upstream picks {@code entityTranslucent} - which culls back faces - when it does and
     * {@code entityCutoutNoCull} when it does not ({@code com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:33-39},
     * {@code GeoModelState:73-74}), and its bake learns that from the texture pixels
     * ({@code BakedModelCache} receives a {@code TexturePixelsSupplier}). A flat decal is the case that needs it: the
     * pack only gives art to one face, the others are built anyway with a zero uv span, and only culling keeps the
     * coincident back face - a single stretched texel - from covering the decal.
     * <p>
     * Sampling the faces matters: these atlases are 58-65% transparent overall because that space is unused, and a
     * whole-image test would mark every model translucent.
     */
    private static void indexTranslucentTextures(BuiltModel built) {
        for (Map.Entry<String, BufferedImage> entry : built.textureImages.entrySet()) {
            ResourceLocation textureId = ModelIdUtil.getSubModelId(built.modelId, entry.getKey());
            if (samplesTranslucentTexel(entry.getValue(), built.geometry)) {
                TRANSLUCENT_TEXTURES.add(textureId);
            } else {
                TRANSLUCENT_TEXTURES.remove(textureId);
            }
        }
    }

    /**
     * Whether any quad of the built geometry samples a texel of {@code image} that is not fully opaque. Quads carry
     * normalized uvs, so the texel is the image size times the coordinate; the scan stops at the first hit.
     */
    private static boolean samplesTranslucentTexel(BufferedImage image, List<BuiltGeo> geometry) {
        if (image == null || geometry == null) {
            return false;
        }
        for (BuiltGeo geo : geometry) {
            if (geo != null && samplesTranslucentTexel(image, geo.model)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any quad of {@code model} samples a texel of {@code image} that is not fully opaque. Package-visible so
     * a test can drive exactly the rule the publish uses, in particular that an opaque texture never trips it.
     */
    static boolean samplesTranslucentTexel(BufferedImage image, GeoModel model) {
        if (image == null || model == null) {
            return false;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        for (GeoBone bone : model.topLevelBones) {
            if (samplesTranslucentTexel(image, bone, width, height)) {
                return true;
            }
        }
        return false;
    }

    private static boolean samplesTranslucentTexel(BufferedImage image, GeoBone bone, int width, int height) {
        for (GeoCube cube : bone.childCubes) {
            if (cube.quads == null) {
                continue;
            }
            for (GeoQuad quad : cube.quads) {
                if (quad == null || quad.vertices == null) {
                    continue;
                }
                for (GeoVertex vertex : quad.vertices) {
                    int x = (int) (vertex.textureU * width);
                    int y = (int) (vertex.textureV * height);
                    if (x < 0 || y < 0 || x >= width || y >= height) {
                        continue;
                    }
                    if ((image.getRGB(x, y) >>> 24) < 255) {
                        return true;
                    }
                }
            }
        }
        for (GeoBone child : bone.childBones) {
            if (samplesTranslucentTexel(image, child, width, height)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes back what a publish installed before it decided to refuse the model.
     * <p>
     * The publish installs the geometry and the tables that describe it before it uploads textures, so a failure at
     * the texture step would otherwise leave a refused model with installed geometry. No marker says it is drawable -
     * the predicates are marker-only - but two readers do not ask: the model tooltip returns the extra info
     * unconditionally, and the animation roulette reads the label table gated only on the build being settled.
     * <p>
     * Client thread only. The loader thread's refusal path runs before anything has been installed, which is why this
     * is not part of the locked refusal itself.
     */
    private static void rollbackPublishedGeometry(ResourceLocation modelId) {
        for (ResourceLocation id : allIdsOf(modelId)) {
            GeckoLibCache.getInstance()
                .getGeoModels()
                .remove(id);
            SCALE_INFO.remove(id);
            RENDER_LAYERS_FIRST.remove(id);
            EXTRA_INFO.remove(id);
            EXTRA_ANIMATION_NAME.remove(id);
        }
    }

    /**
     * Gives up on a model: it is never built again, and every reader sees it as one this client does not have.
     * <p>
     * Both failure paths land here - a build that threw, and a build that produced no main geometry - because both
     * are deterministic. {@link #isModelPublished} answers false for a quarantined id whatever the engine's cache
     * holds, so the render path substitutes the built-in default instead of drawing geometry left over from an
     * earlier connection.
     */
    private static void markGeometryFailed(ResourceLocation modelId, ModelData data) {
        synchronized (GEOMETRY_STATE_LOCK) {
            // Only the payload currently parked for this id may be refused, and the test and the removal have to be
            // one step: a loader task outlives the registration that queued it, so if a newer payload replaced these
            // bytes in between, the check would pass and the removal would evict the newer payload and mark a live
            // model dead - for the rest of the connection, since a refused id is never requested again.
            if (!PENDING_GEOMETRY.remove(modelId, data)) {
                return;
            }
            refuseGeometryLocked(modelId);
        }
    }

    /** Refuses a model unconditionally; for the eager path, which has no loader task that could have gone stale. */
    private static void refuseGeometry(ResourceLocation modelId) {
        synchronized (GEOMETRY_STATE_LOCK) {
            refuseGeometryLocked(modelId);
        }
    }

    /** Body of the refusal paths; callers hold {@link #GEOMETRY_STATE_LOCK}. */
    private static void refuseGeometryLocked(ResourceLocation modelId) {
        if (isDefaultModelId(modelId)) {
            // The fallback itself failed: whatever was installed is rolled back with the same refusal, so no render
            // path may keep assuming it can fall back on it. See #isFallbackAvailable.
            fallbackAvailable = false;
        }
        // Upstream's FALLBACK outcome: this model failed and the affected entities are drawn as the built-in default
        // (its own wording, ModelFailureNotificationAggregator.Outcome.FALLBACK). The port reports it at the model
        // level, because that is where a refusal happens; upstream reports it per entity binding, so the same failure
        // reaches a projectile or vehicle as STOPPED instead - those renderers restore their host, which is what this
        // port's arrow and vehicle paths report for their own content failures.
        com.fox.ysmu.client.render.ModelFailureNotifier.report(
            modelId,
            com.fox.ysmu.client.render.ModelFailureNotifier.Outcome.FALLBACK);
        for (ResourceLocation id : allIdsOf(modelId)) {
            GEOMETRY_FAILED.add(id);
            // A refusal supersedes a publish: leaving the ready marker set would make the id report itself published
            // while it also reports itself refused, which is the one combination no reader expects.
            GEOMETRY_READY.remove(id);
            PENDING_GEOMETRY.remove(id);
        }
        GEOMETRY_REQUESTED.remove(modelId);
    }

    private static void markGeometryReady(ResourceLocation modelId) {
        synchronized (GEOMETRY_STATE_LOCK) {
            if (isDefaultModelId(modelId)) {
                // Geometry published and this model's textures uploaded by the same publish step: the fallback is
                // usable from here on, and stays so across a sync (see #isFallbackAvailable).
                fallbackAvailable = true;
            }
            // Upstream forgets a model that is loaded again, so a failure of a later payload is announced again
            // (ModelFailureNotificationAggregator#recovered:42-46).
            com.fox.ysmu.client.render.ModelFailureNotifier.recovered(modelId);
            for (ResourceLocation id : allIdsOf(modelId)) {
                GEOMETRY_READY.add(id);
                PENDING_GEOMETRY.remove(id);
                // Belt and braces: a refusal already clears the ready marker, so this pair is not reachable today.
                // Recovery from a refusal comes from parkGeometry, which drops it when the bytes are replaced - a
                // refusal removes the parking entry, so there is no later build of the same data to succeed here.
                GEOMETRY_FAILED.remove(id);
            }
            GEOMETRY_REQUESTED.remove(modelId);
        }
    }

    private static ExecutorService modelLoader() {
        ExecutorService loader = modelLoader;
        if (loader != null) {
            return loader;
        }
        synchronized (ClientModelManager.class) {
            if (modelLoader == null) {
                AtomicInteger counter = new AtomicInteger();
                // Upstream's shape: a dedicated pool of half the cores, at least two, one step below normal
                // priority (model/service/ClientModelService.java:94-102). Safe here because building a model only
                // reads the payload it was handed - GeoBuilder.constructGeoModel allocates its own GeoModel and the
                // engine's geo builder registry is written once at mod init.
                int threads = Math.max(2, Runtime.getRuntime()
                    .availableProcessors() / 2);
                modelLoader = Executors.newFixedThreadPool(threads, runnable -> {
                    Thread thread = new Thread(runnable, "YSMU Model Loader " + counter.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
            }
            return modelLoader;
        }
    }

    private static ResourceLocation getModelId(ModelData data) {
        return new ResourceLocation(ysmu.MODID, data.getModelId());
    }

    /**
     * Content fingerprint of everything {@link #registerAll} installs for a model: the geometry, texture and
     * animation bytes plus the digest the server published as this model's metadata.
     * <p>
     * The GUI presentation images are part of it because {@code registerAll} indexes them too: a payload whose only
     * change is its {@code gui_background} / {@code gui_foreground} must not be skipped as "identical content is
     * already registered", or the tile would keep drawing the previous payload's images.
     * <p>
     * The GUI preview declaration ({@code preview_animation} / {@code disable_preview_rotation}) is part of it for the
     * same reason: {@code registerAll} hands it to {@code ModelPreviewRegistry}, so a payload whose only change is the
     * clip its tile plays must not be skipped either.
     * <p>
     * Returns {@code null} when no digest is available, in which case the caller registers unconditionally rather
     * than assuming that two models are equal.
     */
    @Nullable
    private static String contentSignature(ModelData data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            updateDigest(digest, data.getModel());
            updateDigest(digest, data.getTexture());
            updateDigest(digest, data.getAnimation());
            updateDigest(digest, data.getGuiImages());
            // Separated like the file names above, so the declaration cannot run into whatever is hashed next.
            digest.update(data.getPreviewAnimation().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((byte) (data.isDisablePreviewRotation() ? 1 : 0));
            digest.update(data.getWheelDeclarations().encode().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, Map<Integer, byte[]>> base : data.getPbrComponents().entrySet()) {
                digest.update(base.getKey().getBytes(StandardCharsets.UTF_8));
                for (Map.Entry<Integer, byte[]> role : base.getValue().entrySet()) {
                    digest.update((byte) (int) role.getKey());
                    digest.update(role.getValue());
                }
            }
            ServerModelInfo info = data.getInfo();
            String md5 = info == null ? null : info.getMd5();
            if (md5 != null) {
                digest.update(md5.getBytes(StandardCharsets.UTF_8));
            }
            return Md5Utils.toHexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            ysmu.LOG.warn("Cannot fingerprint YSM model {} for duplicate detection", data.getModelId(), e);
            return null;
        }
    }

    /**
     * Hashes the files of one {@code name -> bytes} map in name order. The name and the length are part of the
     * digest so that two different maps cannot hash to the same value.
     */
    private static void updateDigest(MessageDigest digest, Map<String, byte[]> files) {
        if (files == null) {
            digest.update((byte) -2);
            return;
        }
        List<String> names = new ArrayList<>(files.keySet());
        Collections.sort(names);
        for (String name : names) {
            byte[] bytes = files.get(name);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (bytes == null) {
                digest.update((byte) -1);
                continue;
            }
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }

    /**
     * Parks a model's animation payload so it can be installed on first use instead of at registration.
     * <p>
     * Keyed by the model's {@code main} id only, because that is the id the engine asks for the animation file by
     * ({@link ModelIdUtil#getMainId}); the callers here hold model ids and convert. A payload that declares no
     * animations of its own clears whatever is parked or already installed under that id instead, so a re-registered
     * model cannot keep playing the previous payload's clips.
     */
    static void deferAnimations(ResourceLocation modelId, ModelData data) {
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        // Anything already installed under either of this model's animation ids belongs to the payload being replaced,
        // so it goes first: readers that ask CustomPlayerEntity.getAnimation() without installing first (the id-only
        // users in AnimationManager) would otherwise act on the previous payload's file until the new one happens to
        // be installed. Both ids, because the payload is installed as one file per domain
        // ({@link AnimationDomain}); leaving the first-person file behind would keep the previous pack's arm clips.
        for (String domain : AnimationDomain.ALL) {
            GeckoLibCache.getInstance()
                .getAnimations()
                .remove(AnimationDomain.animationFileId(modelId, domain));
        }
        if (data.getAnimation()
            .isEmpty()) {
            // This payload declares no animations of its own, so the model is meant to fall back to the built-in
            // default animations; dropping anything parked under the id is what makes that fallback reachable.
            PENDING_ANIMATIONS.remove(mainId);
            return;
        }
        PENDING_ANIMATIONS.put(mainId, data);
    }

    /** Whether this model's animations are still parked; see {@link #PENDING_ANIMATIONS}. */
    public static boolean animationsPending(@Nullable ResourceLocation mainId) {
        return mainId != null && PENDING_ANIMATIONS.containsKey(mainId);
    }

    /**
     * Takes a parked animation payload, returning it exactly once.
     * <p>
     * The entry is removed before the caller installs anything, so a re-entrant request - the engine asks for an
     * animation while that same model's animations are being installed - sees a miss and falls through to whatever is
     * already in the cache rather than starting a second install.
     */
    @Nullable
    static ModelData takePendingAnimations(ResourceLocation mainId) {
        return PENDING_ANIMATIONS.remove(mainId);
    }

    /**
     * Records the calling thread as the client's, so {@link #ensureAnimations} can tell whether it is on it.
     * <p>
     * Called from the client-side entry points only. Recording it here rather than asking the game keeps the check
     * free of any client class, which is what makes it safe to reach from a host mod on a dedicated server.
     */
    public static void markClientThread() {
        clientThread = Thread.currentThread();
    }

    /**
     * Whether the calling thread is the client's.
     * <p>
     * Fails <em>open</em> while no thread has been recorded, which is deliberate: the guard exists to stop a host mod
     * on the server thread from installing animations, and a fail-closed guard would silently stop installing them on
     * the client if the recording ever moved. On a dedicated server nothing is ever parked - every writer of
     * {@link #PENDING_ANIMATIONS} is reachable only from the client - so the open case installs nothing anyway.
     */
    private static boolean isClientThread() {
        Thread known = clientThread;
        return known == null || known == Thread.currentThread();
    }

    /**
     * The animation file for a model, installing that model's animations first if they are still parked.
     * <p>
     * Every reader of {@code GeckoLibCache.getAnimations()} that wants to *use* a file should come through here
     * rather than reaching into the cache, because since animations are installed on first use an entry is only
     * present once something has already asked for it. A reader that skips this step sees "this model has no
     * animations" for a model that simply has not been drawn yet, which is how a non-player animatable would end up
     * never installing its own animations at all: the state predicates would answer "no such animation" forever and
     * the engine lookup that installs them would never be reached.
     */
    @Nullable
    public static AnimationFile animationFileFor(@Nullable ResourceLocation mainId) {
        if (mainId == null) {
            return null;
        }
        if (!isClientThread()) {
            // The engine's animation cache is a plain map written by the client thread's install, and this method is
            // reachable from EntityAnimationApi, which a host mod may call from the server thread. Reading it there
            // would be a read concurrent with that write, so the answer is the one the caller got before installation
            // became lazy - and the one that API documents for a server-side call.
            return null;
        }
        ensureAnimations(mainId);
        return GeckoLibCache.getInstance()
            .getAnimations()
            .get(mainId);
    }

    /**
     * Installs a model's animations if they are still parked, and does nothing otherwise.
     * <p>
     * This is called from the engine's animation lookup, so it runs on every frame for whatever animation is
     * playing; after the first call it is a single lookup in a map that holds only the models not yet drawn.
     */
    public static void ensureAnimations(@Nullable ResourceLocation mainId) {
        if (mainId == null) {
            return;
        }
        if (!isClientThread()) {
            // Installing parses JSON and writes several tables the client thread reads every frame, including the
            // engine's plain-HashMap animation cache. This method is reachable from EntityAnimationApi, which a host
            // mod may call from the server thread and which promises to answer rather than throw, so it must not
            // install from there. The caller simply sees "not installed", which is the answer it got before
            // installation became lazy; the render path installs it on the client thread the moment the model is
            // drawn.
            return;
        }
        ModelData data = takePendingAnimations(mainId);
        if (data == null) {
            // Nothing parked: either this model's animations are already installed, or it declares none of its own
            // and reads the built-in default. The eviction seam is the animation half of ensureGeometry's.
            keepAnimationsResident(mainId);
            return;
        }
        try {
            registerAnimations(mainId, data.getAnimation());
        } catch (Throwable failure) {
            // Throwable, like the geometry path: an Error from a JSON parse or a table registration would otherwise
            // escape into the engine's animation lookup on the render thread, which catches only Exception. The
            // payload is gone either way, so the model simply has no animation file of its own for this connection -
            // the same outcome it had before installation became lazy, where the one attempt happened at registration.
            ysmu.LOG.warn("Failed to register animations for model {}", mainId, failure);
            // A failed install must not be adopted: the eviction would then hold an entry the reload cannot
            // restore into the state a successful install would have produced.
            return;
        }
        // Reference wiring, adapted to this port's lazy install. The reference hands the file it just installed to
        // the asset framework at the same moment (reference ClientModelManager.java:644, inside applyPreParsed) and
        // its AssetManager.tick() touches exactly this entry to pin the built-in default (reference
        // AssetManager.java:81-82). Two gates, the same pair as publishGeo's: the switch, and the port-specific
        // reloadability test documented on hasReloadableClientCache - a payload this port cannot re-read must never
        // be adopted by a cache whose whole job is to evict it. What is cached is the animation map itself, i.e. the
        // install rule's own input, and not the whole ModelData: the geometry and texture bytes that also ride in
        // it are dropped at install in this port and must not be kept alive for the rest of the session.
        if (com.fox.ysmu.client.asset.AssetManager.isEnabled() && hasReloadableClientCache(mainId)) {
            com.fox.ysmu.client.asset.AssetManager.registerAnim(mainId, data.getAnimation());
        }
    }

    /**
     * The animation half of {@link #ensureGeometry}'s anti-eviction seam: a model whose animations were evicted must
     * get them back, and one that is being drawn must not be evicted at all.
     * <p>
     * Called from {@link #ensureAnimations}, which {@code CustomPlayerModel.getAnimation} reaches on every animation
     * lookup - i.e. once per drawn model per frame - and which is the port's single animation choke point, exactly as
     * {@code ensureGeometry} is for geometry. The reference instead asks at each render site
     * ({@code CustomPlayerEntity.java:198}, {@code RenderUtil.java:193}); one seam does the same job here.
     * <p>
     * Why the {@code managesAnimations} test rather than the engine cache alone: geometry has the invariant that a
     * published model's geometry is in {@code GeckoLibCache}, so "the cache has no geometry" means "it was evicted".
     * Animations have no such invariant - a model that declares no animations of its own legitimately has no file and
     * falls back to the built-in default ({@code CustomPlayerEntity.getAnimation()} answers
     * {@code DEFAULT_MAIN_ANIMATION} on a miss), and asking for a reload there would decrypt the whole payload to
     * install clips it already falls back to. {@link com.fox.ysmu.client.asset.AssetManager#managesAnimations} is
     * the record of "this subsystem adopted this model", which survives the eviction that drops the cache entry.
     * <p>
     * Both calls are gated on the switch, and the reloadability test is repeated because the "model to cache file"
     * association can be dropped by a disconnect between the adoption and this call; a stale adoption then goes
     * quiet instead of starting a load that is bound to fail.
     */
    private static void keepAnimationsResident(ResourceLocation mainId) {
        if (!com.fox.ysmu.client.asset.AssetManager.isEnabled()
            || !com.fox.ysmu.client.asset.AssetManager.managesAnimations(mainId)
            || !hasReloadableClientCache(mainId)) {
            return;
        }
        if (GeckoLibCache.getInstance()
            .getAnimations()
            .get(mainId) == null) {
            com.fox.ysmu.client.asset.AssetManager.anim(mainId)
                .get();
        } else {
            com.fox.ysmu.client.asset.AssetManager.anim(mainId)
                .touch();
        }
    }

    /**
     * Re-installs one model's animations after the per-domain files were released from the engine cache.
     * <p>
     * Client thread only, called from the asset framework's apply phase
     * ({@code client/asset/provider/AnimationProvider#apply}). It is deliberately <em>not</em> a second
     * implementation of this port's install rule: it calls {@link #registerAnimations} - the same private pipeline
     * the first install ran, and the only thing that produces the two per-domain files ({@link AnimationDomain}),
     * the per-domain default fallback and the conditions and controllers that go with them.
     * <p>
     * The reference's provider can be narrower because its model has one merged animation file: its
     * {@code apply} only puts that file into {@code GeckoLibCache} (reference {@code provider/AnimationProvider.java:27-34}).
     * A merged file cannot express this port's two domains - it is the defect {@code AnimationDomain} exists to fix -
     * so the reload replays the rule with the animation map the provider just re-read. See
     * {@code .agent/phase40-eviction-members.md}.
     *
     * @param animations the payload's animation file map, exactly as {@link ModelData#getAnimation()} returns it
     */
    public static void reinstallAnimations(@Nullable ResourceLocation mainId, @Nullable Map<String, byte[]> animations) {
        if (mainId == null || animations == null || animations.isEmpty()) {
            return;
        }
        if (!isClientThread()) {
            // Same reason as ensureAnimations: this writes the engine's caches and several per-domain tables, and
            // the apply phase that calls it runs on the client thread.
            return;
        }
        try {
            registerAnimations(mainId, animations);
        } catch (Throwable failure) {
            // Same guard as ensureAnimations': the payload is the freshly re-read one, so a failure here leaves the
            // model on the built-in default fallback rather than taking down the client thread's task queue.
            ysmu.LOG.warn("Failed to re-register animations for model {}", mainId, failure);
        }
    }

    /**
     * Builds and installs a payload's geometry directly, without the loader and without the per-tick budget.
     * <p>
     * Client thread only, like everything else that writes the engine's caches. A model installed through here
     * becomes drawable only when its {@code main} geometry could be built, exactly as a publish does - without that
     * gate the model would be listed in the GUI but never drawn, because the readiness predicates are keyed on this
     * class's markers rather than on the engine's cache. No production caller today; {@link #registerAll} is the
     * entry point every sync path uses.
     */
    public static void registerGeo(ResourceLocation id, Map<String, byte[]> mapData) {
        List<BuiltGeo> built = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : mapData.entrySet()) {
            built.add(buildGeo(ModelIdUtil.getSubModelId(id, entry.getKey()), entry.getValue()));
        }
        if (findMainGeometry(built, id) == null) {
            refuseGeometry(id);
            return;
        }
        for (BuiltGeo geo : built) {
            if (geo != null) {
                publishGeo(geo);
            }
        }
        markGeometryReady(id);
    }

    /** One model's geometry, baked but not yet installed; see {@link #buildGeo} and {@link #publishGeo}. */
    private static final class BuiltGeo {

        private final ResourceLocation id;
        private final GeoModel model;
        private final double heightScale;
        private final double widthScale;
        private final boolean renderLayersFirst;
        /**
         * The texture this model declares as its own default, as the full sub-model id, or {@code null} when it
         * declares none. Carried in the same {@code ysm_} property block as {@link #renderLayersFirst}; see
         * {@link #DECLARED_DEFAULT_TEXTURES} for why it exists.
         */
        @Nullable
        private final ResourceLocation declaredDefaultTexture;
        @Nullable
        private final ExtraInfo extraInfo;

        private BuiltGeo(ResourceLocation id, GeoModel model, RawGeometryTree tree) {
            this.id = id;
            this.model = model;
            this.heightScale = tree.properties.getHeightScale();
            this.widthScale = tree.properties.getWidthScale();
            this.renderLayersFirst = Boolean.TRUE.equals(tree.properties.getRenderLayersFirst());
            this.declaredDefaultTexture = declaredDefaultTexture(id, tree.properties.getDefaultTexture());
            this.extraInfo = tree.properties.getExtraInfo();
        }

        /**
         * The declared default as a bindable id, or {@code null} when the model declares none. The name is a
         * texture key of this model, the same form {@link #indexTextures} turns into an id; whether it really is one
         * of this model's textures is checked where it is used, which is what upstream's {@code containsTexture}
         * does.
         */
        @Nullable
        private static ResourceLocation declaredDefaultTexture(ResourceLocation id, @Nullable String name) {
            return name == null || name.isEmpty() ? null : ModelIdUtil.getSubModelId(id, name);
        }
    }

    /** One model's finished build, waiting to be installed on the client thread. */
    private static final class BuiltModel {

        private final ResourceLocation modelId;
        private final List<BuiltGeo> geometry;
        private final Map<String, byte[]> textures;
        /**
         * The textures already decoded on the loader thread, keyed the same way as {@link #textures}; a texture missing
         * from here failed to decode and is left to the client thread. See {@link #buildModel}.
         */
        private final Map<String, BufferedImage> textureImages;
        private final Map<String, Map<Integer, BufferedImage>> componentImages;
        /** The exact payload these bytes came from; see the identity check in {@link #publishBuiltModel}. */
        private final ModelData data;
        /** The connection this build was queued on; see {@link #publishBuiltModel}. */
        private final long generation;

        private BuiltModel(ResourceLocation modelId, List<BuiltGeo> geometry, Map<String, byte[]> textures,
            Map<String, BufferedImage> textureImages, Map<String, Map<Integer, BufferedImage>> componentImages,
            ModelData data, long generation) {
            this.modelId = modelId;
            this.geometry = geometry;
            this.textures = textures;
            this.textureImages = textureImages;
            this.componentImages = componentImages;
            this.data = data;
            this.generation = generation;
        }
    }

    /**
     * Parses and bakes one geometry file.
     * <p>
     * Pure CPU: it reads no engine cache and writes none, which is what lets {@link #buildModel} run it on the loader
     * thread. Everything it produces is a plain object that {@link #publishGeo} installs on the client thread.
     *
     * @return the baked geometry, or {@code null} when the file declares a layout this port cannot build.
     */
    @Nullable
    private static BuiltGeo buildGeo(ResourceLocation id, byte[] data) {
        try {
            // 直接从字节数组解析JSON，而不是尝试反序列化对象
            String modelJson = new String(data, StandardCharsets.UTF_8);
            RawGeoModel rawModel = Converter.fromJsonString(modelJson);

            // MON-01: whether this port attempts a layout is the engine's decision, not a second table kept here.
            // The previous local whitelist is what made every 1.8.0/1.10.0 model disappear.
            FormatVersion formatVersion = rawModel.getFormatVersion();
            if (formatVersion == null || !formatVersion.isSupportedLayout()) {
                warnUnsupportedLayout(id, formatVersion);
                return null;
            }
            RawGeometryTree rawGeometryTree = RawGeometryTree.parseHierarchy(rawModel);
            GeoModel geoModel = GeoBuilder.getGeoBuilder(id.getResourceDomain())
                .constructGeoModel(rawGeometryTree);
            return new BuiltGeo(id, geoModel, rawGeometryTree);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to register geometry " + id, e);
            return null;
        }
    }

    /** Installs one baked geometry file; client thread only. */
    private static void publishGeo(@Nullable BuiltGeo built) {
        if (built == null) {
            return;
        }
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(built.id, built.model);
        SCALE_INFO.put(built.id, Pair.of(built.heightScale, built.widthScale));
        RENDER_LAYERS_FIRST.put(built.id, built.renderLayersFirst);
        // Self-healing: a payload that declares no default must not inherit the previous bytes' declaration, so the
        // absence removes rather than merely skipping the put. The invalidation sites drop it too, for the ids this
        // build does not publish.
        if (built.declaredDefaultTexture == null) {
            DECLARED_DEFAULT_TEXTURES.remove(built.id);
        } else {
            DECLARED_DEFAULT_TEXTURES.put(built.id, built.declaredDefaultTexture);
        }
        EXTRA_INFO.put(built.id, handleExtraInfo(built.id, built.extraInfo));
        // The information screen wants the same block, keyed by the model rather than by its main/arm
        // sub-model. The richer author list (with avatars) arrives separately over the OpenYSM channel.
        ClientModelMetadataRegistry.acceptExtraInfo(ModelIdUtil.getParentModelId(built.id), built.extraInfo);
        String[] extraAnimationNames = built.extraInfo == null ? null : built.extraInfo.getExtraAnimationNames();
        if (extraAnimationNames != null && extraAnimationNames.length > 0) {
            EXTRA_ANIMATION_NAME.put(built.id, extraAnimationNames);
        }
        ysmu.LOG.info(
            "YSM client registered geometry {}: heightScale={}, widthScale={}, hasExtraInfo={}, extraAnimationNames={}",
            built.id,
            built.heightScale,
            built.widthScale,
            built.extraInfo != null,
            extraAnimationNames != null ? extraAnimationNames.length : 0);
        // Reference wiring: applyPreParsed hands every installed geometry to AssetManager
        // (reference ClientModelManager.java:599). Gated twice for the same reason as every other
        // AssetManager call site: with Config.ENABLE_IDLE_ASSET_EVICTION off the subsystem must do
        // nothing at all. The reloadability test is the port-specific safety gate documented on
        // hasReloadableClientCache: a geometry the port cannot re-read must never be adopted by a
        // cache whose whole job is to evict it.
        if (com.fox.ysmu.client.asset.AssetManager.isEnabled() && hasReloadableClientCache(built.id)) {
            com.fox.ysmu.client.asset.AssetManager.registerGeo(built.id, built.model);
        }
    }

    /**
     * Reports a geometry file this port cannot build, once per declared version.
     * <p>
     * The version gate itself delegates to {@link FormatVersion#isSupportedLayout()}, so this only fires for a
     * file that declares no usable {@code format_version} at all: a value the enum cannot represent is already
     * rejected inside the engine's converter and logged by the caller's catch block.
     * <p>
     * Note that the gate only decides whether a layout is attempted. Whether the engine's geometry tree can parse
     * a newer Bedrock layout is a separate engine-side question, so passing this gate is not a promise that such a
     * model builds - it only stops this port from silently skipping a compatible one.
     */
    private static void warnUnsupportedLayout(ResourceLocation id, @Nullable FormatVersion version) {
        String key = String.valueOf(version);
        if (WARNED_UNSUPPORTED_LAYOUTS.putIfAbsent(key, Boolean.TRUE) == null) {
            ysmu.LOG.warn(
                "YSM client cannot build geometry {}: the engine does not support its declared layout ({}); skipping",
                id,
                version == null ? "no format_version" : version);
        }
    }

    public static void registerTexture(ResourceLocation id, Map<String, byte[]> mapData) {
        indexTextures(id, mapData);
        uploadTextures(id, mapData);
    }

    /** Records which textures a model has, without uploading them; this is the index the selection screen lists. */
    private static void indexTextures(ResourceLocation id, Map<String, byte[]> mapData) {
        List<ResourceLocation> textures = Lists.newArrayList();
        for (String name : mapData.keySet()) {
            textures.add(ModelIdUtil.getSubModelId(id, name));
        }
        MODELS.put(id, textures);
    }

    /**
     * Records the projectile sub-entities a model declares, so the arrow renderer can ask "does this model have a
     * projectile for the entity I am drawing, and which texture is its own".
     * <p>
     * Both answers come out of the payload's key naming, which {@code RawYsmModelAdapter} produces:
     * {@code projectile_<matchId>} for the geometry and {@code projectile_<matchId>_<file>.png} for the textures. The
     * geometry and textures themselves are installed by the ordinary build and upload paths; this only records how to
     * find them.
     */
    private static void indexProjectiles(ResourceLocation modelId, ModelData data) {
        List<String> matchIds = new ArrayList<>();
        for (String key : data.getModel()
            .keySet()) {
            if (key.startsWith(PROJECTILE_KEY_PREFIX)) {
                String matchId = key.substring(PROJECTILE_KEY_PREFIX.length());
                if (!matchId.isEmpty()) {
                    matchIds.add(matchId);
                }
            }
        }
        if (matchIds.isEmpty()) {
            // Assigned rather than skipped, so a re-registration that drops the projectiles stops answering with the
            // previous payload's list.
            PROJECTILE_MODEL_IDS.remove(modelId);
            PROJECTILE_TEXTURE_IDS.remove(modelId);
            return;
        }
        PROJECTILE_MODEL_IDS.put(modelId, matchIds);

        List<ResourceLocation> projectileTextures = new ArrayList<>();
        for (String name : data.getTexture()
            .keySet()) {
            if (name.startsWith(PROJECTILE_KEY_PREFIX)) {
                projectileTextures.add(ModelIdUtil.getSubModelId(modelId, name));
            }
        }
        if (projectileTextures.isEmpty()) {
            PROJECTILE_TEXTURE_IDS.remove(modelId);
        } else {
            PROJECTILE_TEXTURE_IDS.put(modelId, projectileTextures);
        }
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info(
                "YSM client indexed projectile sub-entities for {}: match={}, textures={}",
                modelId,
                matchIds,
                projectileTextures.size());
        }
    }

    /**
     * Records the vehicle sub-entities a model declares, so {@code VehicleRenderer} can ask "does this model have a
     * vehicle for the entity I am drawing, and which texture is its own".
     * <p>
     * Both answers come out of the payload's key naming, which {@code RawYsmModelAdapter} produces:
     * {@code vehicle_<matchId>} for the geometry and {@code vehicle_<matchId>_<file>.png} for the textures. The match
     * id is kept exactly as the pack wrote it (a leading {@code #} included, e.g. {@code #minecraft:boat}), because
     * that same string is the one embedded in the sub-model id the renderer has to build.
     */
    private static void indexVehicles(ResourceLocation modelId, ModelData data) {
        List<String> matchIds = new ArrayList<>();
        for (String key : data.getModel()
            .keySet()) {
            if (key.startsWith(VEHICLE_KEY_PREFIX)) {
                String matchId = key.substring(VEHICLE_KEY_PREFIX.length());
                if (!matchId.isEmpty()) {
                    matchIds.add(matchId);
                }
            }
        }
        if (matchIds.isEmpty()) {
            // Assigned rather than skipped, so a re-registration that drops the vehicles stops answering with the
            // previous payload's list.
            VEHICLE_MODEL_IDS.remove(modelId);
            VEHICLE_TEXTURE_IDS.remove(modelId);
            return;
        }
        VEHICLE_MODEL_IDS.put(modelId, matchIds);

        List<ResourceLocation> vehicleTextures = new ArrayList<>();
        for (String name : data.getTexture()
            .keySet()) {
            if (name.startsWith(VEHICLE_KEY_PREFIX)) {
                vehicleTextures.add(ModelIdUtil.getSubModelId(modelId, name));
            }
        }
        if (vehicleTextures.isEmpty()) {
            VEHICLE_TEXTURE_IDS.remove(modelId);
        } else {
            VEHICLE_TEXTURE_IDS.put(modelId, vehicleTextures);
        }
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info(
                "YSM client indexed vehicle sub-entities for {}: match={}, textures={}",
                modelId,
                matchIds,
                vehicleTextures.size());
        }
    }

    /**
     * Uploads every texture of a model.
     * <p>
     * A texture the game cannot decode is <em>not</em> a failure here: {@code TextureManager.loadTexture} catches the
     * decode error, maps the id to its shared missing texture and returns false, and that id is still registered, so
     * the model publishes and shows a missing-texture patch - which is what {@code dev} did too. The return value is
     * therefore not "were they all decodable" but "did none of them fail abnormally", and only an abnormal failure -
     * a {@code Throwable} escaping the registration, which would otherwise take the client tick down - makes the
     * caller refuse the model.
     *
     * @return whether every texture got as far as the texture manager.
     */
    private static boolean uploadTextures(ResourceLocation id, Map<String, byte[]> mapData) {
        return uploadTextures(id, mapData, Collections.emptyMap());
    }

    /**
     * Uploads every texture of a model, on the client thread.
     *
     * @param decoded images already decoded on the loader thread, keyed like {@code mapData}; a texture missing from
     *                here is decoded on this thread, which is what the eager path and a failed decode do.
     * @return whether every texture got as far as the texture manager; see the note above about what that means.
     */
    private static boolean uploadTextures(ResourceLocation id, Map<String, byte[]> mapData,
        Map<String, BufferedImage> decoded) {
        return uploadTextures(id, mapData, decoded, Collections.emptyMap(), Collections.emptyMap());
    }

    private static boolean uploadTextures(ResourceLocation id, Map<String, byte[]> mapData,
        Map<String, BufferedImage> decoded, Map<String, Map<Integer, byte[]>> components,
        Map<String, Map<Integer, BufferedImage>> componentImages) {
        boolean complete = true;
        for (Map.Entry<String, byte[]> entry : mapData.entrySet()) {
            ResourceLocation textureId = ModelIdUtil.getSubModelId(id, entry.getKey());
            try {
                BufferedImage base = decoded.get(entry.getKey());
                if (base != null && componentImages.containsKey(entry.getKey())) {
                    ModelTextureRegistry.register(textureId, new OuterFileTexture(base, componentImages.get(entry.getKey())));
                } else if (components.containsKey(entry.getKey())) {
                    ModelTextureRegistry.register(textureId, new OuterFileTexture(entry.getValue(), components.get(entry.getKey())));
                } else registerTexture(textureId, entry.getValue(), base);
            } catch (Throwable failure) {
                // Throwable, like the other publish steps: an Error escaping into the client tick would take the tick
                // down, and there is nothing to do with this texture either way.
                complete = false;
                ysmu.LOG.warn("Failed to register texture {} for model {}", textureId, id, failure);
            }
        }
        ysmu.LOG.info("YSM client registered textures for {}: {}", id, MODELS.get(id));
        return complete;
    }


    /**
     * Registers one texture, uploading the image the loader thread already decoded when there is one, and hands its
     * lifecycle to {@link ModelTextureRegistry}.
     * <p>
     * Texture ids this client uploaded itself used to be tracked here in {@code UPLOADED_TEXTURES}, a timestamp-free
     * id set whose only job was to decide whether a re-registration may {@code deleteTexture}. That decision now
     * belongs to the release mechanism, which owns the same question and needs the answer anyway:
     * {@code CustomTextureManager.MinecraftHost} remembers, per id, whether the platform entry is this mod's own
     * upload - the {@code loadTexture} return value is what tells the two apart - and deletes the previous GL texture
     * only when it is. The reason the old set existed is unchanged and still honoured: an id the game mapped to its
     * shared missing texture is not ours to delete, and deleting it would blank every missing texture in the game.
     * <p>
     * The set was deliberately NOT a gate on what the renderer may bind (the game's texture manager legitimately
     * holds textures this mod never uploaded - the player's own skin for the built-in {@code steve}/{@code alex}
     * models, another mod's textures), and neither is its replacement: a model texture is checked against the model's
     * own list in {@code CustomPlayerModel.textureFor}, not against this bookkeeping.
     *
     * @param decoded the image prepared off-thread, or {@code null} to decode here; see {@link #buildModel}.
     */
    private static void registerTexture(ResourceLocation id, byte[] data, @Nullable BufferedImage decoded) {
        // R-01: loadTexture only replaces the map entry, so re-registering an id twice would leak the GL texture the
        // previous OuterFileTexture uploaded. That delete, and the "is this id ours to delete at all" decision, now
        // live in the release mechanism: CustomTextureManager.MinecraftHost.register deletes the id's previous GL
        // texture when the registration before it was this mod's own upload, and never when the game mapped the id to
        // its shared missing texture - whose GL name every missing texture in the game draws with, so deleting it
        // would blank them all. That replaces the timestamp-free ClientModelManager.UPLOADED_TEXTURES id set that
        // used to answer exactly this question (see .agent/phase34-texture-release.md).
        //
        // The throw stays handled by the caller (uploadTextures): a texture that cannot be registered is not a reason
        // to refuse the model.
        ModelTextureRegistry.register(id, decoded != null ? new OuterFileTexture(decoded) : new OuterFileTexture(data));
    }

    private static void registerAnimations(ResourceLocation id, Map<String, byte[]> mapData) {
        Map<ResourceLocation, AnimationFile> animations = GeckoLibCache.getInstance()
            .getAnimations();
        // A model's animation sets are split into *domains*, which is upstream's shape: the `fp_arm` set is the
        // first-person arm's own set and every other set belongs to the model proper
        // (DefaultAnimationKey.java:36-38 maps a `player` target's `fp_arm` set to `player/first-person` and
        // everything else to `player/main`; ModelRenderTargetLoader.java:243-253 collects the files into exactly
        // those two groups and bakes one animation store each). Installing one file per domain instead of one file
        // for the whole payload is what stops a later set from redefining an earlier set's clip: with a single
        // namespace, `fp_arm`'s `parallel0` (arms only, 0.2 s) replaced `main`'s (the model's body bones, 1.0 s) for
        // every pack that ships a real first-person set, and the built-in default's own `parallel0`/`parallel1` were
        // replaced by the empty `parallel0..7` stubs of its `fp.arm.animation.json`.
        Map<String, AnimationFile> filesByDomain = new LinkedHashMap<>();
        for (String domain : AnimationDomain.ALL) {
            filesByDomain.put(domain, new AnimationFile());
        }
        Map<String, byte[]> controllerFiles = new LinkedHashMap<>();
        // 动画名 → 该动画**自身** JSON 引用到的可选模组 modId 集合，注册控制器后交给
        // OpenYsmAnimationControllerRegistry.scanAnimKeyframesForDeps 展开依赖。
        // (reference branch ClientModelManager.java:1083-1115, :700-703)
        Map<String, Set<String>> animToModIds = new LinkedHashMap<>();
        // A projectile sub-entity's animation and controller files must NOT merge into the player's single animation
        // namespace: a pack's arrow animation set and its player set both define `parallel0`, and merging them lets
        // one overwrite the other for whichever is drawn second. Each projectile therefore gets its own AnimationFile
        // registered under its own sub-model id, which is what the projectile renderer looks up.
        ResourceLocation baseId = ModelIdUtil.getParentModelId(id);
        Map<ResourceLocation, byte[]> projectileControllerFiles = new LinkedHashMap<>();
        Map<ResourceLocation, byte[]> vehicleControllerFiles = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : mapData.entrySet()) {
            // The controller prefix is tested first: `projectile_ctrl_arrow` also starts with `projectile_`.
            if (entry.getKey()
                .startsWith(PROJECTILE_CONTROLLER_KEY_PREFIX)) {
                String matchId = entry.getKey()
                    .substring(PROJECTILE_CONTROLLER_KEY_PREFIX.length());
                byte[] bytes = entry.getValue();
                if (matchId.isEmpty() || bytes == null || bytes.length == 0) {
                    continue;
                }
                ResourceLocation projectileId = ModelIdUtil
                    .getSubModelId(baseId, PROJECTILE_KEY_PREFIX + matchId);
                projectileControllerFiles.put(projectileId, bytes);
                // Give the id an AnimationFile of its own even when the animation arrives in a separate entry: the
                // renderer returns early - and draws the bind pose - when this lookup misses.
                animations.computeIfAbsent(projectileId, ignored -> new AnimationFile());
                continue;
            }
            if (entry.getKey()
                .startsWith(PROJECTILE_KEY_PREFIX)) {
                String matchId = entry.getKey()
                    .substring(PROJECTILE_KEY_PREFIX.length());
                byte[] bytes = entry.getValue();
                if (matchId.isEmpty() || bytes == null || bytes.length == 0) {
                    continue;
                }
                ResourceLocation projectileId = ModelIdUtil
                    .getSubModelId(baseId, PROJECTILE_KEY_PREFIX + matchId);
                animations.put(projectileId, projectileAnimationFile(bytes, projectileId));
                continue;
            }
            // The vehicle family, same two branches and same reason as the projectile ones above: a pack's boat set
            // and its player set can both define a clip name (`water`, `forward`, ...), and merging them would let one
            // overwrite the other for whichever is drawn second. Each vehicle gets its own AnimationFile under its own
            // sub-model id. The controller prefix is tested first: `vehicle_ctrl_<match>` also starts with `vehicle_`.
            if (entry.getKey()
                .startsWith(VEHICLE_CONTROLLER_KEY_PREFIX)) {
                String matchId = entry.getKey()
                    .substring(VEHICLE_CONTROLLER_KEY_PREFIX.length());
                byte[] bytes = entry.getValue();
                if (matchId.isEmpty() || bytes == null || bytes.length == 0) {
                    continue;
                }
                ResourceLocation vehicleId = ModelIdUtil.getSubModelId(baseId, VEHICLE_KEY_PREFIX + matchId);
                vehicleControllerFiles.put(vehicleId, bytes);
                animations.computeIfAbsent(vehicleId, ignored -> new AnimationFile());
                continue;
            }
            if (entry.getKey()
                .startsWith(VEHICLE_KEY_PREFIX)) {
                String matchId = entry.getKey()
                    .substring(VEHICLE_KEY_PREFIX.length());
                byte[] bytes = entry.getValue();
                if (matchId.isEmpty() || bytes == null || bytes.length == 0) {
                    continue;
                }
                ResourceLocation vehicleId = ModelIdUtil.getSubModelId(baseId, VEHICLE_KEY_PREFIX + matchId);
                // Same parser as the projectile branch: it is "one sub-entity's own animation file, never merged into
                // the player's namespace", which is exactly what a vehicle needs too. Only its name and its log line
                // say projectile.
                animations.put(vehicleId, projectileAnimationFile(bytes, vehicleId));
                continue;
            }
            // Each file's bytes are decoded and parsed exactly once: that one JsonObject both answers "is this a
            // controller file?" and feeds the animation conversion. The previous shape parsed every file whose name
            // was not a controller name twice, because a probe parsed it to look for `animation_controllers` and the
            // conversion then parsed the same text again - 28.7 MB of JSON parsed twice over the reference catalog.
            if (YsmControllerResources.isControllerResource(entry.getKey())) {
                controllerFiles.put(entry.getKey(), entry.getValue());
                continue;
            }
            byte[] bytes = entry.getValue();
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            JsonObject jsonObject;
            try {
                jsonObject = GsonHelper
                    .fromJson(ysmu.GSON, new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            } catch (Exception e) {
                ysmu.LOG.warn(
                    "Failed to parse animation file {} for model {}: {}: {}",
                    entry.getKey(),
                    id,
                    e.getClass()
                        .getSimpleName(),
                    Objects.requireNonNullElse(e.getMessage(), ""));
                continue;
            }
            if (jsonObject == null) {
                continue;
            }
            if (jsonObject.has("animation_controllers")) {
                controllerFiles.put(entry.getKey(), bytes);
                continue;
            }
            // 逐动画扫描关键帧 Molang 表达式里对可选模组控制变量（如 ctrl.tac_hold_gun）的引用：
            // 控制器的 animation 条目可能只是裸动画名、自己不带任何 condition，引用藏在动画关键帧里。
            // 逐动画（而不是逐文件）是必须的：某个模型的 main.animation.json 里只有 sneak/sneaking
            // 引用 ctrl.tac_，逐文件标记会把 pre_parallel0-7/表情/衣服全部误标为 tacz 依赖，
            // 于是 tacz 未加载时 pre_parallel_0 控制器被整体跳过（衣服/表情永不播放）。
            // (reference branch ClientModelManager.java:1073-1115)
            for (Map.Entry<String, JsonElement> animationEntry : JsonAnimationUtils.getAnimations(jsonObject)) {
                JsonElement animationJsonElement = animationEntry.getValue();
                if (animationJsonElement == null || !animationJsonElement.isJsonObject()) {
                    continue;
                }
                String animationJson = animationJsonElement.toString();
                for (ModDependency dependency : ModDependencyRegistry.getAll()) {
                    if (dependency.matches(animationJson)) {
                        animToModIds.computeIfAbsent(animationEntry.getKey(), ignored -> new LinkedHashSet<>())
                            .add(dependency.getModId());
                    }
                }
            }
            mergeAnimationFile(
                filesByDomain.get(AnimationDomain.ofAnimationSet(entry.getKey())),
                animationFileFromJson(jsonObject));
        }
        // Each domain is completed from the built-in default model's *same* domain, upstream's per-domain fallback
        // (`defaultAnimations.fallback(domain)`, ModelRenderTargetLoader.java:375-379): a name the model defines
        // itself always wins, and a name it does not falls back to the default's clip for that domain.
        // Which names came from that fallback is remembered, because a name the model merely inherits is not this
        // model's to report: the built-in default model's own registration reports it once, and without this every
        // model that inherits an unclassifiable default clip (`hold_mainhand:lance` on all 22 wine_fox models, for
        // one) would warn about content its own files never mention.
        Map<String, Set<String>> inheritedFromDefault = new LinkedHashMap<>();
        for (String domain : AnimationDomain.ALL) {
            AnimationFile target = filesByDomain.get(domain);
            Set<String> inherited = new LinkedHashSet<>();
            defaultAnimationFile(domain).animations.forEach((name, action) -> {
                if (!target.animations.containsKey(name)) {
                    target.putAnimation(name, action);
                    inherited.add(name);
                }
            });
            inheritedFromDefault.put(domain, inherited);
        }
        // Condition names are registered per domain, under the id that domain's animatable reads, which is upstream's
        // two-store split (`ModelRenderTargetAssembler.java:53-60`: the player's own names into `conditionManager`, the
        // `fp_arm` set's into `fpArmConditionManager`). The body's controllers look their conditional names up with the
        // model's `main` id and the first-person arm's with the model's `arm` id
        // (`ConditionManager#armorFor`), so each table must hold exactly its own domain's names - a name the `fp_arm`
        // set declares is not a name the body's armor controller may play.
        filesByDomain.get(AnimationDomain.MAIN)
            .animations.forEach((name, animation) -> {
                try {
                    ConditionManager.addTest(
                        id,
                        name,
                        !inheritedFromDefault.get(AnimationDomain.MAIN)
                            .contains(name));
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to register animation condition {} for model {}", name, id, e);
                }
            });
        ResourceLocation firstPersonId = AnimationDomain.animationFileId(baseId, AnimationDomain.FIRST_PERSON);
        filesByDomain.get(AnimationDomain.FIRST_PERSON)
            .animations.forEach((name, animation) -> {
                try {
                    com.fox.ysmu.client.animation.condition.FPArmConditionManager.addTest(
                        firstPersonId,
                        name,
                        !inheritedFromDefault.get(AnimationDomain.FIRST_PERSON)
                            .contains(name));
                } catch (Exception e) {
                    ysmu.LOG.warn(
                        "Failed to register first-person animation condition {} for model {}",
                        name,
                        firstPersonId,
                        e);
                }
            });
        Set<String> installedAnimationNames = new LinkedHashSet<>();
        for (String domain : AnimationDomain.ALL) {
            AnimationFile file = filesByDomain.get(domain);
            installedAnimationNames.addAll(file.animations.keySet());
            animations.put(AnimationDomain.animationFileId(baseId, domain), file);
        }
        // The reference gates controller registration on the model actually declaring controller files
        // (REF ClientModelManager.java:690 `if (!bundle.controllerFiles.isEmpty())`). Without that gate every
        // built-in model gains a ControllerSet: the registry synthesises one controller per parallel animation
        // name, and the built-in `default` pack declares no controller/ at all while its main.animation.json
        // still defines pre_parallel0..7 and parallel0..7. Those 16 slots would leave the raw
        // playLoopAnimation path for the OpenYSM state machine. The registry cleared a stale set for this id
        // through the empty-file call; clearRuntimeModelCaches clears the whole registry per sync, and a
        // re-registration of the same id carries the same payload, so nothing goes stale here.
        if (!controllerFiles.isEmpty()) {
            try {
                // animationNames is the animation set installed just above: the model's own animations plus the
                // built-in defaults merged in, across both domains so a controller state that names a clip cannot
                // lose its route. That is the port's counterpart of the `resourceAdapter.getAnimations(model,
                // assets)` map OpenYSM's ParallelProcessor scans (ParallelControllerDiscovery.java:45).
                OpenYsmAnimationControllerRegistry.register(id, controllerFiles.values(), installedAnimationNames);
            } catch (Throwable failure) {
                // The animation set above is already installed, and the registry already handles a broken controller file
                // per file, so this only catches something escaping that - an Error. Letting it through would make the
                // caller report the whole model as failed when its animations are in fact usable.
                ysmu.LOG.warn("Failed to register OpenYSM controllers for model {}", id, failure);
            }
        }
        // 注册之后才展开依赖：scanAnimKeyframesForDeps 查的是已经建立的 ControllerSet。
        // (reference branch ClientModelManager.java:697-703)
        if (!animToModIds.isEmpty()) {
            try {
                OpenYsmAnimationControllerRegistry.scanAnimKeyframesForDeps(id, animToModIds);
            } catch (Throwable failure) {
                // 依赖展开只是优化（未加载模组的控制器被跳过）；失败时控制器照常按自己的条件运行，
                // 不能因此把整个模型的注册判为失败。
                ysmu.LOG.warn("Failed to scan animation keyframes for mod dependencies for model {}", id, failure);
            }
        }
        // The projectile controllers live under the projectile's own sub-model id, which is also the id its geometry
        // and its AnimationFile are registered under.
        for (Map.Entry<ResourceLocation, byte[]> entry : projectileControllerFiles.entrySet()) {
            try {
                OpenYsmAnimationControllerRegistry.register(
                    entry.getKey(),
                    java.util.Collections.singletonList(entry.getValue()));
            } catch (Throwable failure) {
                ysmu.LOG.warn("Failed to register projectile controllers for {}", entry.getKey(), failure);
            }
        }
        // The vehicle controllers live under the vehicle's own sub-model id, same rule as the projectile ones.
        for (Map.Entry<ResourceLocation, byte[]> entry : vehicleControllerFiles.entrySet()) {
            try {
                OpenYsmAnimationControllerRegistry.register(
                    entry.getKey(),
                    java.util.Collections.singletonList(entry.getValue()));
            } catch (Throwable failure) {
                ysmu.LOG.warn("Failed to register vehicle controllers for {}", entry.getKey(), failure);
            }
        }
        ysmu.LOG.info(
            "YSM client registered animations for {}: count={}",
            id,
            filesByDomain.get(AnimationDomain.MAIN).animations.size());
    }

    /** The built-in default model's animations for one domain; see {@link AnimationDomain}. */
    private static AnimationFile defaultAnimationFile(String domain) {
        return AnimationDomain.isFirstPerson(domain) ? DEFAULT_FIRST_PERSON_ANIMATION_FILE
            : DEFAULT_ANIMATION_FILE;
    }

    /** One projectile's animation file, parsed and never merged into the player's namespace. */
    private static AnimationFile projectileAnimationFile(byte[] bytes, ResourceLocation projectileId) {
        try {
            JsonObject jsonObject = GsonHelper
                .fromJson(ysmu.GSON, new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            return jsonObject == null ? new AnimationFile() : animationFileFromJson(jsonObject);
        } catch (Exception e) {
            ysmu.LOG.warn(
                "Failed to parse projectile animation for {}: {}: {}",
                projectileId,
                e.getClass()
                    .getSimpleName(),
                Objects.requireNonNullElse(e.getMessage(), ""));
            return new AnimationFile();
        }
    }

    private static AnimationFile getAnimationFile(String file) {
        JsonObject jsonObject = GsonHelper.fromJson(ysmu.GSON, file, JsonObject.class);
        return jsonObject == null ? new AnimationFile() : animationFileFromJson(jsonObject);
    }

    /**
     * Converts every animation in one already-parsed animation file.
     * <p>
     * This is the expensive half - one {@code KeyFrame} per channel per keyframe time, each carrying a MoLang
     * expression - which is why the file is parsed once by the caller and only the resulting object is handed here.
     */
    private static AnimationFile animationFileFromJson(JsonObject jsonObject) {
        AnimationFile animationFile = new AnimationFile();
        MolangParser parser = GeckoLibCache.getInstance().parser;
        for (Map.Entry<String, JsonElement> entry : JsonAnimationUtils.getAnimations(jsonObject)) {
            String animationName = entry.getKey();
            Animation animation;
            try {
                Map.Entry<String, JsonElement> animEntry = JsonAnimationUtils.getAnimation(jsonObject, animationName);
                animation = JsonAnimationUtils.deserializeJsonToAnimation(animEntry, parser);
                attachAnimSpeed(animation, animEntry);
                animationFile.putAnimation(animationName, animation);
            } catch (Exception e) {
                ysmu.LOG.warn(
                    "Failed to register animation {}: {}: {}",
                    animationName,
                    e.getClass()
                        .getSimpleName(),
                    Objects.requireNonNullElse(e.getMessage(), ""));
            }
        }
        return animationFile;
    }

    /**
     * YSMU: 把模型动画 JSON 的 {@code anim_speed}（逐动画播放倍率，数字或 MoLang 表达式）与
     * {@code anim_time_update}（自定义时间推进）挂到引擎 {@link Animation#animSpeed} /
     * {@link Animation#animTimeUpdate}。这是全仓对这两个字段的唯一写入点：前者由
     * {@code MovementSpeedMatcher.animSpeedFor} 消费，后者由 {@code AnimationController} 的时钟推进消费。
     * <p>
     * 形状照参考分支 {@code ClientModelManager.java:1275-1279}（它的 {@code getAnimationFile}），
     * 但参考只写了 {@code anim_speed}。
     * <p>
     * <b>两条通道都读得到</b>：legacy 通道上散件模型的动画 JSON 是原样透传的
     * （{@code FolderFormat}/{@code YsmFormat} 直接读文件字节；{@code RawYsmModelAdapter.putAnimationFile}
     * 优先使用 {@code RawAnimationFile.sourceJson}），JSON 里的两个字段一直都在；OpenYSM 二进制通道上
     * {@code sourceJson} 不存在，所以 JSON 是 {@code RawYsmModelAdapter.createAnimationJson} 从结构重新生成的，
     * 而那两个字段现在既在 {@code RawYsmModel.RawAnimation} 里、也被二进制序列化器写出并被
     * {@code createAnimationJson} 回写（{@code YSMBinarySerializer.writeAnimationFileContent} 尾部的两个
     * {@code writeOptionalString}）。在此之前它们只在 legacy 通道存活，而默认通道是二进制通道。
     */
    private static void attachAnimSpeed(Animation animation, Map.Entry<String, JsonElement> animEntry) {
        if (animEntry == null || !animEntry.getValue().isJsonObject()) {
            return;
        }
        JsonObject animObject = animEntry.getValue().getAsJsonObject();
        JsonElement speed = animObject.get("anim_speed");
        if (speed != null && speed.isJsonPrimitive()) {
            animation.animSpeed = speed.getAsString();
        }
        JsonElement weight = animObject.get("blend_weight");
        if (weight != null && weight.isJsonPrimitive()) {
            animation.blendWeight = weight.getAsString();
        }
        JsonElement timeUpdate = animObject.get("anim_time_update");
        if (timeUpdate != null && timeUpdate.isJsonPrimitive()) {
            animation.animTimeUpdate = timeUpdate.getAsString();
        }
    }

    private static AnimationFile mergeAnimationFile(AnimationFile main, AnimationFile other) {
        other.animations.forEach(main::putAnimation);
        return main;
    }

    public static void loadDefaultModel() {
        // M1/D7:内置 default 现在是 config/ysmu/builtin/default 下的现代 ysm.json 包，config/ysmu/custom 只是用户
        // 自己的根目录，新装机器上并没有 custom/default；仅按旧位置读会让这个方法在全新安装上直接抛
        // FileNotFoundException，DEFAULT_ANIMATION_FILE 于是永远是空的。形状照 SOURCE
        // ClientModelManager:1319-1348：先试旧布局，再回退到内置包。
        ModelData data = null;
        try {
            data = FolderFormat.getModelData(ServerModelManager.CUSTOM, "default");
        } catch (IOException e) {
            ysmu.LOG.info("Legacy default model not found at CUSTOM/default, trying the built-in OpenYSM pack...");
        }
        if (data == null) {
            // 新装机器走的就是这一支。
            Path builtinDefault = ServerModelManager.BUILT.resolve("default");
            if (Files.isDirectory(builtinDefault)) {
                try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(builtinDefault)) {
                    RawYsmModel raw = deserializer.deserialize();
                    raw.modelId = "default";
                    // SOURCE 在 :1336-1340 手动把 default_texture 填进它自己的 DEFAULT_TEXTURE 表，因为它这条路径
                    // 绕开了会登记该字段的 OpenYSM 同步路径。本端不需要这一步：RawYsmModelAdapter 把
                    // default_texture 写进几何的 ysm_default_texture，下面的 registerAll(eager) -> publishGeo 会
                    // 照常把它读进 DECLARED_DEFAULT_TEXTURES。
                    if (RawYsmModelAdapter.isBridgeable(raw)) {
                        data = RawYsmModelAdapter.toLegacyModelData(raw, "default");
                    }
                } catch (Exception e2) {
                    ysmu.LOG.warn("Failed to load default model from the built-in OpenYSM pack", e2);
                }
            }
        }
        if (data != null) {
            data.getAnimation()
                .forEach((name, bytes) -> {
                    AnimationFile animationFile = getAnimationFile(new String(bytes, StandardCharsets.UTF_8));
                    // Domain-split like any other model's payload: the default model's own `fp_arm` set is the
                    // first-person fallback, not one more main-domain set. See {@link AnimationDomain}.
                    mergeAnimationFile(defaultAnimationFile(AnimationDomain.ofAnimationSet(name)), animationFile);
                });
            // Eager: this is the substitute every not-yet-built model is drawn with, so it has to exist before any
            // other model asks for it.
            ClientModelManager.registerAll(data, true);
        } else {
            ysmu.LOG.warn("Failed to load default model from any format");
        }
    }

    /**
     * Asks the server which of its models this client already has cached.
     * <p>
     * CUI-01: the packet handler that reaches this method runs on a network thread, while {@code theWorld}, the
     * runtime caches and the cache directory all belong to the client thread, so the work is scheduled there.
     */
    public static void sendSyncModelMessage() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.func_152345_ab()) {
            // Already on the client thread; func_152344_a would run this inline anyway.
            ClientModelManager.prepareModelSync();
            return;
        }
        mc.func_152344_a(ClientModelManager::prepareModelSync);
    }

    /** Client thread: resets the sync state, clears the runtime caches, then reads the cache directory. */
    private static void prepareModelSync() {
        final long generation;
        synchronized (com.fox.ysmu.network.message.RequestLoadModel.class) {
            generation = SYNC_GENERATION + 1L;
            SYNC_GENERATION = generation;
            PASSWORD = null;
            PASSWORD_UUID = null;
            com.fox.ysmu.network.message.RequestLoadModel.clearPendingLoads();
        }
        pendingModelSync = null;
        ysmu.LOG.info(
            "YSM client starting model sync: currentModels={}, rememberedCachedModels={}",
            MODELS.size(),
            CACHE_MD5.size());
        clearCachedModelMd5();
        // CUI-01: the cache reset and the cache-directory listing run in this one client task, in this order, so
        // the reply describes the state the client is actually in when it is built.
        clearRuntimeModelCaches();
        String[] candidates = getMd5Info();
        long deadline = System.currentTimeMillis() + SYNC_WORLD_WAIT_MILLIS;
        // M-10: a name is only a claim until the bytes behind it are checked, and that is file IO. Verify on the
        // model pool - without ever waiting there - and reschedule the reply instead of blocking the client thread.
        Runnable verification = () -> verifyCachedModelFiles(candidates, generation, deadline);
        ThreadTools.THREAD_POOL.submit(verification);
    }

    /** Pool thread: keeps the names whose file content really hashes to them, then reschedules the reply. */
    private static void verifyCachedModelFiles(String[] candidates, long generation, long deadline) {
        List<String> verified = new ArrayList<>(candidates.length);
        for (String name : candidates) {
            if (generation != SYNC_GENERATION || Thread.currentThread().isInterrupted()) {
                return;
            }
            File file = ServerModelManager.CACHE_CLIENT.resolve(name)
                .toFile();
            if (contentMatchesFileName(file, name)) {
                verified.add(name);
            } else {
                // A crash or a disconnect can leave a half-written file under a full MD5 name; reporting it would
                // make the server grant a load request that can never succeed, and it would never resend the file.
                ysmu.LOG.warn("YSM client discarding stale model cache file {}: its content is not {}", file, name);
            }
        }
        String[] md5Info = verified.toArray(new String[0]);
        // Called from the pool, so func_152344_a queues and the client thread runs it on its next tick.
        Minecraft.getMinecraft()
            .func_152344_a(() -> armModelSyncReply(md5Info, generation, deadline));
    }

    /** Client thread: makes this reply the current one and tries to send it. */
    private static void armModelSyncReply(String[] md5Info, long generation, long deadline) {
        if (generation != SYNC_GENERATION) {
            return;
        }
        PendingModelSync send = new PendingModelSync(md5Info, deadline);
        pendingModelSync = send;
        trySendModelSync(send);
    }

    /** Client thread: sends the reply once a world exists, or re-arms the bounded, tick-driven wait below. */
    private static void trySendModelSync(PendingModelSync send) {
        if (send == null || send != pendingModelSync) {
            return;
        }
        if (Minecraft.getMinecraft().theWorld != null) {
            pendingModelSync = null;
            ysmu.LOG.info(
                "YSM client sending model sync md5 list: count={}, values={}",
                send.md5Info.length,
                Lists.newArrayList(send.md5Info));
            NetworkHandler.CHANNEL.sendToServer(new SyncModelFiles(send.md5Info));
            return;
        }
        // N-12/M-06: this used to sleep on the model pool until the world was ready. Waiting is now free instead of
        // occupying the pool: each attempt is one client tick, and it is abandoned after a deadline.
        if (Thread.currentThread().isInterrupted()) {
            pendingModelSync = null;
            ysmu.LOG.warn("YSM client model sync cancelled before a client world existed");
            return;
        }
        if (System.currentTimeMillis() >= send.deadline) {
            pendingModelSync = null;
            ysmu.LOG.warn(
                "YSM client gave up sending its model sync after {} ms without a client world",
                SYNC_WORLD_WAIT_MILLIS);
            return;
        }
        scheduleModelSyncRetry();
    }

    /**
     * Re-arms {@link #trySendModelSync} once through the client task queue. {@code func_152344_a} runs its runnable
     * inline when the caller already is the client thread, so this hop goes through the model pool: the queued
     * runnable is then executed by the client thread on its next tick, which is what makes the wait tick-driven.
     */
    private static void scheduleModelSyncRetry() {
        if (!SYNC_RETRY_QUEUED.compareAndSet(false, true)) {
            return;
        }
        Runnable hop = () -> Minecraft.getMinecraft()
            .func_152344_a(() -> {
                SYNC_RETRY_QUEUED.set(false);
                // Re-read the field: a newer sync request may have replaced the reply this hop was armed for.
                PendingModelSync pending = pendingModelSync;
                if (pending != null) {
                    trySendModelSync(pending);
                }
            });
        ThreadTools.THREAD_POOL.submit(hop);
    }

    /** A model sync reply that is waiting for {@code theWorld}, with the point in time it stops being worth sending. */
    private static final class PendingModelSync {

        private final String[] md5Info;
        private final long deadline;

        private PendingModelSync(String[] md5Info, long deadline) {
            this.md5Info = md5Info;
            this.deadline = deadline;
        }
    }

    /**
     * Names of the cache files this client may claim to have.
     * <p>
     * N-07/M-10: a cache file is named after the uppercase hex MD5 that {@code ModelCacheWriter} and
     * {@code SendModelFile} computed over its bytes, so anything that is not 32 hex characters - above all a
     * {@code <md5>.part} file left behind by an interrupted chunked upload - is not a cache hit and must not be
     * reported as one. Whether the content really matches the name is checked separately, off the client thread.
     */
    private static String[] getMd5Info() {
        File cacheDir = ServerModelManager.CACHE_CLIENT.toFile();
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            ysmu.LOG.warn("Failed to create YSM client model cache directory: {}", cacheDir);
            return new String[0];
        }
        Collection<File> files = FileUtils.listFiles(cacheDir, FileFileFilter.FILE, null);
        List<String> output = new ArrayList<>(files.size());
        List<String> ignored = Lists.newArrayList();
        for (File file : files) {
            String name = file.getName();
            if (isCacheMd5Name(name)) {
                output.add(name);
            } else {
                ignored.add(name);
            }
        }
        if (!ignored.isEmpty()) {
            ysmu.LOG.info(
                "YSM client ignoring {} file(s) in the model cache that are not named after an MD5: {}",
                ignored.size(),
                ignored);
        }
        return output.toArray(new String[0]);
    }

    /** Whether a file name is a plain 32 character hex MD5, the only shape a cache file can legitimately have. */
    private static boolean isCacheMd5Name(String name) {
        if (name == null || name.length() != MD5_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < MD5_HEX_LENGTH; i++) {
            char c = name.charAt(i);
            boolean hex = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** Whether the file's bytes hash to the name they are stored under, which is the criterion the writer used. */
    private static boolean contentMatchesFileName(File file, String name) {
        if (!file.isFile() || file.length() == 0L) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream input = FileUtils.openInputStream(file)) {
                byte[] buffer = new byte[CACHE_HASH_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return Md5Utils.toHexString(digest.digest())
                .equalsIgnoreCase(name);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to hash YSM client model cache file " + file, e);
            return false;
        }
    }

    /**
     * 后台线程：从加密客户端缓存解密并反序列化模型（RawYsmModel）。
     * 供按需懒加载使用（音效首次播放）；返回 null 表示缺失/损坏。
     * legacy 格式（EncryptTools）不携带 soundFiles，返回 null。
     * <p>Ported from the reference branch's {@code loadRawModelFromCache}; the only
     * difference is the deserializer's format argument, which is the same literal
     * {@code 32} ({@code OpenYsmFormat.OPEN_YSM_SYNC_FORMAT}) this port's sync path
     * already passes, because this port's {@code OpenYsmFormat} does not declare the
     * named constant.
     *
     * @param mainModelId 主模型 id（如 "ysmu:model_id/main"）
     */
    @Nullable
    public static com.fox.ysmu.model.resource.pojo.RawYsmModel loadRawModelFromCache(ResourceLocation mainModelId) {
        if (!OPENYSM_CACHE_FORMAT.contains(mainModelId)) {
            return null;
        }
        String path = CACHED_MODEL_MD5.get(mainModelId);
        if (path == null || path.isEmpty()) return null;
        try {
            java.io.File cacheFile = ServerModelManager.CACHE_CLIENT.resolve(path).toFile();
            if (!cacheFile.isFile()) return null;
            byte[] fileBytes = org.apache.commons.io.FileUtils.readFileToByteArray(cacheFile);
            byte[] clearBytes = com.fox.ysmu.client.sync.OpenYsmModelSyncClient.readClientCacheToClearBytes(fileBytes);
            if (clearBytes == null) return null;
            try (com.fox.ysmu.model.resource.YSMBinaryDeserializer deserializer =
                     new com.fox.ysmu.model.resource.YSMBinaryDeserializer(clearBytes,
                         32)) {
                com.fox.ysmu.model.resource.pojo.RawYsmModel raw = deserializer.deserializeKeepOpen();
                deserializer.parseYSMFooter(raw);
                raw.modelId = com.fox.ysmu.util.ModelIdUtil.getModelIdFromMainId(mainModelId).getResourcePath();
                // 后台线程预暖音效：模型首次使用时顺带把音效字节填入内存缓存，
                // 使首播不再在主线程解密（消除 ~0.5s 卡顿）。
                com.fox.ysmu.client.audio.YSMSoundManager.cacheModelSounds(mainModelId, raw);
                return raw;
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load raw model from cache for {}: {}", mainModelId, e.getMessage());
            return null;
        }
    }

    /**
     * 后台线程：把一个模型在加密客户端缓存里的 payload 重新解密并桥接成 legacy {@link ModelData}。
     * 不写任何缓存。
     * <p>
     * 这是参考分支 {@code loadLegacyModelData}（参考 {@code ClientModelManager.java:1484-1508}）在本仓的
     * 等价物，只是本仓只有 OpenYSM 那半边：legacy（{@code EncryptTools} + 同步密码）通道的
     * 「模型 → 缓存文件」关联记在 {@code network/message/RequestLoadModel}，而那个事实就是
     * {@link #hasReloadableClientCache} 这个采纳门所依据的东西（见它的说明）。几何与动画两个 provider 的
     * {@code load} 都走这一个桥，与参考的 {@code GeoModelProvider}/{@code AnimationProvider} 共用
     * {@code loadLegacyModelData} 是同一个形状。
     *
     * @param mainModelId 主模型 id（如 "ysmu:model_id/main"）
     * @return 桥接后的 payload；缺失、无法解密或无法桥接时返回 null
     */
    @Nullable
    public static ModelData loadLegacyModelData(ResourceLocation mainModelId) {
        if (mainModelId == null) {
            return null;
        }
        com.fox.ysmu.model.resource.pojo.RawYsmModel raw = loadRawModelFromCache(mainModelId);
        if (raw == null) {
            return null;
        }
        try {
            return RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to bridge cached model data for {}: {}", mainModelId, e.getMessage());
            return null;
        }
    }

    /**
     * 后台线程：从加密客户端缓存重解析单个 sub-model 的几何。
     * 不写任何缓存；由 {@link com.fox.ysmu.client.asset.AssetManager} /
     * {@code provider.GeoModelProvider} 在主线程 apply。
     * <p>
     * Reference {@code ClientModelManager.java:1616-1637}. The payload comes from
     * {@link #loadLegacyModelData}, which is the reference's own bridge minus the legacy channel this
     * port cannot re-read (see {@link #hasReloadableClientCache}), and it is the same call
     * {@code provider/AnimationProvider} makes. The
     * sub-model lookup is the reference's own rule ({@code ModelIdUtil.getSubModelId(baseId, key)}
     * compared to {@code geoId}) and this port's {@link #buildGeo} is the same bake
     * {@code publishGeo} installs, so a reloaded geometry is byte-for-byte the object the
     * original build produced.
     *
     * @param geoId sub-model id（如 "ysmu:model_id/main"、"ysmu:model_id/arm"）
     * @return 解析出的 GeoModel；缺失/损坏/本仓无法重载时返回 null
     */
    @Nullable
    public static GeoModel parseSingleGeoFromCache(ResourceLocation geoId) {
        if (geoId == null) {
            return null;
        }
        // geoId is a sub-model id ("ysmu:model/main" or "ysmu:model/arm"). Derive the
        // base id, then the main id for the encrypted-cache lookup. Calling
        // getMainId(geoId) directly would append "/main" a second time (e.g.
        // "ysmu:model/main/main") and miss the CACHED_MODEL_MD5 entry — silently
        // failing every background reload (vanilla hand / missing model after idle).
        ResourceLocation baseId = ModelIdUtil.getParentModelId(geoId);
        ModelData data = loadLegacyModelData(ModelIdUtil.getMainId(baseId));
        if (data == null) {
            return null;
        }
        Map<String, byte[]> modelBytes = data.getModel();
        if (modelBytes == null || modelBytes.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, byte[]> entry : modelBytes.entrySet()) {
            ResourceLocation subId = ModelIdUtil.getSubModelId(baseId, entry.getKey());
            if (geoId.equals(subId)) {
                BuiltGeo built = buildGeo(subId, entry.getValue());
                return built == null ? null : built.model;
            }
        }
        return null;
    }

    private static void clearRuntimeModelCaches() {
        ysmu.LOG.info(
            "YSM client clearing runtime model caches: models={}, scales={}, extraInfo={}, extraAnimations={}",
            MODELS.size(),
            SCALE_INFO.size(),
            EXTRA_INFO.size(),
            EXTRA_ANIMATION_NAME.size());
        MODELS.clear();
        // The geometry state and the tables it produces are serialised against the loader thread, which can be inside
        // a refusal while this runs; a refusal landing after the clear would leave GEOMETRY_FAILED entries describing
        // a connection that has ended.
        synchronized (GEOMETRY_STATE_LOCK) {
            SCALE_INFO.clear();
            RENDER_LAYERS_FIRST.clear();
        DECLARED_DEFAULT_TEXTURES.clear();
        TRANSLUCENT_TEXTURES.clear();
            EXTRA_INFO.clear();
            EXTRA_ANIMATION_NAME.clear();
            PENDING_ANIMATIONS.clear();
            PENDING_GEOMETRY.clear();
            COMPLETED_BUILDS.clear();
            GEOMETRY_REQUESTED.clear();
            GEOMETRY_READY.clear();
            GEOMETRY_FAILED.clear();
        }
        // The content signatures describe exactly the models being dropped here, so they go with them; otherwise a
        // model that is still identical to the previous server's copy would be skipped into an empty cache.
        REGISTERED_MODEL_CONTENT.clear();
        // The pack table belongs to the server whose models are being dropped; keeping it would label the next
        // server's folders with the previous server's names and cover art.
        ClientPackRegistry.clear();
        ClientModelMetadataRegistry.clear();
        PackUserFunctions.clear();
        // The preview animations belong to the models being dropped; keeping them would make the selection GUI ask the
        // next server's models for animations they never declared.
        com.fox.ysmu.client.gui.ModelPreviewRegistry.clear();
        // The declared settings panels belong to the models being dropped as well, and for the same reason.
        com.fox.ysmu.client.gui.ModelConfigRegistry.clear();
        com.fox.ysmu.client.roaming.ModelFormExecution.clear();
        // The GUI presentation images belong to the dropped models, and their textures have to be released explicitly
        // for the reason on guiImageTextureFor.
        clearGuiImages();
        // Roaming namespaces are keyed by model content hash, so they belong to the dropped models too.
        com.fox.ysmu.client.roaming.ClientRoamingKeys.clear();
        com.fox.ysmu.client.roaming.ClientRoamingStore.clear();
        com.fox.ysmu.client.gui.ModelPreviewAnimationState.resetAll();
        // The registered sound names are keyed by main model id and belong to the models being dropped here, so they
        // go with them; the reference branch clears the same registry at this point
        // (ClientModelManager.clearRuntimeModelCaches → YSMSoundManager.clear()).
        com.fox.ysmu.client.audio.YSMSoundManager.clear();
        ConditionManager.clear();
        OpenYsmAnimationControllerRegistry.clear();
        MolangPhysicsRuntime.clear();
        MolangInstructionExecutor.clearWarnings();
        MolangInstructionExecutor.clearTimelineLog();
        // The projectile index belongs to the models being dropped, and so does the per-projectile state those models
        // were driving.
        PROJECTILE_MODEL_IDS.clear();
        PROJECTILE_TEXTURE_IDS.clear();
        // The vehicle index belongs to the models being dropped for the same reason.
        VEHICLE_MODEL_IDS.clear();
        VEHICLE_TEXTURE_IDS.clear();
        com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.clear();
        com.fox.ysmu.client.animation.controller.ProjectileTimelineRuntime.clear();
        com.fox.ysmu.client.renderer.ArrowProjectileRenderer.clearDumpedTrees();
        // The vehicle renderer's once-per-message warnings belong to the models being dropped too, so a reload reports
        // a still-broken vehicle again instead of staying silent.
        com.fox.ysmu.client.renderer.VehicleRenderer.clearWarnings();
        // Reference wiring: the reference releases the framework's assets at this point too
        // (reference ClientModelManager.java:2036 `AssetManager.clearAll()`). No-op while the
        // switch is off, in which case the caches are empty anyway.
        com.fox.ysmu.client.asset.AssetManager.clearAll();
        // 防滑步的每玩家平滑倍速状态与表达式解析缓存随模型清空一起丢掉，避免换世界/重同步时
        // 把上一个世界的倍速带到下一个。参考分支在同一个位置调用
        // （reference ClientModelManager.java:2040 `MovementSpeedMatcher.clearAll()`）。
        com.fox.ysmu.client.animation.MovementSpeedMatcher.clearAll();
        // The model textures belong to the models being dropped here, so their registrations are released with them:
        // this is upstream's explicit release trigger (upstream CustomTextureManager.release) driven from the port's
        // existing model-unload path, and it is what makes the holders unreachable - the reachability trigger alone
        // could never fire, because 1.7.10's texture map keeps every registered texture alive. Each release is still
        // delayed by CustomTextureManager.DEFAULT_REMOVAL_DELAY_TICKS on the client tick, like upstream's. Runs after
        // the model list was cleared above, so no render site can still resolve one of these ids
        // (CustomPlayerModel.textureFor reads MODELS). See .agent/phase34-texture-release.md.
        ModelTextureRegistry.clear();
        // What each entity is currently shown with belongs to the model list being dropped here: upstream releases the
        // applied target with the catalog content it came from (EntityModelBinding:297-302), and this port's clear is
        // the whole list, so every record goes with it and the next frames draw the built-in default exactly as
        // upstream does during session negotiation. What the *entities* are then re-drawn from is decided by
        // CustomPlayerRenderer#resolveDrawnModel.
        com.fox.ysmu.client.render.AppliedModelRegistry.clear();
    }

    /**
     * The current connection's generation; see {@link #clearConnectionState}.
     * <p>
     * Exposed so work that is prepared off the client thread can state which connection it belongs to and be dropped
     * if that connection ended meanwhile. The generation guarded finished builds; a registration that straddles a
     * server switch needs the same treatment, because by the time it lands the parked tables belong to the new
     * server and a build requested from them would be stamped with the new generation.
     */
    public static long currentGeneration() {
        return SYNC_GENERATION;
    }

    public static void rememberCachedModel(String md5) {
        synchronized (CACHE_MD5) {
            if (!CACHE_MD5.contains(md5)) {
                CACHE_MD5.add(md5);
            }
        }
    }

    /** Records the cache file path (OpenYSM encrypted format) for a model so its own
     *  sound bytes can be re-read and re-decrypted on demand, after the parsed payload
     *  that carried them is gone. Called from the sync parse path with the relative path
     *  the payload's file was written under. */
    public static void rememberOpenYsmModelCache(ResourceLocation modelId, String cachePath) {
        if (modelId != null && cachePath != null && !cachePath.isEmpty()) {
            ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
            CACHED_MODEL_MD5.put(mainId, cachePath);
            OPENYSM_CACHE_FORMAT.add(mainId);
        }
    }

    public static List<String> getCachedModelSnapshot() {
        synchronized (CACHE_MD5) {
            return new ArrayList<>(CACHE_MD5);
        }
    }

    public static void clearConnectionState() {
        PASSWORD = null;
        PASSWORD_UUID = null;
        // A reply still waiting for a world belongs to the connection that just ended, so drop it and invalidate the
        // in-flight verification with it.
        SYNC_GENERATION++;
        pendingModelSync = null;
        clearCachedModelMd5();
        // Drop the geometry state here, under the lock, instead of leaving it to the next sync's clear. These
        // structures are all concurrent, so writing them from whichever thread raises the disconnect is safe, and
        // dropping them is what keeps a payload parked for the connection that just ended from being built later and
        // published as the *next* connection's model: the generation is stamped when a build is submitted, not when
        // its payload was parked, so the generation check alone cannot cover a payload that simply stayed parked.
        synchronized (GEOMETRY_STATE_LOCK) {
            PENDING_GEOMETRY.clear();
            PENDING_ANIMATIONS.clear();
            GEOMETRY_REQUESTED.clear();
            GEOMETRY_READY.clear();
            GEOMETRY_FAILED.clear();
            COMPLETED_BUILDS.clear();
        }
        // The plain HashMaps are deliberately not touched here - MODELS, SCALE_INFO, RENDER_LAYERS_FIRST, EXTRA_INFO
        // and EXTRA_ANIMATION_NAME are read by the render thread and this method runs on the thread that raises the
        // disconnect. Dropping those is left to prepareModelSync, on the client thread at the start of the next sync.
        OpenYsmModelSyncClient.clearConnectionState();
    }

    private static void clearCachedModelMd5() {
        synchronized (CACHE_MD5) {
            CACHE_MD5.clear();
        }
        // The "model → encrypted cache file" map describes the models of the connection that just ended (or that is
        // about to start a new sync round), and so does the format flag beside it.
        CACHED_MODEL_MD5.clear();
        OPENYSM_CACHE_FORMAT.clear();
    }

    private static byte[] getBytes(Path root, String fileName) throws IOException {
        return FileUtils.readFileToByteArray(
            root.resolve(fileName)
                .toFile());
    }

    @Nullable
    private static List<IChatComponent> handleExtraInfo(ResourceLocation id, @Nullable ExtraInfo extraInfo) {
        if (extraInfo == null || StringUtils.isBlank(extraInfo.getName())) {
            return null;
        }
        List<IChatComponent> component = Lists.newArrayList();
        IChatComponent textComponent = new ChatComponentText(extraInfo.getName());
        textComponent.getChatStyle()
            .setColor(EnumChatFormatting.GOLD);
        component.add(textComponent);
        if (StringUtils.isNoneBlank(extraInfo.getTips())) {
            String[] split = extraInfo.getTips()
                .split("\n");
            for (String s : split) {
                IChatComponent lineComponent = new ChatComponentText(s);
                lineComponent.getChatStyle()
                    .setColor(EnumChatFormatting.GRAY);
                component.add(lineComponent);
            }
        }
        if (extraInfo.getAuthors() != null && extraInfo.getAuthors().length != 0) {
            component.add(
                new ChatComponentTranslation(
                    "gui.yes_steve_model.model.authors",
                    StringUtils.join(extraInfo.getAuthors(), "丨")));
        }
        if (StringUtils.isNoneBlank(extraInfo.getLicense())) {
            component.add(new ChatComponentTranslation("gui.yes_steve_model.model.license", extraInfo.getLicense()));
        }
        return component;
    }
}
