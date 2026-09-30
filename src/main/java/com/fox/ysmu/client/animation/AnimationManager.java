package com.fox.ysmu.client.animation;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.fox.ysmu.client.animation.condition.*;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.animation.molang.CtrlScriptBinding;
import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.fox.ysmu.client.animation.molang.PackFunctionScript;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.ysmu;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.data.EntityClips;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class AnimationManager {

    private static AnimationManager MANAGER;
    private final Int2ObjectOpenHashMap<LinkedList<AnimationState>> data = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, Integer> swingProgressByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> useDurationByPlayer = new ConcurrentHashMap<>();

    public static AnimationManager getInstance() {
        if (MANAGER == null) {
            MANAGER = new AnimationManager();
        }
        return MANAGER;
    }

    /**
     * Idempotent "client disconnected / switched server" cleanup, the entry point `fix-core-ui`'s `CU-10` calls
     * (returned to `[animation]` by the phase 7 plan as `A-11`).
     *
     * <p>
     * Clears every per-player state this package keeps and never releases on its own:
     * <ul>
     * <li>{@code swingProgressByPlayer} / {@code useDurationByPlayer} (this class's two progress tables, A-11)</li>
     * <li>{@link RemotePlayerAnimationQueries} (remote-player interpolation state)</li>
     * <li>{@link RemotePlayerMotionStates} (remote-player onGround/isFlying flags)</li>
     * </ul>
     * Idempotence: the body only empties concurrent maps
     * ({@code ConcurrentHashMap.clear()} and the two {@code RemotePlayer*}.clear() calls), it does not depend on
     * call order, and repeating it has no effect. When {@link #MANAGER} has not been created yet the two local
     * tables are simply skipped and the rest is still cleared.
     */
    public static void clearPlayerState() {
        AnimationManager manager = MANAGER;
        if (manager != null) {
            manager.swingProgressByPlayer.clear();
            manager.useDurationByPlayer.clear();
        }
        RemotePlayerAnimationQueries.clear();
        RemotePlayerMotionStates.clear();
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playLoopAnimation(AnimationEvent<P> event, String animationName) {
        return playAnimation(event, animationName, ILoopType.EDefaultLoopTypes.LOOP);
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName,
        ILoopType loopType) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName, loopType));
        return PlayState.CONTINUE;
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName));
        return PlayState.CONTINUE;
    }

    public void register(AnimationState state) {
        if (data.containsKey(state.getPriority())) {
            data.get(state.getPriority())
                .add(state);
        } else {
            LinkedList<AnimationState> states = Lists.newLinkedList();
            states.add(state);
            data.put(state.getPriority(), states);
        }
    }

    public PlayState predicateParallel(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        if (Minecraft.getMinecraft()
            .isGamePaused()) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        return playLoopAnimation(event, animationName);
    }

    public PlayState predicateOpenYsmSlot(AnimationEvent<CustomPlayerEntity> event) {
        if (Minecraft.getMinecraft()
            .isGamePaused()) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        return controllerState == null ? PlayState.STOP : controllerState;
    }

    public PlayState predicateCap(AnimationEvent<CustomPlayerEntity> event) {
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            // GUI preview entity: upstream's CapPredicate plays the tile's preview channel (see
            // ModelPreviewAnimationState). Before this, nothing ever set the name and the tile stayed a frozen pose.
            return playPreviewChannel(event, animatable.getPreviewInfo().getPreview());
        }

        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null && eep.isPlayAnimation()) {
            if (eep.consumeReplayRequest() && event.getController() != null) {
                // A-05: pressing the same emote key again must restart its clip. The engine's setAnimation is a no-op
                // while it already holds that builder and was not told to reload, so the request has to be turned into
                // a reload - which is exactly what upstream's CapPredicate does with the entity's dirty flag
                // (client/animation/predicate/CapPredicate.java:23-28: clearExtraAnimationDirty + indicateReload).
                event.getController()
                    .markNeedsReload();
            }
            return playAnimation(event, eep.getAnimation());
        }
        return PlayState.STOP;
    }

    /** The GUI preview's hover channel (upstream {@code HoverPredicate}). */
    public PlayState predicateHover(AnimationEvent<CustomPlayerEntity> event) {
        return playPreviewChannel(event, event.getAnimatable().getPreviewInfo().getHover());
    }

    /** The GUI preview's focus channel (upstream {@code FocusPredicate}). */
    public PlayState predicateFocus(AnimationEvent<CustomPlayerEntity> event) {
        return playPreviewChannel(event, event.getAnimatable().getPreviewInfo().getFocus());
    }

    /**
     * Plays one of the three preview channels, but only on a preview entity: a model in the world never gets its
     * preview channels applied, and a channel that the model does not define (or the {@code empty} sentinel) stops the
     * controller instead of asking the engine for a missing animation.
     */
    private static PlayState playPreviewChannel(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        CustomPlayerEntity animatable = event.getAnimatable();
        if (animatable.getPlayer() != null || animatable.getMainModel() == null) {
            return PlayState.STOP;
        }
        net.minecraft.util.ResourceLocation modelId = com.fox.ysmu.util.ModelIdUtil
            .getModelIdFromMainId(animatable.getMainModel());
        if (!com.fox.ysmu.client.gui.ModelPreviewRegistry.isPlayable(modelId, animationName)) {
            return PlayState.STOP;
        }
        return playLoopAnimation(event, animationName);
    }

    @NotNull
    public PlayState predicateMain(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            // Non-player animatables (NPCs and companions handed to YSM by another mod) have no player
            // state, so the player-typed state machine below can never match and the main controller would
            // stop outright - meaning such an entity never played walk/idle at all. Drive the same
            // locomotion states from the animatable's own entity instead.
            return predicateEntityLocomotion(event);
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            rememberMainState(player, "");
            return controllerState;
        }
        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            if (!data.containsKey(i)) {
                continue;
            }
            LinkedList<AnimationState> states = data.get(i);
            for (AnimationState state : states) {
                if (state.getPredicate().test(player, event)) {
                    String animationName = state.getAnimationName();
                    if (!definesAnimation(event, animationName)) {
                        // The model does not define this state's animation. Handing it over anyway drives
                        // AnimationController#setAnimation into its "Could not load animation: ... Is it missing?"
                        // branch, which prints to stdout and leaves the controller with no fallback at all - so the
                        // model keeps whatever it was showing. For a model without `run` that is exactly "walking
                        // animates, sprinting does not". Fall through to the next matching state instead, which is
                        // what predicateEntityLocomotion has always done (a70479ad) and what playIfPresent does for
                        // the hold/swing controllers (D-A5).
                        warnMissingState(animationName, event);
                        continue;
                    }
                    ILoopType loopType = state.getLoopType();
                    rememberMainState(player, animationName);
                    return playAnimation(event, animationName, loopType);
                }
            }
        }
        rememberMainState(player, "");
        return PlayState.STOP;
    }

    /**
     * The main controller's currently selected state, per player - upstream's {@code HumanoidStateTracker}
     * main-animation cache, which its {@code ctrl.<state>} script queries test against
     * ({@code CtrlBinding#testCondition}).
     * <p>
     * Written by {@link #predicateMain} and read by the scripts of the controllers registered before `main`
     * (`pre_parallel`, `vehicle`, `pre_main`), so those see the previous frame's value. Upstream has the same
     * ordering, for the same reason: the cache is filled as the frame's controllers are processed in order.
     */
    private final Map<UUID, String> mainAnimationCache = new ConcurrentHashMap<>();

    private void rememberMainState(EntityPlayer player, String animationName) {
        if (animationName == null || animationName.isEmpty()) {
            mainAnimationCache.remove(player.getUniqueID());
        } else {
            mainAnimationCache.put(player.getUniqueID(), animationName);
        }
    }

    @Nullable
    private String mainStateOf(EntityPlayer player) {
        return mainAnimationCache.get(player.getUniqueID());
    }

    /**
     * Every registered main-state name. The {@code ctrl.<state>} script queries are keyed by this vocabulary, so it is
     * read off the state table rather than listing the names a second time.
     */
    public Set<String> registeredStateNames() {
        Set<String> names = new LinkedHashSet<>();
        for (LinkedList<AnimationState> states : data.values()) {
            for (AnimationState state : states) {
                names.add(state.getAnimationName());
            }
        }
        return names;
    }

    /**
     * Wraps a built-in predicate so a pack script bound to that controller is consulted first - see
     * {@link #predicateWithPackScript}. Applied at every registration site, because upstream binds scripts in the
     * shared controller class rather than per controller.
     */
    public AnimationController.IAnimationPredicate<CustomPlayerEntity> scripted(
        AnimationController.IAnimationPredicate<CustomPlayerEntity> fallback) {
        return event -> predicateWithPackScript(event, fallback::test);
    }

    /**
     * Runs the script a pack bound to this controller when it has one, and otherwise defers to the built-in predicate.
     * <p>
     * Upstream binds scripts inside {@code CodedAnimationController}: {@code updateModel} looks the handler up by
     * {@code name.replace(".", "_ctrl_")} and {@code process} lets it decide before the coded predicate
     * ({@code geckolib3/core/controller/CodedAnimationController.java:65-72,124-134}). This port wires its controllers
     * straight to their predicates, so the equivalent seam is here - the one place every controller's predicate passes
     * through - and it is applied at the registration site in {@code CustomPlayerEntity#registerControllers}.
     *
     * @param fallback the built-in predicate, used whenever the script has no opinion this frame
     */
    public PlayState predicateWithPackScript(AnimationEvent<CustomPlayerEntity> event,
        Function<AnimationEvent<CustomPlayerEntity>, PlayState> fallback) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null || event.getController() == null) {
            return fallback.apply(event);
        }
        PackFunctionScript script = PackUserFunctions.scriptForController(event.getController().getName());
        if (script == null) {
            return fallback.apply(event);
        }
        PlayState scripted = CtrlScriptBinding.evaluate(
            script,
            GeckoLibCache.getInstance().parser,
            player,
            event.getController(),
            mainStateOf(player));
        return scripted == null ? fallback.apply(event) : scripted;
    }

    /** States already reported as missing, so a state evaluated every frame warns once per model. */
    private static final java.util.Set<String> REPORTED_MISSING_STATES =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Whether the model defines an animation, answering {@code true} when its animation file is unknown.
     * <p>
     * Fail-open on purpose: {@link #hasAnimation} answers {@code false} for an unknown file, which is right where a
     * caller can stop, but in the main state loop it would skip every state and freeze a model whose animations simply
     * had not been registered yet.
     */
    private static boolean definesAnimation(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        AnimationFile file = GeckoLibCache.getInstance()
            .getAnimations()
            .get(getAnimationId(event));
        return file == null || file.animations.containsKey(animationName);
    }

    private static void warnMissingState(String animationName, AnimationEvent<CustomPlayerEntity> event) {
        String key = getAnimationId(event) + "|" + animationName;
        if (!REPORTED_MISSING_STATES.add(key)) {
            return;
        }
        ysmu.LOG.warn(
            "Model {} does not define the '{}' state; the next matching state is used instead, so that state falls "
                + "back rather than leaving the main controller without an animation",
            getAnimationId(event),
            animationName);
    }

    /**
     * Animation for a non-player animatable, in the order the answers are trusted.
     * <p>
     * A host mod that owns the animation logic for the entity - Touhou Little Maid decides a maid's clip from
     * its own priority table, {@code sit} included - pushes the single resulting clip through
     * {@code EntityAnimationApi}. That clip wins outright: the host has already resolved every rule this
     * method could re-derive, and the clip is replayed with the loop mode the host asked for.
     * <p>
     * Otherwise the model's own seat and locomotion states are used, and only states the model actually
     * defines are selected, so a model without {@code run}/{@code walk}/{@code idle} keeps the controller
     * stopped instead of being handed a missing animation.
     */
    @NotNull
    private PlayState predicateEntityLocomotion(AnimationEvent<CustomPlayerEntity> event) {
        EntityLivingBase entity = event.getAnimatable()
            .getEntity();
        if (entity == null) {
            return PlayState.STOP;
        }
        // A-05 / A-06③: a non-player animatable never reaches AnimationRegister#setParserValue (it takes an
        // EntityPlayer), so this is where the per-frame MoLang context - current entity plus per-axis movement -
        // is published. The player branch publishes the same context in setParserValue, which runs before any
        // controller is evaluated.
        MolangFrameContext.begin(entity);
        // The pack's own scripts resolve fn.<name> against the frame's model; a non-player animatable never reaches
        // AnimationRegister#setParserValue, so this is its publish point.
        PackUserFunctions.begin(event.getAnimatable() == null ? null : event.getAnimatable().getMainModel());
        // Already resolved by whoever owns the animation logic for this entity; replay it verbatim.
        EntityClips.Clip pushed = EntityClips.get(entity);
        if (pushed != null && hasAnimation(event, pushed.getName())) {
            return playAnimation(event, pushed.getName(), pushed.getLoopType());
        }
        // What she is holding, before what she is doing. The condition names are the ones the player path asks
        // through ConditionalHold (hold_mainhand$<registry name>), and both paths now build them with the shared
        // InnerClassify#holdConditionName helper (D-A3) - before that, a maid holding a sword fell back to walk or
        // idle instead of playing the model's own held-item animation.
        String heldAnimation = findEntityHoldAnimation(event, entity);
        if (heldAnimation != null) {
            return playLoopAnimation(event, heldAnimation);
        }
        // A sitting tameable - a vanilla cat or wolf, or a companion another mod hands us - is neither walking nor
        // idle, so it plays the model's own seat animation while the flag is set. The ending holds the last frame
        // rather than looping, because a seat animation is usually a transition into a pose and looping that
        // transition stands the entity up and sits it back down again every cycle. The flag is read through vanilla's
        // EntityTameable API, so this stays a general rule rather than knowledge of one mod's entity.
        if (entity instanceof EntityTameable && ((EntityTameable) entity).isSitting() && hasAnimation(event, "sit")) {
            return playAnimation(event, "sit", ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME);
        }
        if (entity.onGround) {
            if (entity.isSprinting() && hasAnimation(event, "run")) {
                return playLoopAnimation(event, "run");
            }
            if (Math.abs(event.getLimbSwingAmount()) > 0.05F && hasAnimation(event, "walk")) {
                return playLoopAnimation(event, "walk");
            }
        }
        return hasAnimation(event, "idle") ? playLoopAnimation(event, "idle") : PlayState.STOP;
    }

    /** Whether the model backing this animation event actually defines the named animation. */
    private static boolean hasAnimation(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        AnimationFile file = GeckoLibCache.getInstance()
            .getAnimations()
            .get(getAnimationId(event));
        return file != null && file.animations.containsKey(animationName);
    }

    public PlayState predicateOffhandHold(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }

        // A-01: 1.7.10 has no vanilla offhand slot (Backhand provides it). With Backhand present an *empty*
        // offhand must still reach ConditionalHold#doTest so it can answer `hold_offhand:empty`, so the
        // "offhand must hold something" precondition is gone; the slot's existence is what is checked now.
        if (!BackhandCompat.isBackhandLoaded()) {
            // Without the off-hand slot there is no off-hand pose to hold.
            return PlayState.STOP;
        }
        if (checkSwingAndUse(player, false)) {
            return playIfPresent(event, findHoldAnimation(event, player, false));
        }
        // A-07: upstream answers PAUSE here, not STOP (client/animation/predicate/OffhandPredicate.java:27-29).
        // Stopping made the off-hand clip reload mid-swing; pausing holds the pose the player had instead.
        return PlayState.PAUSE;
    }

    public PlayState predicateMainhandHold(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        if (!player.isSwingInProgress && !player.isUsingItem()) {
            // 1.7.10 has no crossbow (Items.CROSSBOW / CrossbowItem.isCharged do not exist), so the charged
            // branches stay commented out: hold_mainhand:charged_crossbow / hold_offhand:charged_crossbow are
            // unreachable here and ConditionNameDiagnostics reports them once per model.
            // ItemStack mainHandItem = player.getHeldItem();
            // if (mainHandItem.is(Items.CROSSBOW) && CrossbowItem.isCharged(mainHandItem)) {
            // return playAnimation(event, "hold_mainhand:charged_crossbow", ILoopType.EDefaultLoopTypes.LOOP);
            // }
            // ItemStack offhandItem = BackhandCompat.getOffhandItem(player);
            // if (offhandItem != null && offhandItem.is(Items.CROSSBOW) && CrossbowItem.isCharged(offhandItem)) {
            // return playAnimation(event, "hold_offhand:charged_crossbow", ILoopType.EDefaultLoopTypes.LOOP);
            // }
            if (player.fishEntity != null) {
                // D-A5: exist-checked, so a model without `hold_mainhand:fishing` gets STOP instead of driving
                // AnimationController#setAnimation into its "Could not load animation" branch every frame.
                return playIfPresent(event, "hold_mainhand:fishing", ILoopType.EDefaultLoopTypes.LOOP);
            }
        }

        // A-01: the "main hand must hold something" precondition is gone - an empty hand now reaches
        // ConditionalHold#doTest, which answers `hold_mainhand:empty` (1.20.1 MainHandHoldPredicate does the same).
        if (checkSwingAndUse(player, true)) {
            return playIfPresent(event, findHoldAnimation(event, player, true));
        }
        // A-07: upstream answers PAUSE here, not STOP (client/animation/predicate/MainhandPredicate.java:31-33).
        // Stopping made the held-item clip reload mid-swing; pausing holds the pose the player had instead.
        return PlayState.PAUSE;
    }

    public PlayState predicateSwing(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        if (!player.isSwingInProgress) {
            swingProgressByPlayer.remove(player.getUniqueID());
            return PlayState.STOP;
        }
        if (!player.isPlayerSleeping()) {
            if (markSwingStart(player)) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }
            boolean isMainHand = BackhandCompat.swingingArm(player);
            String conditionalAnimation = findSwingAnimation(event, player, isMainHand);
            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                return playAnimation(event, conditionalAnimation, ILoopType.EDefaultLoopTypes.PLAY_ONCE);
            }
            // A-02: the offhand fallback name is `swing_offhand` (1.20.1 ItemHoldAnimationPredicate), and both
            // fallbacks go through the exist-check so a model without them stops instead of spamming the log.
            return playIfPresent(
                event,
                isMainHand ? "swing_hand" : "swing_offhand",
                ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        }
        return PlayState.STOP;
    }

    private boolean markSwingStart(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        if (!player.isSwingInProgress) {
            swingProgressByPlayer.remove(playerId);
            return false;
        }
        int currentProgress = player.swingProgressInt;
        Integer previousProgress = swingProgressByPlayer.put(playerId, currentProgress);
        return previousProgress == null || currentProgress < previousProgress;
    }

    public PlayState predicateUse(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        if (player.isUsingItem() && !player.isPlayerSleeping()) {
            if (markUseStart(player)) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }
            boolean isMainHand = BackhandCompat.getUsedItemHand(player);
            String conditionalAnimation = findUseAnimation(event, player, isMainHand);
            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                return playAnimation(event, conditionalAnimation);
            }
            return playAnimation(event, isMainHand ? "use_mainhand" : "use_offhand", ILoopType.EDefaultLoopTypes.LOOP);
        }
        useDurationByPlayer.remove(player.getUniqueID());
        return PlayState.STOP;
    }

    private boolean markUseStart(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        if (!player.isUsingItem()) {
            useDurationByPlayer.remove(playerId);
            return false;
        }
        int currentDuration = player.getItemInUseDuration();
        Integer previousDuration = useDurationByPlayer.put(playerId, currentDuration);
        return previousDuration == null || currentDuration < previousDuration;
    }

    public PlayState predicateArmor(AnimationEvent<CustomPlayerEntity> event, int slotIndex) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        ItemStack itemBySlot = player.getEquipmentInSlot(slotIndex);
        if (itemBySlot == null) {
            return PlayState.STOP;
        }

        String conditionalAnimation = findArmorAnimation(event, player, slotIndex);
        if (StringUtils.isNoneBlank(conditionalAnimation)) {
            return playLoopAnimation(event, conditionalAnimation);
        }

        ResourceLocation animation = getAnimationId(event);
        String slotName = ConditionArmor.getSlotNameFromIndex(slotIndex);
        String defaultName = slotName + ":default";
        if (GeckoLibCache.getInstance()
            .getAnimations()
            .get(animation).animations.containsKey(defaultName)) {
            return playAnimation(event, defaultName, ILoopType.EDefaultLoopTypes.LOOP);
        }
        return PlayState.STOP;
    }

    /**
     * The {@code player.vehicle} controller (B-04): the per-vehicle clip a model declares, else the legacy riding
     * states.
     * <p>
     * The order is upstream's ({@code client/animation/predicate/VehiclePredicate.java:51-96}): the {@code vehicle$…}
     * condition name first, then the pig / saddleable / boat fallbacks, then {@code sit}. Upstream's mod-specific
     * branches (SWEM, the Touhou chair, CarryOn, maid vehicles) have no 1.7.10 counterpart, and its {@code Saddleable}
     * group is exactly the pig and the horse here. Falling through with {@code hasAnimation} rather than playing
     * unconditionally is this port's rule for every state that a model may not define.
     */
    public PlayState predicateVehicle(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null || player.ridingEntity == null || !player.ridingEntity.isEntityAlive()) {
            return PlayState.STOP;
        }
        ConditionalVehicle condition = ConditionManager.getVehicle(getAnimationId(event));
        if (condition != null) {
            String name = condition.doTest(player);
            if (StringUtils.isNoneBlank(name) && hasAnimation(event, name)) {
                return playLoopAnimation(event, name);
            }
        }
        Entity vehicle = player.ridingEntity;
        if (vehicle instanceof EntityPig && hasAnimation(event, "ride_pig")) {
            return playLoopAnimation(event, "ride_pig");
        }
        if (vehicle instanceof EntityHorse && hasAnimation(event, "ride")) {
            return playLoopAnimation(event, "ride");
        }
        if (vehicle instanceof EntityBoat && hasAnimation(event, "boat")) {
            return playLoopAnimation(event, "boat");
        }
        return hasAnimation(event, "sit") ? playLoopAnimation(event, "sit") : PlayState.STOP;
    }

    /**
     * The {@code player.passenger} controller (B-04): the clip a model declares for whoever is riding the player
     * ({@code passenger$…}), and nothing otherwise - upstream's predicate has no fallback either
     * ({@code client/animation/predicate/PassengerPredicate.java:23-35}).
     */
    public PlayState predicatePassenger(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null || player.riddenByEntity == null || !player.riddenByEntity.isEntityAlive()) {
            return PlayState.STOP;
        }
        ConditionalPassenger condition = ConditionManager.getPassenger(getAnimationId(event));
        if (condition == null) {
            return PlayState.STOP;
        }
        String name = condition.doTest(player);
        if (StringUtils.isNoneBlank(name) && hasAnimation(event, name)) {
            return playLoopAnimation(event, name);
        }
        return PlayState.STOP;
    }

    private static ResourceLocation getAnimationId(AnimationEvent<CustomPlayerEntity> event) {
        return event.getAnimatable()
            .getAnimation();
    }

    private static PlayState playIfPresent(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        return playIfPresent(event, animationName, null);
    }

    /**
     * Plays the name only when the model actually defines it (D-A5).
     *
     * <p>
     * Conditional names (`hold_mainhand:empty`, `swing_offhand`, the `held-item` fallbacks) are not part of every
     * model; calling {@code playAnimation} for a missing one drives {@code AnimationController#setAnimation} into
     * its "Could not load animation: ... Is it missing?" branch, which prints to stdout and leaves the controller
     * without a fallback. 1.20.1 guards the same calls with {@code playAnimationWithValid}. A {@code null}
     * loop type keeps the animation file's own loop setting, exactly like the previous behaviour.
     */
    private static PlayState playIfPresent(AnimationEvent<CustomPlayerEntity> event, String animationName,
        ILoopType loopType) {
        if (StringUtils.isBlank(animationName) || !hasAnimation(event, animationName)) {
            return PlayState.STOP;
        }
        return loopType == null ? playAnimation(event, animationName) : playAnimation(event, animationName, loopType);
    }

    /**
     * The held-item animation for an entity that is not a player, or null when the model has none for what she holds.
     * <p>
     * The id form is the one that answers "a sword in her hand": the same
     * {@code hold_mainhand$<registry name>} shape {@code ConditionalHold} tests, built by the single shared helper
     * {@link InnerClassify#holdConditionName} (D-A3), and it is only answered when the model has that clip, so a
     * model that does not name its items by registry id simply says nothing here. The ore-dictionary and "kind"
     * forms the player path also tries are not consulted on this path yet.
     */
    private static String findEntityHoldAnimation(AnimationEvent<CustomPlayerEntity> event, EntityLivingBase entity) {
        String byId = InnerClassify.holdConditionName(entity.getHeldItem(), true);
        if (StringUtils.isBlank(byId) || !hasAnimation(event, byId)) {
            return null;
        }
        return byId;
    }

    private static String findHoldAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        ResourceLocation id = getAnimationId(event);
        ConditionalHold conditionalHold = isMainHand ? ConditionManager.getHoldMainhand(id)
            : ConditionManager.getHoldOffhand(id);
        return conditionalHold == null ? null : conditionalHold.doTest(player, isMainHand);
    }

    private static String findSwingAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        // A-02: main hand and off hand have separate prefix sets (swing_offhand$/#/: on the off hand), so the
        // table is chosen by which arm is swinging rather than by handing both cases the main-hand table.
        ConditionalSwing conditionalSwing = ConditionManager.getSwing(getAnimationId(event), isMainHand);
        return conditionalSwing == null ? null : conditionalSwing.doTest(player, isMainHand);
    }

    private static String findUseAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        ResourceLocation id = getAnimationId(event);
        ConditionalUse conditionalUse = isMainHand ? ConditionManager.getUseMainhand(id)
            : ConditionManager.getUseOffhand(id);
        return conditionalUse == null ? null : conditionalUse.doTest(player, isMainHand);
    }

    private static String findArmorAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        int slotIndex) {
        ConditionArmor conditionArmor = ConditionManager.getArmor(getAnimationId(event));
        return conditionArmor == null ? null : conditionArmor.doTest(player, slotIndex);
    }

    private boolean checkSwingAndUse(EntityPlayer player, boolean isMainHand) {
        if (player.isSwingInProgress && BackhandCompat.swingingArm(player) == isMainHand) {
            return false;
        }
        return !player.isUsingItem() || BackhandCompat.getUsedItemHand(player) != isMainHand;
    }
}
