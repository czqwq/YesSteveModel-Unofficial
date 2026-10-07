package com.fox.ysmu.client.renderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.data.VehicleModelStates;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Draws a pack's vehicle sub-entity (the {@code files.vehicles} entry, e.g. {@code #minecraft:boat}) in place of a
 * vanilla entity. Called from {@code MixinRenderBoat}, {@code MixinRenderMinecart} and the client's
 * {@code RenderLivingEvent.Pre} handler for pigs and horses.
 *
 * <p>The model is the one the vehicle's <b>owner</b> had when they mounted it, which is what upstream stores in the
 * vehicle's capability ({@code mixin/ServerPlayerMixin.java:14-29} → {@code CapabilityEvent.onVehicleSetModel:244-258})
 * and never clears, so a vehicle keeps its model after the owner dismounts. {@link VehicleModelStates} is the port's
 * copy of that fact; an entity with no entry draws vanilla, exactly as upstream's capability-less entity does
 * ({@code client/renderer/replace/EntityRendererReplace.java:16-23} returns "not handled" in that case).
 *
 * <p>Failure is always "let the host draw": every early return here means the caller does <b>not</b> cancel the
 * vanilla renderer. A missing model, a missing geometry, a missing texture or a missing renderer therefore leaves the
 * vanilla boat/minecart/pig/horse on screen. That is upstream's fallback rule (its product decisions
 * {@code docs/product-decisions/decisions/model-fallback.md:27} and {@code failure-and-recovery.md:22}: a render
 * failure restores the host's own draw) and it is the same contract {@code ArrowProjectileRenderer} keeps - this
 * renderer never substitutes the built-in default humanoid.
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
     * Whether this entity is one of the vehicle kinds this port can replace, and if so which entity ids a pack's
     * {@code match} entry may use for it.
     * <p>
     * Two spellings per kind, because the two sides of the port disagree about what an entity "is called": upstream
     * (1.20) matches a {@code ResourceLocation} such as {@code minecraft:minecart}
     * ({@code client/entity/CustomVehicleEntity.java:29-35}), while 1.7.10 identifies an entity type by the string it
     * was registered with - and registers the minecart family under seven different names, none of them
     * {@code Minecart} ({@code EntityList.java:298-305}: {@code MinecartRideable}, {@code MinecartChest}, ...), with
     * {@code EntityMinecart} itself abstract ({@code EntityMinecart.java:31}). A pack authored against either spelling
     * therefore matches. The comparison strips upstream's {@code #} entity-tag prefix, which 1.7.10 cannot resolve -
     * there are no data-driven entity tags here.
     */
    @Nullable
    private static List<String> matchCandidates(Entity entity) {
        List<String> candidates = new ArrayList<>(2);
        if (entity instanceof EntityBoat) {
            candidates.add("minecraft:boat");
        } else if (entity instanceof EntityMinecart) {
            candidates.add("minecraft:minecart");
            // The concrete 1.7.10 registration, for a pack that was authored by looking at EntityList.
            candidates.add("minecraft:minecartrideable");
        } else if (entity instanceof EntityPig) {
            candidates.add("minecraft:pig");
        } else if (entity instanceof EntityHorse) {
            candidates.add("minecraft:horse");
            // 1.7.10 registers the horse as "EntityHorse" (EntityList.java:335).
            candidates.add("minecraft:entityhorse");
        } else {
            return null;
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
     * @return {@code true} when a custom model was drawn, so the caller must skip the vanilla renderer;
     *         {@code false} when there is nothing to draw, so vanilla should proceed
     */
    public static boolean render(Entity entity, double x, double y, double z, float yaw, float partialTicks) {
        if (entity == null) {
            return false;
        }
        List<String> candidates = matchCandidates(entity);
        if (candidates == null) {
            return false;
        }
        ResourceLocation modelId = ownerModelId(entity);
        if (modelId == null) {
            return false;
        }
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
        return true;
    }
}
