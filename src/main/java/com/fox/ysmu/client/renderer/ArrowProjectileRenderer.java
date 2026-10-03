package com.fox.ysmu.client.renderer;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.eliotlash.mclib.math.IValue;
import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ActiveAnimation;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ProjectileState;
import com.fox.ysmu.client.animation.controller.ProjectileTimelineRuntime;
import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.util.IProjectileModelArrow;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.ProjectileGroundTracker;
import com.fox.ysmu.util.ProjectileShootItemIds;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.easing.EasingManager;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.core.util.MathUtil;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Draws a pack's projectile sub-entity (the {@code files.projectiles} entry, e.g. {@code #arrow}) in place of a
 * vanilla entity. Called from {@code MixinRenderArrow} when the arrow carries a model id in its datawatcher.
 *
 * <p>The engine has no equivalent of upstream's {@code GeoProjectilesRenderer}: a projectile is not an
 * {@code IAnimatable} and never reaches an {@code AnimationProcessor}, so its keyframes are sampled here by hand
 * ({@link #applyActiveAnimations}) and its bones are drawn through the player renderer's cube walker.</p>
 *
 * <h3>Why the lighting is turned off while drawing</h3>
 * <p>Measured against the official client at the same angle, a projectile's white outline frame sits at about
 * 230/255 on its darkest edge and 255 everywhere else - effectively flat. 1.7.10 wraps its entity pass in
 * {@code RenderHelper.enableStandardItemLighting()} (ambient 0.4 plus two 0.6 directional lights plus
 * {@code GL_FLAT}), so a face whose normal points away from both lights lands exactly on 0.4. Projectile models
 * routinely squash a sub-model into a card with {@code scale} (this pack's {@code Arrow_E.scale = (1,1,0.01)}), and
 * the three faces keep the normals they had before the squash, so one of them is driven to 0.4 - which is what turned
 * a white frame into 101/255 and a translucent blue part into a visibly black-bottomed one. Disabling the directional
 * light for the duration of the draw puts every face at 1.0, within a hair of the measured 0.90.</p>
 */
public final class ArrowProjectileRenderer {

    /** {@code ysm.on_ground_time} per arrow: vanilla keeps {@code ticksInGround} private and NBT-only. */
    private static final ProjectileGroundTracker GROUND_TRACKER = new ProjectileGroundTracker();

    /** Rate limit for the per-arrow diagnostics: at most one line per arrow per 20 ticks. */
    private static final Map<Integer, Integer> LAST_DEBUG_DUMP_TICK = new HashMap<>();

    /** Projectile geometries whose bone tree has already been dumped, so DebugModelLoad does not reprint it. */
    private static final Set<ResourceLocation> DUMPED_TREES = Collections
        .newSetFromMap(new ConcurrentHashMap<ResourceLocation, Boolean>());

    /**
     * Dedup for the warnings on this per-frame path: a model missing its geometry or texture would otherwise print
     * once per frame, per arrow. Reset through {@link #clearDumpedTrees} on a reload.
     */
    private static final Set<String> LOGGED_RENDER_WARNS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private ArrowProjectileRenderer() {}

    /** Clears the once-per-model/per-message dedup tables, so they report again after a reload. */
    public static void clearDumpedTrees() {
        DUMPED_TREES.clear();
        LOGGED_RENDER_WARNS.clear();
        LAST_DEBUG_DUMP_TICK.clear();
    }

    /**
     * Turns off the fixed-pipeline directional light for the duration of a projectile draw.
     *
     * @return whether this call turned it off, i.e. whether it has to be restored
     */
    private static boolean beginFlatProjectileLighting() {
        if (!GL11.glIsEnabled(GL11.GL_LIGHTING)) {
            return false;
        }
        GL11.glDisable(GL11.GL_LIGHTING);
        return true;
    }

    /** Restores what {@link #beginFlatProjectileLighting()} turned off. */
    private static void endFlatProjectileLighting(boolean disabledByUs) {
        if (disabledByUs) {
            GL11.glEnable(GL11.GL_LIGHTING);
        }
    }

    /** A warning on the per-frame path, printed once per distinct message. */
    private static void warnOnce(String tag, String format, Object... args) {
        String message = String.format(Locale.ROOT, format, args);
        if (LOGGED_RENDER_WARNS.add(tag + "|" + message)) {
            ysmu.LOG.warn("[YSMU-ARROW] {}", message);
        }
    }

    /**
     * Tries to draw a custom projectile model for {@code entity}.
     *
     * @return {@code true} when a custom model was drawn, so the caller must skip vanilla rendering; {@code false}
     *         when there is nothing to draw, so vanilla should proceed
     */
    @SuppressWarnings("unchecked")
    public static boolean render(Entity entity, double x, double y, double z, float yaw, float partialTicks,
        ResourceLocation modelId, IGeoRenderer<?> geoRenderer) {
        if (modelId == null || entity == null) {
            return false;
        }
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_RENDER) {
            ysmu.LOG.info("[YSMU-ARROW] render start: modelId={}, entityId={}", modelId, entity.getEntityId());
        }

        List<String> projectileTypes = ClientModelManager.PROJECTILE_MODEL_IDS.get(modelId);
        if (projectileTypes == null) {
            warnOnce("no-projectile-models", "no projectile sub-entity is registered for {}", modelId);
            return false;
        }

        // This renderer only handles arrows, so pick the entry that names one. Both "minecraft:arrow" and the pack's
        // "#arrow" spelling contain it.
        String arrowType = null;
        for (String type : projectileTypes) {
            if (type.contains("arrow")) {
                arrowType = type;
                break;
            }
        }
        if (arrowType == null) {
            warnOnce("no-arrow-type", "no arrow entry among {} for {}", projectileTypes, modelId);
            return false;
        }

        ResourceLocation projectileGeoId = ModelIdUtil.getSubModelId(modelId, "projectile_" + arrowType);
        // A projectile can be drawn before its owner's model has ever been: it may belong to another player, or the
        // shooter may have left. This client parks geometry and animations until something asks for them, so ask here.
        // A build takes a frame or two, during which this returns false and the arrow is drawn vanilla - which is the
        // correct fallback rather than an invisible arrow.
        ClientModelManager.ensureGeometry(modelId);
        ClientModelManager.ensureAnimations(ModelIdUtil.getMainId(modelId));
        GeoModel projectileModel = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(projectileGeoId);
        if (projectileModel == null) {
            warnOnce("geo-not-found", "projectile geometry {} is not registered for {}", projectileGeoId, modelId);
            return false;
        }

        List<ResourceLocation> projectileTextures = ClientModelManager.PROJECTILE_TEXTURE_IDS.get(modelId);
        ResourceLocation projectileTextureId = null;
        if (projectileTextures != null) {
            String prefix = "projectile_" + arrowType + "_";
            for (ResourceLocation textureId : projectileTextures) {
                if (textureId.getResourcePath()
                    .contains(prefix)) {
                    projectileTextureId = textureId;
                    break;
                }
            }
        }
        if (projectileTextureId == null) {
            warnOnce("no-texture", "no projectile texture is registered for {}", modelId);
            return false;
        }

        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_RENDER && DUMPED_TREES.add(projectileGeoId)) {
            ysmu.LOG.info("[YSMU-ARROW] bone tree of {}:", projectileGeoId);
            dumpBoneTree(projectileModel.topLevelBones, 0);
        }

        boolean lightingDisabled = false;
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);

            // Vanilla's arrow orientation: yaw - 90 about Y, then pitch about Z.
            float interpYaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
            float interpPitch = entity.prevRotationPitch
                + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
            GL11.glRotatef(interpYaw - 90.0F, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(interpPitch, 0.0F, 0.0F, 1.0F);
            GL11.glScalef(0.7f, 0.7f, 0.7f);

            net.geckominecraft.client.renderer.GlStateManager.disableCull();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            Minecraft.getMinecraft().renderEngine.bindTexture(projectileTextureId);

            if (entity instanceof EntityArrow arrow) {
                // The x/y/z vanilla passes in are camera-relative (world - renderPos) and only usable for
                // glTranslated. A timeline's ysm.particle(...) needs world coordinates, so the interpolated world
                // position is computed separately - passing the relative one puts particles thousands of blocks away.
                double worldX = arrow.lastTickPosX + (arrow.posX - arrow.lastTickPosX) * partialTicks;
                double worldY = arrow.lastTickPosY + (arrow.posY - arrow.lastTickPosY) * partialTicks;
                double worldZ = arrow.lastTickPosZ + (arrow.posZ - arrow.lastTickPosZ) * partialTicks;
                applyProjectileAnimations(
                    projectileModel,
                    arrow,
                    partialTicks,
                    projectileGeoId,
                    modelId,
                    arrowType,
                    worldX,
                    worldY,
                    worldZ,
                    interpYaw,
                    interpPitch);
            }

            // A translucent skin needs blending: this model's two outer cards are only 1-23% opaque in the texture,
            // and drawing them through renderRecursively bypasses the blend state that IGeoRenderer#render would have
            // set, so the transparent pixels come out opaque - "the inner blue art solid, the white frame a filled
            // quad" instead of nearly invisible. (The dark underside is not transparency: alpha test already drops
            // those texels. It is the 0.4 directional light described in the class javadoc.)
            net.geckominecraft.client.renderer.GlStateManager.disableCull();
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            net.geckominecraft.client.renderer.GlStateManager.enableBlend();
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            lightingDisabled = beginFlatProjectileLighting();

            // renderRecursively applies the bone pivots/rotations and walks the children.
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_QUADS);
            for (GeoBone bone : projectileModel.topLevelBones) {
                ((IGeoRenderer<Entity>) geoRenderer).renderRecursively(
                    tessellator,
                    entity,
                    bone,
                    1.0F,
                    1.0F,
                    1.0F,
                    1.0F);
            }
            tessellator.draw();
        } catch (Throwable failure) {
            // A per-frame path: even an Error has to be caught, or vanilla builds a CrashReport per frame. Dedup by
            // content so one broken model cannot flood the log.
            String key = "render|" + failure.getClass()
                .getName() + '|' + failure.getMessage();
            if (LOGGED_RENDER_WARNS.add(key)) {
                ysmu.LOG.warn("[YSMU-ARROW] projectile model render failed", failure);
            }
        } finally {
            // In a finally block: an exception mid-draw must not leave the light off for the rest of the frame.
            endFlatProjectileLighting(lightingDisabled);
            net.geckominecraft.client.renderer.GlStateManager.disableBlend();
            net.geckominecraft.client.renderer.GlStateManager.enableCull();
            GL11.glPopMatrix();
        }
        return true;
    }

    /**
     * Samples the active projectile animations onto {@code model}'s bones before the draw.
     *
     * @param renderX the entity's interpolated <b>world</b> position, which is the model's origin, for the timeline's
     *        {@code ysm.particle(...)} calls - the vanilla {@code doRender} coordinates are camera-relative
     */
    private static void applyProjectileAnimations(GeoModel model, EntityArrow arrow, float partialTicks,
        ResourceLocation projectileGeoId, ResourceLocation modelId, String arrowType, double renderX, double renderY,
        double renderZ, float interpYaw, float interpPitch) {
        AnimationFile animationFile = GeckoLibCache.getInstance()
            .getAnimations()
            .get(projectileGeoId);
        if (animationFile == null || animationFile.animations == null) {
            // Nothing to sample, so the geometry stays in its bind pose.
            return;
        }

        double ageInTicks = arrow.ticksExisted + partialTicks;

        // In-ground detection reads the position delta rather than the motion vector: 1.7.10's EntityArrow leaves
        // motionX/Y/Z non-zero on a stuck arrow (onCollide does not always clear them), while prevPos vs pos is
        // reliable.
        double deltaX = arrow.posX - arrow.prevPosX;
        double deltaY = arrow.posY - arrow.prevPosY;
        double deltaZ = arrow.posZ - arrow.prevPosZ;
        double deltaLength = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        boolean isInGround = !arrow.isDead && (deltaLength < 0.0001 || arrow.onGround);

        boolean debugThisTick = Config.DEBUG_ANIMATION && allowArrowDebug(arrow.getEntityId(), arrow.ticksExisted);

        setMolangVariable("ysm.in_ground", isInGround ? 1.0 : 0.0);
        setMolangVariable("ysm.delta_movement_length", deltaLength);

        // ysm.shoot_item_id: "the item that fired this arrow". 1.7.10 never syncs the firing weapon to the client
        // (shootingEntity is server-side only), so MixinEntityArrow captured the shooter's held item into a
        // datawatcher slot at construction and it is translated here into the modern id a pack writes - GTNH's
        // crossbow is TConstruct:Crossbow, not minecraft:crossbow. Must be a POOLED STRING id: writing 1 here would
        // collide with whatever string the pool put at id 1.
        String shootItemId = arrow instanceof IProjectileModelArrow projectileArrow
            ? projectileArrow.ysmu$getShootItemId()
            : null;
        setMolangVariable("ysm.shoot_item_id", MolangStringPool.intern(ProjectileShootItemIds.toModernId(shootItemId)));

        if (arrow.isDead) {
            GROUND_TRACKER.forget(arrow.getEntityId());
            ProjectileTimelineRuntime.forget(arrow.getEntityId(), projectileGeoId);
            ProjectileControllerRuntime.cleanupEntity(arrow.getEntityId());
        }
        setMolangVariable("ysm.on_ground_time", GROUND_TRACKER.update(
            arrow.getEntityId(),
            isInGround,
            arrow.ticksExisted));

        // Reset to the bind pose before sampling, and remember it the first time this geometry is drawn.
        saveInitialSnapshots(model.topLevelBones);
        resetBonesToSnapshot(model.topLevelBones);

        List<ActiveAnimation> activeEntries = ProjectileControllerRuntime.getActiveAnimationEntries(
            arrow.getEntityId(),
            projectileGeoId,
            ageInTicks,
            ProjectileState.of(isInGround, arrow.isInWater(), arrow.isBurning()));
        List<String> activeAnimations = new java.util.ArrayList<>(activeEntries.size());
        // Each animation's clock origin: a state animation counts from the tick its state was entered, everything
        // else from the entity's birth (origin 0).
        Map<String, Double> clockOrigins = new HashMap<>();
        for (ActiveAnimation entry : activeEntries) {
            activeAnimations.add(entry.name);
            if (entry.startTick != 0.0d) {
                clockOrigins.put(entry.name, entry.startTick);
            }
        }
        if (debugThisTick) {
            ysmu.LOG.info(
                "[YSMU-ARROW] entity={} inGround={} inWater={} burning={} active={}",
                arrow.getEntityId(),
                isInGround,
                arrow.isInWater(),
                arrow.isBurning(),
                activeAnimations);
        }
        if (activeAnimations.isEmpty()) {
            // No animation is active, so the bind pose is what should be drawn.
            return;
        }

        applyActiveAnimations(model, animationFile, activeAnimations, clockOrigins, ageInTicks, debugThisTick);

        // The timeline has to run after the bones are written: a model's instruction may read this frame's pose
        // through ysm.bone_pivot_abs(...).
        dispatchProjectileTimelines(
            arrow,
            projectileGeoId,
            animationFile,
            activeAnimations,
            ageInTicks,
            model,
            renderX,
            renderY,
            renderZ,
            interpYaw,
            interpPitch);
    }

    /**
     * Dispatches the projectile's timeline instructions - the flight trail and impact splashes a pack writes as
     * {@code ysm.particle(...)}.
     *
     * <p>Four things are set up for the dispatch, mirroring {@code ArrowProjectileRenderer.java:443-459} in the
     * reference branch:</p>
     * <ol>
     * <li>the particle function's subject is this arrow ({@code ParticleEffectUtil.setCurrentEntity});</li>
     * <li>the particle offset base and rotation are the arrow's render transform, so a model-space offset written as
     * {@code bone_pivot_abs(...)/16*0.7} lands in the world where the model actually is
     * ({@code beginProjectileTransform});</li>
     * <li>the bone table behind {@code ysm.bone_pivot_abs(...)} is this projectile's own geometry
     * ({@code MolangPhysicsRuntime.beginProjectileBones}): a projectile never registers with a player
     * {@code AnimationProcessor}, so without this table the function answers 0 and a model-space offset evaluates to
     * the entity origin instead of the named bone;</li>
     * <li>all of them are restored in a {@code finally}, so the player render path is unaffected.</li>
     * </ol>
     */
    private static void dispatchProjectileTimelines(EntityArrow arrow, ResourceLocation projectileGeoId,
        AnimationFile animationFile, List<String> activeAnimations, double ageInTicks, GeoModel model, double renderX,
        double renderY, double renderZ, float interpYaw, float interpPitch) {
        Entity previousEntity = ParticleEffectUtil.getCurrentEntity();
        ParticleEffectUtil.setCurrentEntity(arrow);
        ParticleEffectUtil.beginProjectileTransform(renderX, renderY, renderZ, interpYaw, interpPitch);
        Map<String, IBone> previousBones = MolangPhysicsRuntime.beginProjectileBones(model.topLevelBones);
        try {
            ProjectileTimelineRuntime.dispatch(
                arrow.getEntityId(),
                projectileGeoId,
                animationFile,
                activeAnimations,
                ageInTicks);
        } catch (Throwable failure) {
            // Per-frame path: dedup by content, because a model's expression may reference a function this
            // environment does not have and would otherwise print every frame.
            String key = "timeline|" + failure.getClass()
                .getName() + '|' + failure.getMessage();
            if (LOGGED_RENDER_WARNS.add(key)) {
                ysmu.LOG.warn("[YSMU-ARROW] projectile timeline dispatch failed", failure);
            }
        } finally {
            MolangPhysicsRuntime.endProjectileBones(previousBones);
            ParticleEffectUtil.endProjectileTransform();
            ParticleEffectUtil.setCurrentEntity(previousEntity);
        }
    }

    /**
     * Writes the keyframes of {@code activeAnimations} onto {@code model}'s bones, in list order: a later animation
     * overwrites an earlier one bone by bone, which is how {@link ProjectileControllerRuntime}'s priority is realised.
     *
     * <p>Package-visible and free of entity and GL state on purpose, so a plain unit test can replay a real model's
     * projectile animations and inspect the resulting transforms.</p>
     *
     * <p>This overload samples every animation against the entity's age (origin 0); the render path uses the
     * clock-origin overload below so a controller state animation is timed from its state entry.</p>
     */
    static void applyActiveAnimations(GeoModel model, AnimationFile animationFile, List<String> activeAnimations,
        double ageInTicks, boolean debugThisTick) {
        applyActiveAnimations(model, animationFile, activeAnimations, Collections.emptyMap(), ageInTicks, debugThisTick);
    }

    /** As above, with each animation's clock origin; a missing origin means origin 0 (the entity's age). */
    static void applyActiveAnimations(GeoModel model, AnimationFile animationFile, List<String> activeAnimations,
        Map<String, Double> clockOrigins, double ageInTicks, boolean debugThisTick) {
        for (String animationName : activeAnimations) {
            Animation animation = animationFile.animations.get(animationName);
            if (animation == null || animation.boneAnimations == null) {
                continue;
            }
            Double clockOrigin = clockOrigins.get(animationName);
            double animationAge = ageInTicks - (clockOrigin != null ? clockOrigin : 0.0d);

            double animationLength = animation.animationLength != null ? animation.animationLength : 0;
            double animationTick;
            if (animationLength > 0) {
                // A one-shot clip is clamped, not wrapped: wrapping restarts it repeatedly, which makes a bone scale
                // oscillate between 0 and 1 instead of settling.
                boolean isLooping = animation.loop != null && animation.loop.isRepeatingAfterEnd()
                    && animation.loop != ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
                animationTick = isLooping ? animationAge % animationLength : Math.min(animationAge, animationLength);
            } else {
                animationTick = animationAge;
            }

            for (BoneAnimation boneAnimation : animation.boneAnimations) {
                GeoBone bone = findBone(model.topLevelBones, boneAnimation.boneName);
                if (bone == null) {
                    continue;
                }
                BoneSnapshot snapshot = bone.getInitialSnapshot();

                applyKeyFrameListRotation(
                    bone,
                    boneAnimation.rotationKeyFrames,
                    animationTick,
                    snapshot.rotationValueX,
                    snapshot.rotationValueY,
                    snapshot.rotationValueZ);
                applyKeyFrameListPosition(
                    bone,
                    boneAnimation.positionKeyFrames,
                    animationTick,
                    snapshot.positionOffsetX,
                    snapshot.positionOffsetY,
                    snapshot.positionOffsetZ);
                applyKeyFrameListScale(
                    bone,
                    boneAnimation.scaleKeyFrames,
                    animationTick,
                    snapshot.scaleValueX,
                    snapshot.scaleValueY,
                    snapshot.scaleValueZ,
                    debugThisTick);
            }
        }
    }

    private static void saveInitialSnapshots(List<GeoBone> bones) {
        if (bones == null) {
            return;
        }
        for (GeoBone bone : bones) {
            bone.saveInitialSnapshot();
            saveInitialSnapshots(bone.childBones);
        }
    }

    private static void resetBonesToSnapshot(List<GeoBone> bones) {
        if (bones == null) {
            return;
        }
        for (GeoBone bone : bones) {
            // Clear persistent hiding: renderRecursively's "any scale axis is 0" check is the only visibility gate
            // that should decide, and a stale hidden flag would outlive the animation that set it.
            if (bone.isHidden()) {
                bone.setHidden(false);
            }
            BoneSnapshot snapshot = bone.getInitialSnapshot();
            if (snapshot != null) {
                bone.setRotationX(snapshot.rotationValueX);
                bone.setRotationY(snapshot.rotationValueY);
                bone.setRotationZ(snapshot.rotationValueZ);
                bone.setPositionX((float) snapshot.positionOffsetX);
                bone.setPositionY((float) snapshot.positionOffsetY);
                bone.setPositionZ((float) snapshot.positionOffsetZ);
                bone.setScaleX((float) snapshot.scaleValueX);
                bone.setScaleY((float) snapshot.scaleValueY);
                bone.setScaleZ((float) snapshot.scaleValueZ);
            }
            resetBonesToSnapshot(bone.childBones);
        }
    }

    private static GeoBone findBone(List<GeoBone> bones, String name) {
        if (bones == null) {
            return null;
        }
        for (GeoBone bone : bones) {
            if (bone.name.equals(name)) {
                return bone;
            }
            GeoBone found = findBone(bone.childBones, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void applyKeyFrameListRotation(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapshotX, double snapshotY, double snapshotZ) {
        if (!hasAllAxes(frames)) {
            return;
        }
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            // Rotation and position are ADDED to the bind pose (the engine's convention); scale is multiplied.
            bone.setRotationX((float) (result[0] + snapshotX));
            bone.setRotationY((float) (result[1] + snapshotY));
            bone.setRotationZ((float) (result[2] + snapshotZ));
        }
    }

    private static void applyKeyFrameListPosition(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapshotX, double snapshotY, double snapshotZ) {
        if (!hasAllAxes(frames)) {
            return;
        }
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            bone.setPositionX((float) (result[0] + snapshotX));
            bone.setPositionY((float) (result[1] + snapshotY));
            bone.setPositionZ((float) (result[2] + snapshotZ));
        }
    }

    private static void applyKeyFrameListScale(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapshotX, double snapshotY, double snapshotZ, boolean debug) {
        if (!hasAllAxes(frames)) {
            return;
        }
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            bone.setScaleX((float) (result[0] * snapshotX));
            bone.setScaleY((float) (result[1] * snapshotY));
            bone.setScaleZ((float) (result[2] * snapshotZ));
        }
    }

    /**
     * Whether a bone channel carries data on all three axes.
     *
     * <p>This has to be an "all three axes" test rather than a {@code frames != null} test. The parser leaves a
     * present-but-empty {@code VectorKeyFrameList} behind for a channel the model never wrote, {@link #evaluateAxis}
     * answers 0 for each of its empty axes, and so an animation that only rotates would write scale 0 over whatever a
     * previous animation had set. A real case: one projectile's outer bone is scaled to 0.9 by {@code parallel2} and
     * only rotated by {@code parallel3}, and the scale ended up pinned at 0, so the rotating hexagon was invisible
     * once it landed. The engine's own player path writes a channel only when all of its points are non-empty.</p>
     */
    private static boolean hasAllAxes(VectorKeyFrameList<KeyFrame<IValue>> frames) {
        return frames != null && frames.xKeyFrames != null
            && !frames.xKeyFrames.isEmpty()
            && frames.yKeyFrames != null
            && !frames.yKeyFrames.isEmpty()
            && frames.zKeyFrames != null
            && !frames.zKeyFrames.isEmpty();
    }

    /** Evaluates a three-axis channel at {@code tick}, returning {@code [x, y, z]}. */
    private static float[] evaluateKeyFrameList(VectorKeyFrameList<KeyFrame<IValue>> frames, double tick) {
        if (frames == null) {
            return null;
        }
        float[] result = new float[3];
        result[0] = evaluateAxis(frames.xKeyFrames, tick);
        result[1] = evaluateAxis(frames.yKeyFrames, tick);
        result[2] = evaluateAxis(frames.zKeyFrames, tick);
        return result;
    }

    /** Evaluates one axis of a channel at {@code tick}, easing between the surrounding keyframes. */
    private static float evaluateAxis(List<KeyFrame<IValue>> keyFrames, double tick) {
        if (keyFrames == null || keyFrames.isEmpty()) {
            return 0;
        }
        if (keyFrames.size() == 1) {
            return (float) keyFrames.get(0)
                .getEndValueDouble();
        }
        double totalTime = 0;
        for (int i = 0; i < keyFrames.size(); i++) {
            KeyFrame<IValue> frame = keyFrames.get(i);
            double frameLength = frame.getLength() != null ? frame.getLength() : 0;
            double nextTotal = totalTime + frameLength;
            if (nextTotal > tick || i == keyFrames.size() - 1) {
                double localTick = tick - totalTime;
                double progress = frameLength > 0 ? Math.min(localTick / frameLength, 1.0) : 1.0;
                EasingType easing = frame.easingType != null ? frame.easingType : EasingType.Linear;
                double easedProgress = EasingManager.ease(progress, easing, frame.easingArgs);
                return (float) MathUtil.lerp(
                    easedProgress,
                    frame.getStartValueDouble(),
                    frame.getEndValueDouble());
            }
            totalTime = nextTotal;
        }
        return (float) keyFrames.get(keyFrames.size() - 1)
            .getEndValueDouble();
    }

    /** Writes one projectile Molang variable into the shared parser table, registering it if absent. */
    private static void setMolangVariable(String name, double value) {
        MolangParser.VARIABLES.computeIfAbsent(name, key -> new LazyVariable(key, () -> 0.0D))
            .set(value);
    }

    /**
     * Rate limit for the per-arrow diagnostics: at most one line per arrow per 20 ticks.
     * <p>
     * The convention for a permanent diagnostic is that it needs both a debug switch and a rate limit - otherwise an
     * arrow sitting on screen prints a line every frame and the log is unusable exactly when it is needed.
     */
    private static boolean allowArrowDebug(int entityId, int entityTicks) {
        Integer last = LAST_DEBUG_DUMP_TICK.get(entityId);
        if (last != null && entityTicks - last < 20) {
            return false;
        }
        if (LAST_DEBUG_DUMP_TICK.size() > 512) {
            LAST_DEBUG_DUMP_TICK.clear();
        }
        LAST_DEBUG_DUMP_TICK.put(entityId, entityTicks);
        return true;
    }

    /** Dumps a geometry's bone tree, once per model, under DebugModelLoad + DebugModelRender. */
    private static void dumpBoneTree(List<GeoBone> bones, int indent) {
        if (bones == null) {
            return;
        }
        for (GeoBone bone : bones) {
            if (bone == null) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < indent; i++) {
                line.append("  ");
            }
            line.append("bone '")
                .append(bone.name)
                .append("' cubes=")
                .append(bone.childCubes != null ? bone.childCubes.size() : 0)
                .append(" childBones=")
                .append(bone.childBones != null ? bone.childBones.size() : 0)
                .append(" hidden=")
                .append(bone.isHidden());
            ysmu.LOG.info("[YSMU-ARROW] {}", line);
            dumpBoneTree(bone.childBones, indent + 1);
        }
    }
}
