package com.fox.ysmu.client.renderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.EntityQueryBindings;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ActiveAnimation;
import com.fox.ysmu.client.animation.controller.ProjectileTimelineRuntime;
import com.fox.ysmu.client.animation.controller.VehicleControllerRuntime;
import com.fox.ysmu.client.animation.controller.VehicleControllerRuntime.VehicleState;
import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.client.render.ModelFailureNotifier;
import com.fox.ysmu.client.roaming.ClientRoamingKeys;
import com.fox.ysmu.client.roaming.ClientRoamingStore;
import com.fox.ysmu.data.VehicleModelStates;
import com.fox.ysmu.mixin.client.RenderLivingInvoker;
import com.fox.ysmu.mixin.client.RendererLivingEntityInvoker;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.molang.RemoteAnimationVariables;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Draws a pack's vehicle sub-entity (the {@code files.vehicles} entry, e.g. {@code #minecraft:boat}) in place of any
 * entity a pack's {@code match} list names. Called from the one dispatcher hook, {@code MixinRenderManager}, which
 * wraps the {@code render.doRender(...)} call inside {@code RenderManager.func_147939_a} - upstream's shape, whose
 * vehicle replacement is likewise a wrapper on the renderer call in its entity render dispatcher
 * ({@code mixin/client/EntityRenderDispatcherMixin.java:20-52}). Any entity type therefore qualifies, vanilla or
 * modded, as long as a loaded model declares it.
 *
 * <p>The model is the one the vehicle's <b>owner</b> had when they mounted it, which is what upstream stores in the
 * vehicle's capability ({@code mixin/ServerPlayerMixin.java:14-29} → {@code CapabilityEvent.onVehicleSetModel:244-258})
 * and never clears, so a vehicle keeps its model after the owner dismounts. {@link VehicleModelStates} is the port's
 * copy of that fact; an entity with no entry draws vanilla, exactly as upstream's capability-less entity does
 * ({@code client/renderer/replace/EntityRendererReplace.java:16-23} returns "not handled" in that case).
 *
 * <p>Failure is always "let the host draw": every early return here means the caller does <b>not</b> cancel the
 * vanilla renderer. A missing model, a missing geometry, a missing texture or a missing renderer therefore leaves the
 * entity's own renderer on screen. That is upstream's fallback rule (its product decisions
 * {@code docs/product-decisions/decisions/model-fallback.md:27} and {@code failure-and-recovery.md:22}: a render
 * failure restores the host's own draw) and it is the same contract {@code ArrowProjectileRenderer} keeps - this
 * renderer never substitutes the built-in default humanoid.
 *
 * <p>Because the dispatcher's redirect replaces the entity's whole renderer call, the parts of the vanilla living
 * pass that live <em>inside</em> that call have to be replayed here, which is what upstream's own replaced-entity
 * renderer does: the name tag and its {@code RenderLivingEvent.Specials.Pre} come back through
 * {@link RendererLivingEntityInvoker#ysmu$passSpecialRender}, {@code RenderLivingEvent.Post} is posted after it
 * (upstream {@code GeoReplacedEntityRenderer.java:129-130}), and the leash comes back through
 * {@link RenderLivingInvoker#ysmu$renderLeash} (the port's own replaced-player renderer does the same at
 * {@code GeoReplacedEntityRenderer.java:145-150}). Shadow and fire are <b>not</b> replayed: they are drawn by
 * {@code Render#doRenderShadowAndFire}, which the dispatcher calls after the redirected call
 * ({@code RenderManager.java:309}).
 *
 * <p>Animation is not driven yet: the geometry is drawn at its authored rest pose. The vehicle animation sets are
 * forwarded and registered (see {@code RawYsmModelAdapter} and {@code ClientModelManager#registerAnimations}), so
 * adding a vehicle controller runtime is a renderer-only change later.
 */
public final class VehicleRenderer {

    /**
     * Upstream's vehicle render scale: {@code CustomVehicleEntity.getWidthScale()/getHeightScale()} return 0.7F
     * ({@code client/entity/CustomVehicleEntity.java:116-123}), its renderer feeds them into
     * {@code poseStack.scale(width, height, width)} ({@code geckolib3/geo/IGeoRenderer.java:15}), and its projectile
     * entity declares the same pair ({@code CustomProjectileEntity.java:104-110}) - which is where this port's arrow
     * renderer takes the 0.7 it already applies ({@code ArrowProjectileRenderer.java:212}). Same value, same reason.
     */
    private static final float RENDER_SCALE = 0.7F;

    /** The rail offset {@code RenderMinecart} samples its tangent at, as the float literal it is in vanilla. */
    private static final double MINECART_TANGENT_OFFSET = (double) 0.3F;

    /** Dedup for the per-frame warnings: a model missing its geometry would otherwise print once per vehicle per frame. */
    private static final Set<String> LOGGED_RENDER_WARNS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private VehicleRenderer() {}

    /** A warning on the per-frame path, printed once per distinct message. */
    private static void warnOnce(String tag, String format, Object... args) {
        String message = String.format(Locale.ROOT, format, args);
        if (LOGGED_RENDER_WARNS.add(tag + "|" + message)) {
            ysmu.LOG.warn("[YSMU-VEHICLE] {}", message);
        }
    }

    /** Clears the once-per-message dedup table, so it reports again after a reload. */
    public static void clearWarnings() {
        LOGGED_RENDER_WARNS.clear();
    }

    /**
     * Tells the player that this vehicle lost its YSM replacement, which is upstream's {@code STOPPED} outcome: the
     * outcome for the entities that have no fallback target at all, since a vehicle's
     * {@code fallbackRenderTargetId()} is {@code null} ({@code CustomVehicleEntity:37-40}) and the replacement is
     * therefore revoked rather than replaced by the built-in default
     * ({@code ClientModelRenderTargetManager:246-250} picks STOPPED exactly then).
     * <p>
     * Only once the owner's model is really ready, or was refused: while its payload is still being built the vehicle
     * is drawn vanilla for a frame or two, and that is preparation, not a failure - upstream's outcome comes from a
     * terminal {@code AcquireResult.Failed}, not from a pending one.
     */
    private static void reportReplacementRevoked(ResourceLocation modelId) {
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        if (ClientModelManager.isModelPublished(mainId) || ClientModelManager.isGeometryFailed(mainId)) {
            ModelFailureNotifier.report(modelId, ModelFailureNotifier.Outcome.STOPPED);
        }
    }

    /**
     * The model the vehicle's owner had when they mounted, or {@code null} when this client has no state for the
     * entity. The state is the server's, so this is not "who is riding now": a vehicle whose owner dismounted keeps
     * the model, and a passenger who was not the owner cannot change it.
     */
    @Nullable
    public static ResourceLocation ownerModelId(Entity vehicle) {
        VehicleModelStates.State state = VehicleModelStates.get(vehicle);
        return state == null ? null : state.getModelId();
    }

    /**
     * The entity ids a pack's {@code match} entry may name for this entity.
     * <p>
     * Upstream compares the entity <b>type's</b> {@code ResourceLocation} against the pack's match list
     * ({@code client/entity/CustomVehicleEntity.java:29-35}), which is what makes its vehicle replacement work for any
     * entity type. 1.7.10 has no such registry - {@code EntityList.getEntityString} is an exact class lookup
     * ({@code EntityList.java:258-261}) returning the string the entity was registered with, and that string is
     * neither the same text nor always the same shape:
     * <ul>
     * <li>vanilla entities use bare names ({@code "Boat"}, {@code "Pig"}, {@code "EntityHorse"}, and seven different
     * {@code "Minecart*"} names for the minecart family, {@code EntityList.java:298-305,325,335});</li>
     * <li>FML registers a mod entity as {@code "<modid>.<name>"} ({@code EntityRegistry.java:162-167}), which is
     * upstream's {@code "modid:name"} with the separator spelled differently.</li>
     * </ul>
     * So each entity yields the id(s) a pack could reasonably have written, and a match is an exact comparison against
     * one of them (after stripping upstream's {@code #} entity-tag prefix, which 1.7.10 cannot resolve - there are no
     * data-driven entity tags here). The result is never null: an entity type nothing names simply matches nothing.
     */
    private static List<String> matchCandidates(Entity entity) {
        List<String> candidates = new ArrayList<>(3);
        String registryName = EntityList.getEntityString(entity);
        if (registryName != null && !registryName.isEmpty()) {
            String lowered = registryName.toLowerCase(Locale.ROOT);
            if (lowered.indexOf(':') >= 0) {
                candidates.add(lowered);
            } else {
                int dot = lowered.indexOf('.');
                if (dot > 0 && dot < lowered.length() - 1) {
                    // FML's "<modid>.<name>" in upstream's "modid:name" spelling.
                    candidates.add(lowered.substring(0, dot) + ":" + lowered.substring(dot + 1));
                }
                candidates.add("minecraft:" + lowered);
            }
        }
        // The two vanilla families 1.7.10 registers under a name upstream does not use. Without these the built-in
        // default's own `minecraft:minecart` entry could never match anything, because every concrete minecart class
        // is registered as "Minecart<something>" and EntityMinecart itself is abstract (EntityMinecart.java:31).
        if (entity instanceof EntityMinecart) {
            candidates.add("minecraft:minecart");
        } else if (entity instanceof EntityHorse) {
            candidates.add("minecraft:horse");
        }
        return candidates;
    }

    /** Whether a pack's declared match id names one of the candidate entity ids. */
    private static boolean matchNames(String declared, List<String> candidates) {
        String normalized = declared.startsWith("#") ? declared.substring(1) : declared;
        normalized = normalized.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tries to draw a custom vehicle model for {@code entity}.
     *
     * @param vanillaRenderer the entity's own renderer, which the dispatcher was about to call. It is not called here
     *                        - the caller does that when this returns {@code false} - but its living half is what the
     *                        special pass (name tag, leash) is replayed through.
     * @return {@code true} when a custom model was drawn, so the caller must skip the vanilla renderer;
     *         {@code false} when there is nothing to draw, so vanilla should proceed
     */
    public static boolean render(Entity entity, double x, double y, double z, float yaw, float partialTicks,
        Render vanillaRenderer) {
        if (entity == null) {
            return false;
        }
        // Upstream's own bypass: EntityRenderDispatcherMixin.java:46 wraps the replacement in
        // `!ClientConfig.DISABLE_VEHICLE_MODEL.get()`, so with the switch on the entity is left to its vanilla
        // renderer. This port's replacement is entered from MixinRenderManager, so that entry is where the same
        // decision belongs; a predicate there would also have to cover the arrow path, which has its own switch.
        // Upstream's other read, the animation ticker (AnimationParallelTicker.java:64-66), has no counterpart here -
        // this renderer drives no animation yet (see the class javadoc).
        if (Config.DISABLE_VEHICLE_MODEL) {
            return false;
        }
        // Upstream's capability is attached to everything that is neither a player nor a projectile
        // (event/CapabilityEvent.java:78-81), so players are not vehicles. The server never writes state for one
        // either (S2CVehicleModelState.onOwnerMounted); this is the render-side half of the same rule.
        if (entity instanceof EntityPlayer) {
            return false;
        }
        // Arrows keep their own mechanism - a datawatcher model id plus a RenderArrow mixin
        // (MixinEntityArrow, MixinRenderArrow). Replacing the renderer call here would bypass that mixin, so an arrow
        // is never claimed as a vehicle even if a packet put state on one.
        if (entity instanceof EntityArrow) {
            return false;
        }
        ResourceLocation modelId = ownerModelId(entity);
        if (modelId == null) {
            return false;
        }
        List<String> candidates = matchCandidates(entity);
        List<String> declared = ClientModelManager.VEHICLE_MODEL_IDS.get(modelId);
        if (declared == null) {
            warnOnce("no-vehicle-models", "no vehicle sub-entity is registered for {}", modelId);
            return false;
        }
        String match = null;
        for (String type : declared) {
            if (matchNames(type, candidates)) {
                match = type;
                break;
            }
        }
        if (match == null) {
            warnOnce("no-vehicle-type", "no vehicle entry among {} for {} matches {}", declared, modelId, candidates);
            return false;
        }

        ResourceLocation geoId = ModelIdUtil.getSubModelId(modelId, "vehicle_" + match);
        // A vehicle can be drawn before its owner's model has ever been: it may belong to another player, or the owner
        // may have left. This client parks geometry and animations until something asks for them, so ask here. A build
        // takes a frame or two, during which this returns false and the vehicle is drawn vanilla - which is the
        // correct fallback rather than an invisible vehicle.
        ClientModelManager.ensureGeometry(modelId);
        ClientModelManager.ensureAnimations(ModelIdUtil.getMainId(modelId));
        GeoModel model = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(geoId);
        if (model == null) {
            warnOnce("geo-not-found", "vehicle geometry {} is not registered for {}", geoId, modelId);
            reportReplacementRevoked(modelId);
            return false;
        }

        ResourceLocation textureId = null;
        List<ResourceLocation> textures = ClientModelManager.VEHICLE_TEXTURE_IDS.get(modelId);
        if (textures != null) {
            String prefix = "vehicle_" + match + "_";
            for (ResourceLocation candidate : textures) {
                if (candidate.getResourcePath()
                    .contains(prefix)) {
                    textureId = candidate;
                    break;
                }
            }
        }
        if (textureId == null) {
            warnOnce("no-texture", "no vehicle texture is registered for {} ({})", modelId, match);
            return false;
        }

        IGeoRenderer<?> geoRenderer = GeoReplacedEntityRenderer.getRenderer(CustomPlayerEntity.class);
        if (geoRenderer == null) {
            return false;
        }

        // A living entity is rotated by its interpolated <b>body</b> yaw, not by the rotationYaw the dispatcher hands
        // over: that is the value this engine's own GeckoLib entity renderer uses
        // (GeoReplacedEntityRenderer.resolveBodyYaw:185 lerps prevRenderYawOffset/renderYawOffset) and the one
        // vanilla's RendererLivingEntity#applyRotations would have used. A boat or a minecart is not living and keeps
        // the yaw it was given. The leash pass below still gets the dispatcher's own value, as vanilla passes it.
        float dispatcherYaw = yaw;
        if (entity instanceof EntityLivingBase living) {
            yaw = living.prevRenderYawOffset + (living.renderYawOffset - living.prevRenderYawOffset) * partialTicks;
        }

        boolean minecart = entity instanceof EntityMinecart;
        float pitch = 0.0F;
        if (minecart) {
            // The rail-derived position and orientation, reproduced from RenderMinecart.java:48-85. It cannot be
            // skipped: the entity's own rotationYaw lags the rail (EntityMinecart.java:339-341 smooths it by
            // turnProgress and :388-410 snaps it), so the vanilla renderer recomputes both from the rail itself, and a
            // model drawn with the entity's yaw would sit at the wrong angle on every curve and slope.
            EntityMinecart cart = (EntityMinecart) entity;
            double interpX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
            double interpY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
            double interpZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
            pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
            Vec3 rail = cart.func_70489_a(interpX, interpY, interpZ);
            if (rail != null) {
                Vec3 front = cart.func_70495_a(interpX, interpY, interpZ, MINECART_TANGENT_OFFSET);
                Vec3 back = cart.func_70495_a(interpX, interpY, interpZ, -MINECART_TANGENT_OFFSET);
                if (front == null) {
                    front = rail;
                }
                if (back == null) {
                    back = rail;
                }
                x += rail.xCoord - interpX;
                y += (front.yCoord + back.yCoord) / 2.0D - interpY;
                z += rail.zCoord - interpZ;
                Vec3 direction = back.addVector(-front.xCoord, -front.yCoord, -front.zCoord);
                if (direction.lengthVector() != 0.0D) {
                    direction = direction.normalize();
                    yaw = (float) (Math.atan2(direction.zCoord, direction.xCoord) * 180.0D / Math.PI);
                    pitch = (float) (Math.atan(direction.yCoord) * 73.0D);
                }
            }
        }

        // Pose the model for this frame before the draw. A vehicle, like a projectile, is not an IAnimatable, so
        // nothing else ticks its clips. The render transform is handed over because the frame's timeline
        // (ysm.particle) offsets particles from the model's own origin and rotation.
        tickAnimation(entity, model, geoId, modelId, partialTicks, x, y, z, yaw, pitch);

        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);
            // The Y rotation is vanilla's and this engine's own GeckoLib convention at once: RenderBoat.java:37 and
            // RenderMinecart.java:84 rotate by 180 - yaw, and GeoReplacedEntityRenderer.applyRotations:318 rotates a
            // player model by exactly the same 180 - rotationYaw. The two orientations agree, so a Bedrock-authored
            // vehicle needs no correction here.
            GL11.glRotatef(180.0F - yaw, 0.0F, 1.0F, 0.0F);
            if (minecart) {
                GL11.glRotatef(-pitch, 0.0F, 0.0F, 1.0F);
            }
            GL11.glScalef(RENDER_SCALE, RENDER_SCALE, RENDER_SCALE);

            // Vanilla's own transform ends with glScalef(-1,-1,1) before it draws its model (RenderBoat.java:55,
            // RenderMinecart.java:116). That mirror is a ModelBoat/ModelMinecart authoring artifact - it is a 180
            // degree rotation about Z - and a Bedrock model is authored in the standard orientation instead, which is
            // why this engine applies no such mirror to a player (GeoReplacedEntityRenderer.doRender:84-101). So it is
            // deliberately not reproduced.
            //
            // Also deliberately not reproduced: the damage wobble (RenderBoat.java:38-49, RenderMinecart.java:86-97),
            // the minecart's deterministic position jitter (:42-47) and its cargo display block (:99-114). Upstream
            // reproduces none of them either - its EntityRendererReplace only fixes the yaw
            // (client/renderer/replace/EntityRendererReplace.java:26-35).
            net.geckominecraft.client.renderer.GlStateManager.disableCull();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            Minecraft.getMinecraft().renderEngine.bindTexture(textureId);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            net.geckominecraft.client.renderer.GlStateManager.enableBlend();

            // renderRecursively applies the bone pivots/rotations and walks the children, exactly as the projectile
            // renderer does it (ArrowProjectileRenderer.java:251-264). The entity pass has already enabled the
            // standard item lighting and set this entity's lightmap (EntityRenderer.java:1297-1300,
            // RenderManager.java:274-276), so the model is lit like the vanilla vehicle it replaces and no lighting
            // state is touched here.
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_QUADS);
            for (GeoBone bone : model.topLevelBones) {
                ((IGeoRenderer<Entity>) geoRenderer).renderRecursively(tessellator, entity, bone, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            tessellator.draw();
        } catch (Throwable failure) {
            // A per-frame path: even an Error has to be caught, or vanilla builds a CrashReport per frame. Dedup by
            // content so one broken model cannot flood the log.
            warnOnce(
                "render|" + failure.getClass()
                    .getName() + '|' + failure.getMessage(),
                "vehicle model render failed for {} ({}): {}",
                modelId,
                match,
                failure.toString());
        } finally {
            net.geckominecraft.client.renderer.GlStateManager.disableBlend();
            net.geckominecraft.client.renderer.GlStateManager.enableCull();
            GL11.glPopMatrix();
        }
        replayLivingSpecials(vanillaRenderer, entity, x, y, z, dispatcherYaw, partialTicks);
        return true;
    }

    /**
     * Replays the vanilla living pass that the replaced {@code render.doRender(...)} call would have run.
     *
     * <p>The dispatcher's redirect replaces the entity's <em>whole</em> renderer call, so everything 1.7.10 draws
     * inside it is skipped unless it is replayed here. Three things live there:
     * <ul>
     * <li>the name tag and its {@code RenderLivingEvent.Specials.Pre}, both inside
     * {@code RendererLivingEntity#passSpecialRender} ({@code RendererLivingEntity.java:466-471}, called from
     * {@code :296}); upstream replays the same pass through an accessor of its own replaced-entity renderer
     * ({@code GeoReplacedEntityRenderer.java:129});</li>
     * <li>{@code RenderLivingEvent.Post}, posted by vanilla immediately after it ({@code RendererLivingEntity.java:297})
     * and by upstream at {@code GeoReplacedEntityRenderer.java:130};</li>
     * <li>the leash, drawn by {@code RenderLiving#doRender} <b>after</b> the model pass
     * ({@code RenderLiving.java:36-37}); the port's own replaced-player renderer re-draws it for the same reason
     * ({@code GeoReplacedEntityRenderer.java:145-150}).</li>
     * </ul>
     *
     * <p>Shadow and fire are deliberately <b>not</b> replayed: they are {@code Render#doRenderShadowAndFire}
     * ({@code Render.java:311-320}), which the dispatcher calls itself after the redirected call
     * ({@code RenderManager.java:309}), together with the F3+B bounding box at {@code :316-326}.
     *
     * <p>A non-living entity has no specials pass in vanilla either - {@code RenderBoat} and {@code RenderMinecart}
     * draw no name tag - so nothing is restored for a boat or a minecart.
     */
    private static void replayLivingSpecials(Render vanillaRenderer, Entity entity, double x, double y, double z,
        float yaw, float partialTicks) {
        if (!(entity instanceof EntityLivingBase living)
            || !(vanillaRenderer instanceof RendererLivingEntity livingRenderer)) {
            return;
        }
        try {
            ((RendererLivingEntityInvoker) livingRenderer).ysmu$passSpecialRender(living, x, y, z);
        } catch (Throwable failure) {
            // The model is already on screen: a broken name tag must not take the frame (or the client) down, and it
            // must not make the model invisible either - that is what "restore the host's draw" means here.
            warnOnce(
                "specials|" + failure.getClass()
                    .getName() + '|' + failure.getMessage(),
                "vehicle name tag pass failed for {}: {}",
                entity,
                failure.toString());
        } finally {
            MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Post(living, livingRenderer, x, y, z));
        }
        if (entity instanceof EntityLiving leashEntity && vanillaRenderer instanceof RenderLiving leashRenderer) {
            try {
                // The vanilla method, on the entity's own renderer - not a copy. Its own first statement is the
                // "is anything leashed to me" test (RenderLiving.java:47-49), so an unleashed entity costs one field
                // read. Its yaw argument is unused by the vanilla body (RenderLiving.java:45-...), so the dispatcher's
                // value is passed exactly as vanilla does.
                ((RenderLivingInvoker) leashRenderer).ysmu$renderLeash(leashEntity, x, y, z, yaw, partialTicks);
            } catch (Throwable failure) {
                warnOnce(
                    "leash|" + failure.getClass()
                        .getName() + '|' + failure.getMessage(),
                    "vehicle leash pass failed for {}: {}",
                    entity,
                    failure.toString());
            }
        }
    }

    /**
     * Poses the model for this frame: selects the vehicle's active clips and samples their keyframes onto the bones.
     *
     * <p>Upstream ticks a real animatable every frame ({@code client/entity/CustomVehicleEntity.java:20-125}) and its
     * renderer drives a real controller set ({@code VehicleControllerCollection.java:19-44}). A 1.7.10 vehicle is not
     * an {@code IAnimatable} and never enters an {@code AnimationProcessor} - the engine's replaced-entity renderer is
     * {@code RendererLivingEntity}-typed and cannot draw a boat - so its clips are selected and sampled by hand,
     * exactly as the projectile path does it ({@code ArrowProjectileRenderer.applyProjectileAnimations:348-381}): the
     * same shared sampler, the same bind-pose reset, a different selector
     * ({@link VehicleControllerRuntime} vs {@code ProjectileControllerRuntime}).
     */
    private static void tickAnimation(Entity entity, GeoModel model, ResourceLocation geoId,
        ResourceLocation modelId, float partialTicks, double x, double y, double z, float yaw, float pitch) {
        AnimationFile animationFile = GeckoLibCache.getInstance()
            .getAnimations()
            .get(geoId);
        if (animationFile == null || animationFile.animations == null) {
            // Nothing to sample, so the geometry stays in its bind pose.
            return;
        }
        double ageInTicks = entity.ticksExisted + partialTicks;

        // The owner's roaming variables (upstream's molangVarsServerBound) are published for the duration of this
        // draw; see publishOwnerRoamingVariables for where they come from.
        PublishedVariables published = publishOwnerRoamingVariables(entity, modelId);
        // Publish the frame's subject and its entity-derived query values exactly as the world path does
        // (AnimationRegister#setParserValue:478-496): query.position_delta(...), ctrl.hold and the particle/bone
        // functions read the subject from here, and query.* / ysm.* are bound by the one shared table. Without this
        // a vehicle's animation read the local player's state - the values are live suppliers, not a snapshot.
        MolangFrameContext.begin(entity, ModelIdUtil.getMainId(modelId));
        try {
            EntityQueryBindings.apply(GeckoLibCache.getInstance().parser, entity, partialTicks);
            // Reset to the bind pose before sampling, so a bone the previous frame's clip moved and this frame's does
            // not touch cannot keep the stale pose.
            ArrowProjectileRenderer.saveInitialSnapshots(model.topLevelBones);
            ArrowProjectileRenderer.resetBonesToSnapshot(model.topLevelBones);

            List<ActiveAnimation> activeEntries = VehicleControllerRuntime.getActiveAnimationEntries(
                entity.getEntityId(),
                geoId,
                ageInTicks,
                VehicleState.of(entity));
            if (entity.isDead) {
                // The entity is leaving: drop the coded-controller state it will never use again.
                VehicleControllerRuntime.cleanupEntity(entity.getEntityId());
            }
            if (activeEntries.isEmpty()) {
                // No clip is active, so the bind pose is what should be drawn.
                return;
            }
            List<String> activeAnimations = new ArrayList<>(activeEntries.size());
            Map<String, Double> clockOrigins = new HashMap<>();
            for (ActiveAnimation entry : activeEntries) {
                activeAnimations.add(entry.name);
                if (entry.startTick != 0.0d) {
                    clockOrigins.put(entry.name, entry.startTick);
                }
            }
            // The sampler's own debug flag is left off: its diagnostics are per keyframe and this path has no
            // rate-limited dump of its own, while the selection is already logged by VehicleControllerRuntime behind
            // DebugController.
            ArrowProjectileRenderer
                .applyActiveAnimations(model, animationFile, activeAnimations, clockOrigins, ageInTicks, false);
            // The timeline has to run after the bones are written: an instruction may read this frame's pose through
            // ysm.bone_pivot_abs(...).
            dispatchTimeline(entity, geoId, animationFile, activeAnimations, ageInTicks, model, x, y, z, yaw, pitch);
        } finally {
            MolangFrameContext.end();
            restoreVariables(published);
        }
    }

    /**
     * Dispatches the vehicle's {@code timeline} instructions - the {@code ysm.particle(...)} a pack writes for an
     * engine wake, a wheel spray, a mount effect.
     *
     * <p>Same four paired scopes as the projectile path ({@code ArrowProjectileRenderer#dispatchProjectileTimelines}),
     * because the runtime underneath is the same one and it is generic: {@code ProjectileTimelineRuntime.dispatch}
     * takes an entity id, an animation id, the file, the active names and the age, and nothing projectile-shaped. The
     * per-frame dispatch budget is already reset once per frame for both kinds
     * ({@code ClientEventHandler:142} → {@code ProjectileTimelineRuntime.beginRenderFrame}), so nothing is reset
     * here.</p>
     *
     * <p>Upstream has no separate timeline dispatcher at all: its vehicle is a real animatable, so the instructions
     * run inside the engine's per-frame animation processing, which is where it parses them
     * ({@code geckolib3/util/json/JsonAnimationUtils.java:48}, {@code format/parser/pojo/animation/Animation.java:124-125}).
     * A 1.7.10 vehicle never enters that processing, so the port's standalone dispatcher is its counterpart - the same
     * reason the projectile path needed one.</p>
     */
    private static void dispatchTimeline(Entity entity, ResourceLocation geoId, AnimationFile animationFile,
        List<String> activeAnimations, double ageInTicks, GeoModel model, double x, double y, double z, float yaw,
        float pitch) {
        Entity previousEntity = ParticleEffectUtil.getCurrentEntity();
        ParticleEffectUtil.setCurrentEntity(entity);
        // x/y/z are the model's origin (the minecart's rail-corrected position included) and yaw/pitch its
        // orientation, so a model-space offset written as bone_pivot_abs(...)/16*0.7 lands where the model is.
        ParticleEffectUtil.beginProjectileTransform(x, y, z, yaw, pitch);
        Map<String, IBone> previousBones = MolangPhysicsRuntime.beginProjectileBones(model.topLevelBones);
        try {
            ProjectileTimelineRuntime.dispatch(
                entity.getEntityId(),
                geoId,
                animationFile,
                activeAnimations,
                ageInTicks);
        } catch (Throwable failure) {
            warnOnce(
                "timeline|" + failure.getClass()
                    .getName() + '|' + failure.getMessage(),
                "vehicle timeline dispatch failed for {}: {}",
                entity,
                failure.toString());
        } finally {
            MolangPhysicsRuntime.endProjectileBones(previousBones);
            ParticleEffectUtil.endProjectileTransform();
            ParticleEffectUtil.setCurrentEntity(previousEntity);
        }
    }

    /** What {@link #publishOwnerRoamingVariables} wrote, so the draw can put the shared table back as it was. */
    private static final class PublishedVariables {

        /** Names that had no entry before this draw, so the entry is removed again. */
        final Set<String> created = new HashSet<>();
        /** Names that did have an entry, with the value it held, so it is restored. */
        final Map<String, Double> previousValues = new HashMap<>();
    }

    /**
     * Publishes the vehicle owner's roaming variables as {@code v.roaming.<name>} for the duration of one draw.
     *
     * <p>Upstream carries them in the vehicle state itself ({@code molangVarsServerBound},
     * {@code VehicleModelInfoCapability.java:10,12-16}) so a vehicle animation can read the owner's
     * {@code v.roaming.*}. This port does <b>not</b> need a second payload for that: the roaming channel already
     * delivers every player's values to every tracking client ({@code S2CRoamingState}, clientbound id 20, applied to
     * {@code ClientRoamingStore} at {@code S2CRoamingState.java:196}), and the vehicle state carries both halves of
     * the address - the owner's UUID ({@code VehicleModelStates.State#getOwnerId}) and the owner's model id. The
     * namespace key comes from {@link ClientRoamingKeys#keyFor}, the same derivation the owner's own client used when
     * it reported the values, so the two agree without a wire field.</p>
     *
     * <p>The values go into the shared parser table because that is the table the hand-written sampler evaluates
     * keyframe expressions against (the projectile path publishes its {@code ysm.*} state there for the same reason,
     * {@code ArrowProjectileRenderer.setMolangVariable}). They are put back afterwards: this is model-specific data,
     * and a name the owner's model defines but another model does not must not be visible to that other model as a
     * leftover.</p>
     */
    private static PublishedVariables publishOwnerRoamingVariables(Entity vehicle, ResourceLocation modelId) {
        PublishedVariables published = new PublishedVariables();
        VehicleModelStates.State state = VehicleModelStates.get(vehicle);
        UUID ownerId = state == null ? null : state.getOwnerId();
        if (ownerId == null) {
            // The owner is unknown (a state written before the owner was resolvable, or one this build never
            // received): the animation reads 0, which is upstream's "no struct" case.
            return published;
        }
        Map<String, Double> values = ClientRoamingStore.valuesFor(ownerId, ClientRoamingKeys.keyFor(modelId));
        if (values == null || values.isEmpty()) {
            return published;
        }
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            // RemoteAnimationVariables.normalize is the port's one rule for a roaming name: a bare name is promoted to
            // v.roaming.<name> and canonicalised, so the pack's own spelling cannot miss the entry.
            String name = RemoteAnimationVariables.normalize(entry.getKey());
            LazyVariable existing = MolangParser.VARIABLES.get(name);
            if (existing == null) {
                published.created.add(name);
            } else {
                published.previousValues.put(name, existing.get());
            }
            ArrowProjectileRenderer.setMolangVariable(name, entry.getValue());
        }
        return published;
    }

    /** Puts the shared parser table back the way {@link #publishOwnerRoamingVariables} found it. */
    private static void restoreVariables(PublishedVariables published) {
        for (String name : published.created) {
            MolangParser.VARIABLES.remove(name);
        }
        for (Map.Entry<String, Double> entry : published.previousValues.entrySet()) {
            ArrowProjectileRenderer.setMolangVariable(entry.getKey(), entry.getValue());
        }
    }
}
