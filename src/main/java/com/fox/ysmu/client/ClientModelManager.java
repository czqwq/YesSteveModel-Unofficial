package com.fox.ysmu.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.condition.ConditionManager;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;
import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.resource.YsmControllerResources;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SyncModelFiles;
import com.fox.ysmu.util.GsonHelper;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
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
import software.bernie.geckolib3.geo.render.built.GeoModel;
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
    public static Map<ResourceLocation, List<IChatComponent>> EXTRA_INFO = Maps.newHashMap();
    public static Map<ResourceLocation, String[]> EXTRA_ANIMATION_NAME = Maps.newHashMap();
    public static AnimationFile DEFAULT_ANIMATION_FILE = new AnimationFile();
    public static List<String> CACHE_MD5 = Collections.synchronizedList(Lists.newArrayList());
    public static volatile byte[] PASSWORD;
    public static volatile UUID PASSWORD_UUID;
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
        if (eager) {
            installNow(data);
        } else {
            parkGeometry(modelId, data);
            // The animations are the largest part of the payload and the only part nothing needs in order to list
            // the models, so they are parked and installed on the first request for one of them.
            deferAnimations(modelId, data);
        }
        ysmu.LOG.info(
            "YSM client registered model {}: totalModelEntries={}, textureCount={}",
            modelId,
            MODELS.size(),
            MODELS.get(modelId) == null ? 0 : MODELS.get(modelId).size());
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
        if (!uploadTextures(modelId, data.getTexture())) {
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
                }
            }
            COMPLETED_BUILDS.add(new BuiltModel(modelId, built, data.getTexture(), images, data, generation));
        } catch (Throwable failure) {
            ysmu.LOG.warn("Failed to build geometry for model {}", modelId, failure);
            // A build that threw is a deterministic failure - the same bytes will throw again - so the model is
            // quarantined rather than retried. Upstream makes the same distinction and quarantines deterministic
            // failures; retrying here would re-parse the same input on every frame that draws the model. The payload
            // is passed along because this task may already have been superseded.
            markGeometryFailed(modelId, data);
        }
    }

    /**
     * Installs at most {@link #MAX_PUBLISH_PER_TICK} finished models.
     * <p>
     * Called from the client tick, so the work that has to happen on this thread - putting the geometry where the
     * renderer looks for it, and uploading textures - is spread over frames instead of being done in one.
     */
    public static void tick() {
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
        if (!uploadTextures(built.modelId, built.textures, built.textureImages)) {
            ysmu.LOG.warn(
                "YSM client cannot draw model {}: one of its textures could not be uploaded; it is quarantined",
                built.modelId);
            rollbackPublishedGeometry(built.modelId);
            refuseGeometry(built.modelId);
            return;
        }
        markGeometryReady(built.modelId);
        ysmu.LOG.info(
            "YSM client built model {} on demand: pending={}",
            built.modelId,
            PENDING_GEOMETRY.size());
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

    /** The body of both refusal paths; callers hold {@link #GEOMETRY_STATE_LOCK}. */
    private static void refuseGeometryLocked(ResourceLocation modelId) {
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
        // Anything already installed under this id belongs to the payload being replaced, so it goes first: readers
        // that ask CustomPlayerEntity.getAnimation() without installing first (the id-only users in AnimationManager)
        // would otherwise act on the previous payload's file until the new one happens to be installed.
        GeckoLibCache.getInstance()
            .getAnimations()
            .remove(mainId);
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
        @Nullable
        private final ExtraInfo extraInfo;

        private BuiltGeo(ResourceLocation id, GeoModel model, RawGeometryTree tree) {
            this.id = id;
            this.model = model;
            this.heightScale = tree.properties.getHeightScale();
            this.widthScale = tree.properties.getWidthScale();
            this.renderLayersFirst = Boolean.TRUE.equals(tree.properties.getRenderLayersFirst());
            this.extraInfo = tree.properties.getExtraInfo();
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
        /** The exact payload these bytes came from; see the identity check in {@link #publishBuiltModel}. */
        private final ModelData data;
        /** The connection this build was queued on; see {@link #publishBuiltModel}. */
        private final long generation;

        private BuiltModel(ResourceLocation modelId, List<BuiltGeo> geometry, Map<String, byte[]> textures,
            Map<String, BufferedImage> textureImages, ModelData data, long generation) {
            this.modelId = modelId;
            this.geometry = geometry;
            this.textures = textures;
            this.textureImages = textureImages;
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
        boolean complete = true;
        for (Map.Entry<String, byte[]> entry : mapData.entrySet()) {
            ResourceLocation textureId = ModelIdUtil.getSubModelId(id, entry.getKey());
            try {
                registerTexture(textureId, entry.getValue(), decoded.get(entry.getKey()));
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
     * Texture ids this client uploaded itself; see {@link #registerTexture}.
     * <p>
     * Internal to the upload bookkeeping and nothing else. It is deliberately NOT a gate on what the renderer may
     * bind: the game's texture manager legitimately holds textures this mod never uploaded (the player's own skin for
     * the built-in {@code steve}/{@code alex} models, another mod's textures), and upstream lets those through - the
     * renderer resolves a model texture through the model's own list and carries anything else as an explicit render
     * event override. In the same way, a model texture is checked against the model's own list in
     * {@code CustomPlayerModel.textureFor}, not against this set.
     * <p>
     * What this set is for is the delete decision below: an id the game mapped to its shared missing texture is not
     * ours to delete, and deleting it would blank every missing texture in the game.
     * Kept for the life of the process on purpose: the GL textures survive a connection change, so an id that was
     * ours stays ours.
     */
    private static final Set<ResourceLocation> UPLOADED_TEXTURES = ConcurrentHashMap.newKeySet();

    private static void registerTexture(ResourceLocation id, byte[] data) {
        registerTexture(id, data, null);
    }

    /**
     * Registers one texture, uploading the image the loader thread already decoded when there is one.
     *
     * @param decoded the image prepared off-thread, or {@code null} to decode here; see {@link #buildModel}.
     */
    private static void registerTexture(ResourceLocation id, byte[] data, @Nullable BufferedImage decoded) {
        TextureManager textureManager = Minecraft.getMinecraft()
            .getTextureManager();
        // R-01: loadTexture only replaces the map entry, so registering an id twice would leak the GL texture the
        // previous OuterFileTexture uploaded and nothing would ever free it - but only an id this client really
        // uploaded may be deleted. A texture the manager could not decode is mapped to its shared
        // TextureUtil.missingTexture singleton, whose GL name every missing texture in the game draws with; deleting
        // that would blank them all. loadTexture's return value is what tells the two apart.
        if (UPLOADED_TEXTURES.contains(id)) {
            textureManager.deleteTexture(id);
        }
        OuterFileTexture texture = decoded != null ? new OuterFileTexture(decoded) : new OuterFileTexture(data);
        if (textureManager.loadTexture(id, texture)) {
            UPLOADED_TEXTURES.add(id);
        } else {
            // The manager substituted the shared missing texture, so this id is not ours to delete next time.
            UPLOADED_TEXTURES.remove(id);
        }
    }

    private static void registerAnimations(ResourceLocation id, Map<String, byte[]> mapData) {
        Map<ResourceLocation, AnimationFile> animations = GeckoLibCache.getInstance()
            .getAnimations();
        AnimationFile main = new AnimationFile();
        Map<String, byte[]> controllerFiles = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : mapData.entrySet()) {
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
                    StringUtils.defaultString(e.getMessage()));
                continue;
            }
            if (jsonObject == null) {
                continue;
            }
            if (jsonObject.has("animation_controllers")) {
                controllerFiles.put(entry.getKey(), bytes);
                continue;
            }
            mergeAnimationFile(main, animationFileFromJson(jsonObject));
        }
        DEFAULT_ANIMATION_FILE.animations.forEach((name, action) -> {
            if (!main.animations.containsKey(name)) {
                main.putAnimation(name, action);
            }
        });
        main.animations.forEach((name, animation) -> {
            try {
                ConditionManager.addTest(id, name);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to register animation condition {} for model {}", name, id, e);
            }
        });
        animations.put(id, main);
        try {
            OpenYsmAnimationControllerRegistry.register(id, controllerFiles.values());
        } catch (Throwable failure) {
            // The animation set above is already installed, and the registry already handles a broken controller file
            // per file, so this only catches something escaping that - an Error. Letting it through would make the
            // caller report the whole model as failed when its animations are in fact usable.
            ysmu.LOG.warn("Failed to register OpenYSM controllers for model {}", id, failure);
        }
        ysmu.LOG.info("YSM client registered animations for {}: count={}", id, main.animations.size());
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
                animation = JsonAnimationUtils
                    .deserializeJsonToAnimation(JsonAnimationUtils.getAnimation(jsonObject, animationName), parser);
                animationFile.putAnimation(animationName, animation);
            } catch (Exception e) {
                ysmu.LOG.warn(
                    "Failed to register animation {}: {}: {}",
                    animationName,
                    e.getClass()
                        .getSimpleName(),
                    StringUtils.defaultString(e.getMessage()));
            }
        }
        return animationFile;
    }

    private static AnimationFile mergeAnimationFile(AnimationFile main, AnimationFile other) {
        other.animations.forEach(main::putAnimation);
        return main;
    }

    public static void loadDefaultModel() {
        try {
            ModelData data = FolderFormat.getModelData(ServerModelManager.CUSTOM, "default");
            data.getAnimation()
                .forEach((name, bytes) -> {
                    AnimationFile animationFile = getAnimationFile(new String(bytes, StandardCharsets.UTF_8));
                    mergeAnimationFile(DEFAULT_ANIMATION_FILE, animationFile);
                });
            // Eager: this is the substitute every not-yet-built model is drawn with, so it has to exist before any
            // other model asks for it.
            ClientModelManager.registerAll(data, true);
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to load default model", e);
            e.printStackTrace();
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
        long generation = SYNC_GENERATION + 1L;
        SYNC_GENERATION = generation;
        pendingModelSync = null;
        ysmu.LOG.info(
            "YSM client starting model sync: currentModels={}, rememberedCachedModels={}",
            MODELS.size(),
            CACHE_MD5.size());
        PASSWORD = null;
        PASSWORD_UUID = null;
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
        // Roaming namespaces are keyed by model content hash, so they belong to the dropped models too.
        com.fox.ysmu.client.roaming.ClientRoamingKeys.clear();
        com.fox.ysmu.client.roaming.ClientRoamingStore.clear();
        com.fox.ysmu.client.gui.ModelPreviewAnimationState.resetAll();
        ConditionManager.clear();
        OpenYsmAnimationControllerRegistry.clear();
        MolangPhysicsRuntime.clear();
        MolangInstructionExecutor.clearWarnings();
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
