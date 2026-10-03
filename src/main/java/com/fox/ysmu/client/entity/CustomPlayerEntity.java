package com.fox.ysmu.client.entity;

import static com.fox.ysmu.util.ControllerUtils.*;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.condition.ConditionArmor;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.client.model.CustomPlayerModel;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.molang.IMolangPhysicsScope;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.GeckoLibUtil;

public class CustomPlayerEntity implements IAnimatable, IMolangPhysicsScope {

    private final AnimationFactory factory = GeckoLibUtil.createFactory(this, true);
    private ResourceLocation mainModel = CustomPlayerModel.DEFAULT_MAIN_MODEL;
    private ResourceLocation texture = CustomPlayerModel.DEFAULT_TEXTURE;
    private final com.fox.ysmu.client.gui.PreviewAnimationInfo previewInfo = new com.fox.ysmu.client.gui.PreviewAnimationInfo();
    private EntityLivingBase entity = null;

    @NotNull
    private static <P extends IAnimatable> PlayState playLoopAnimation(AnimationEvent<P> event, String animationName) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName, ILoopType.EDefaultLoopTypes.LOOP));
        return PlayState.CONTINUE;
    }

    /**
     * 越往后优先级越高
     * <p>
     * Transition lengths are upstream's, from
     * {@code client/controller/collections/PlayerControllerCollection.java:31-71}: the parallel axes, the pre/post
     * slots and the armor slots are 0, {@code main} is 0.1, both hold controllers are 0.1, {@code swing} is 0,
     * {@code use} is 0.1, {@code cap} is 0 and the two GUI preview channels are 0. They are not cosmetic: the engine
     * pins a controller's clock at 0 in {@code AnimationState.Transitioning} until {@code tick >=
     * transitionLengthTicks} ({@code AnimationController#process}), so a longer length spends the first frames of
     * every state change blending into the clip's first frame and cuts the tail off short one-shots. A model may
     * still override one per state through {@code blend_transition}
     * ({@code OpenYsmPlayerControllerRuntime}).
     */
    @Override

    @SuppressWarnings("all")
    public void registerControllers(AnimationData data) {
        AnimationManager manager = AnimationManager.getInstance();
        // B-06: every predicate is wrapped in `manager.scripted(...)`, which lets a pack script bound to that
        // controller decide first (`functions/<anything>@player_ctrl_<name>.molang`). Upstream does the same thing in
        // one place - its shared CodedAnimationController - and the wrapper costs one map lookup when the model binds
        // no script to that controller, which is the usual case.
        for (int i = 0; i < 8; i++) {
            String controllerName = String.format("pre_parallel_%d_controller", i);
            String animationName = String.format("pre_parallel%d", i);
            data.addAnimationController(
                new AnimationController<>(
                    this,
                    controllerName,
                    0,
                    manager.scripted(e -> manager.predicateParallel(e, animationName))));
        }
        // B-04: upstream registers `player.vehicle` right after the pre-parallel axis, with a 0.1 tick transition
        // (client/controller/collections/PlayerControllerCollection.java:36).
        data.addAnimationController(
            new AnimationController(this, VEHICLE_CONTROLLER, 0.1f, manager.scripted(manager::predicateVehicle)));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_PRE_MAIN_CONTROLLER, 0, manager.scripted(manager::predicateOpenYsmSlot)));
        data.addAnimationController(
            new AnimationController(this, MAIN_CONTROLLER, 0.1f, manager.scripted(manager::predicateMain)));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_POST_MAIN_CONTROLLER, 0, manager.scripted(manager::predicateOpenYsmSlot)));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_PRE_HOLD_CONTROLLER, 0, manager.scripted(manager::predicateOpenYsmSlot)));
        data.addAnimationController(
            new AnimationController(this, HOLD_OFFHAND_CONTROLLER, 0.1f, manager.scripted(manager::predicateOffhandHold)));
        data.addAnimationController(
            new AnimationController(this, HOLD_MAINHAND_CONTROLLER, 0.1f, manager.scripted(manager::predicateMainhandHold)));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_POST_HOLD_CONTROLLER, 0, manager.scripted(manager::predicateOpenYsmSlot)));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_PRE_SWING_CONTROLLER, 0, manager.scripted(manager::predicateOpenYsmSlot)));
        data.addAnimationController(
            new AnimationController(this, SWING_CONTROLLER, 0f, manager.scripted(manager::predicateSwing)));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_POST_SWING_CONTROLLER, 0, manager.scripted(manager::predicateOpenYsmSlot)));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_PRE_USE_CONTROLLER, 0, manager.scripted(manager::predicateOpenYsmSlot)));
        data.addAnimationController(
            new AnimationController(this, USE_CONTROLLER, 0.1f, manager.scripted(manager::predicateUse)));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_POST_USE_CONTROLLER, 0, manager.scripted(manager::predicateOpenYsmSlot)));
        // Upstream registers `player.passenger` after the use axis, also at 0.1
        // (PlayerControllerCollection.java:59).
        data.addAnimationController(
            new AnimationController(this, PASSENGER_CONTROLLER, 0.1f, manager.scripted(manager::predicatePassenger)));
        for (int i = 0; i < 8; i++) {
            String controllerName = String.format("parallel_%d_controller", i);
            String animationName = String.format("parallel%d", i);
            data.addAnimationController(
                new AnimationController<>(
                    this,
                    controllerName,
                    0,
                    manager.scripted(e -> manager.predicateParallel(e, animationName))));
        }
        // 为每个盔甲槽位注册控制器，使用1-4的索引值
        for (int slotIndex = 1; slotIndex <= 4; slotIndex++) {
            String controllerName = String.format("%s_controller", ConditionArmor.getSlotNameFromIndex(slotIndex));
            int finalSlotIndex = slotIndex;
            data.addAnimationController(
                new AnimationController(
                    this,
                    controllerName,
                    0,
                    manager.scripted(e -> manager.predicateArmor(e, finalSlotIndex))));
        }
        data.addAnimationController(
            new AnimationController(this, CAP_CONTROLLER, 0f, manager.scripted(manager::predicateCap)));
        // GUI preview channels, after the cap controller so a hover/focus animation wins for the bones they share.
        data.addAnimationController(
            new AnimationController(this, HOVER_CONTROLLER, 0f, manager.scripted(manager::predicateHover)));
        data.addAnimationController(
            new AnimationController(this, FOCUS_CONTROLLER, 0f, manager.scripted(manager::predicateFocus)));
        data.getAnimationControllers()
            .values()
            .forEach(controller -> {
                controller
                    .registerCustomInstructionListener(event -> MolangInstructionExecutor.execute(event.instructions));
                // A pack's `sound_effects` keyframes, which the port parsed and synced but never played. The owner is
                // the entity behind this animatable; an NPC or a GUI preview has none and falls into the sound
                // manager's local slot, which is also what keeps two players running the same model from sharing
                // bookkeeping for controllers they both name `cap_controller`.
                controller.registerSoundListener(
                    event -> {
                        String ctrlName = event.getController().getName();
                        com.fox.ysmu.client.audio.YSMSoundManager.onSoundKeyframe(
                            getPlayer(), ctrlName, event.sound, getMainModel());
                    });
            });
    }

    public ResourceLocation getMainModel() {
        // The engine's cache alone is not enough to answer this: it outlives a connection, so a model the previous
        // server had by this name is still in it. Asking whether *this connection* installed the model is what keeps
        // the answer agreeing with the predicates the renderer uses - if it disagreed, the renderer would substitute
        // the built-in default while this method still handed out the foreign id, and the engine would resolve that
        // id to the previous server's geometry.
        if (ClientModelManager.isModelPublished(this.mainModel)) {
            return mainModel;
        }
        return CustomPlayerModel.DEFAULT_MAIN_MODEL;
    }

    public void setMainModel(ResourceLocation mainModel) {
        this.mainModel = mainModel;
    }

    /**
     * The model this entity was asked for, before any substitution.
     * <p>
     * {@link #getMainModel()} deliberately answers with the built-in default while the requested model is not in the
     * engine's cache, which is right for everything that draws and wrong for anything that has to know *which* model
     * it is talking about - for example deciding whether that model's textures have been uploaded yet.
     */
    public ResourceLocation getRequestedMainModel() {
        return this.mainModel;
    }

    public ResourceLocation getAnimation() {
        if (GeckoLibCache.getInstance()
            .getAnimations()
            .containsKey(this.mainModel)) {
            return mainModel;
        }
        return CustomPlayerModel.DEFAULT_MAIN_ANIMATION;
    }

    public float getHeightScale() {
        // getMainModel(), not the raw field: while this model is still being built the renderer draws the built-in
        // default in its place, so the scale must be read for the model that is actually being drawn. The raw field
        // would only ever answer with the fallback below, which happens to match today and would silently stop
        // matching the moment the built-in default's scale changes.
        if (ClientModelManager.SCALE_INFO.containsKey(getMainModel())) {
            return ClientModelManager.SCALE_INFO.get(getMainModel())
                .left()
                .floatValue();
        }
        return 0.7f;
    }

    public float getWidthScale() {
        if (ClientModelManager.SCALE_INFO.containsKey(getMainModel())) {
            return ClientModelManager.SCALE_INFO.get(getMainModel())
                .right()
                .floatValue();
        }
        return 0.7f;
    }

    /**
     * Whether this model's layers - held item, armor, back attachments - are drawn before the model instead of after
     * it, which the pack declares with {@code render_layers_first}.
     * <p>
     * Upstream reads the same flag from the model's player settings ({@code CustomHumanoidEntity#renderLayersFirst})
     * and the engine asks for it through {@code IGeoRenderer#shouldRenderLayersFirst}. Defaults to {@code false}, the
     * order every model had before the flag was honoured.
     */
    public boolean shouldRenderLayersFirst() {
        return Boolean.TRUE.equals(ClientModelManager.RENDER_LAYERS_FIRST.get(getMainModel()));
    }

    /**
     * Whether this model has a vertex drawn translucently, which is the question upstream asks its baked model state
     * before choosing a render type ({@code GeoModelState#hasTranslucentVertices}, {@code nativeState
     * .getTranslucentVertexCount() != 0}, {@code geckolib3/geo/animated/GeoModelState.java:73-74}). A translucent
     * model is drawn with upstream's {@code CustomTranslucentRenderType} - blending and back-face culling - and a
     * flat decal depends on the culling, because the pack zeroes the uvs of the face it does not want and those faces
     * are still built.
     * <p>
     * Upstream's count is native and cannot be read here, so the port answers with the same input its bake has: the
     * texture that is about to be bound, sampled at the uvs of the faces the model actually draws (see
     * {@code ClientModelManager#TRANSLUCENT_TEXTURES}). Defaults to {@code false}, the cutout branch every model had
     * before this existed.
     */
    public boolean hasTranslucentVertices() {
        return ClientModelManager.TRANSLUCENT_TEXTURES.contains(CustomPlayerModel.textureFor(this));
    }

    /** The rendered entity, player or not. */
    public EntityLivingBase getEntity() {
        return entity;
    }

    public void setEntity(EntityLivingBase entity) {
        this.entity = entity;
    }

    /**
     * The rendered entity when it is a player.
     *
     * @return the player, or {@code null} for a non-player entity.
     */
    @Nullable
    public EntityPlayer getPlayer() {
        return entity instanceof EntityPlayer ? (EntityPlayer) entity : null;
    }

    public void setPlayer(EntityPlayer player) {
        this.entity = player;
    }

    // IMolangPhysicsScope: lets the GeckoLib engine key its per-frame MoLang scope on this animatable.

    /**
     * The player whose roaming variables a GUI preview tile is drawing, or {@code null} for anything else.
     * <p>
     * This is deliberately not {@link #entity}: setting that field would make {@link #getPlayer()} non-null, and the
     * preview predicates branch on exactly that to decide whether to play the tile's preview animation or the world
     * state machine, so the preview would stop animating. The owner only exists so the tile reads the same
     * {@code v.roaming.*} namespace the previewed model would read in the world; upstream has the same shape, where
     * the animation processor is handed a roaming struct for the entity behind the preview.
     */
    @Nullable
    private EntityPlayer previewOwner;

    /** Sets (or with {@code null} clears) the owner of the preview currently being drawn. */
    public void setPreviewOwner(@Nullable EntityPlayer previewOwner) {
        this.previewOwner = previewOwner;
    }

    /**
     * The roaming variables the tile should read. A world animatable is seeded from
     * {@code RemoteAnimationVariables} by the engine itself, so only a detached preview answers here.
     */
    @Override
    @Nullable
    public java.util.Map<String, Double> getMolangVariables() {
        EntityPlayer owner = this.previewOwner;
        if (owner == null || this.entity != null) {
            return null;
        }
        net.minecraft.util.ResourceLocation previewed = com.fox.ysmu.util.ModelIdUtil
            .getModelIdFromMainId(getMainModel());
        return com.fox.ysmu.client.roaming.ClientRoamingStore.valuesFor(
            owner.getUniqueID(),
            com.fox.ysmu.client.roaming.ClientRoamingKeys.keyFor(previewed));
    }

    @Override
    @Nullable
    public EntityLivingBase getMolangEntity() {
        return entity;
    }

    @Override
    public ResourceLocation getMolangModelId() {
        return getMainModel();
    }

    @Override
    public ResourceLocation getMolangAnimationId() {
        return getAnimation();
    }

    @Override

    public AnimationFactory getFactory() {
        return this.factory;
    }

    /**
     * The texture to draw. N-1: never returns {@code null} - the engine hands this straight to
     * {@code TextureManager.bindTexture(ResourceLocation)}, and a {@code null} there ends in a
     * {@code ReportedException("Registering texture")} crash. The field starts at the built-in default and
     * {@link #setTexture(ResourceLocation)} ignores {@code null}, so this is a second line of defence for
     * an override that was stored half-filled (for example a player selection whose texture string was
     * empty, which {@code SetModelAndTexture} decodes as {@code null}).
     */
    public ResourceLocation getTexture() {
        return texture == null ? CustomPlayerModel.DEFAULT_TEXTURE : texture;
    }

    /**
     * Sets the texture. A {@code null} argument is ignored rather than stored: the previous (non-null)
     * texture is kept, so the animatable can never hold a value that would crash the render path (N-1).
     */
    public void setTexture(ResourceLocation texture) {
        if (texture != null) {
            this.texture = texture;
        }
    }

    public String getPreviewAnimation() {
        return previewInfo.getPreview();
    }

    public void setPreviewAnimation(String previewAnimation) {
        previewInfo.setPreview(previewAnimation);
    }

    public void clearPreviewAnimation() {
        previewInfo.clear();
    }

    public boolean hasPreviewAnimation() {
        return previewInfo.hasPreview();
    }

    public boolean hasPreviewAnimation(String previewAnimation) {
        return previewInfo.hasPreview(previewAnimation);
    }

    /**
     * The preview channels this entity should play, mirroring upstream's {@code PreviewAnimationInfo}: the GUI drives
     * it (preview/hover/focus) and the cap, hover and focus controllers read it back.
     */
    public com.fox.ysmu.client.gui.PreviewAnimationInfo getPreviewInfo() {
        return previewInfo;
    }
}
