package com.fox.ysmu.client.animation.controller;

import static com.fox.ysmu.util.ControllerUtils.CAP_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_MAINHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_OFFHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.MAIN_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.OPENYSM_POST_SWING_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.SWING_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.USE_CONTROLLER;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.condition.ConditionArmor;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Transition;
import com.fox.ysmu.client.entity.CustomPlayerEntity;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.file.AnimationFile;

public final class OpenYsmPlayerControllerRuntime {

    private static final Map<StateKey, RuntimeState> STATES = new ConcurrentHashMap<>();

    private OpenYsmPlayerControllerRuntime() {}

    public static PlayState tryApply(AnimationEvent<CustomPlayerEntity> event) {
        if (event == null || event.getController() == null || event.getAnimatable() == null) {
            return null;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            return null;
        }
        // Install this model's animations before reading the controller table, for the same reason the animation
        // lookups do: getAnimation() answers with the built-in default while the model's own animations are parked,
        // so reading the registry first would apply the *default's* controller definitions to this model.
        ClientModelManager.ensureAnimations(animatable.getMainModel());
        ResourceLocation animationId = animatable.getAnimation();
        ControllerSet set = OpenYsmAnimationControllerRegistry.get(animationId);
        if (set == null) {
            return null;
        }

        String geckoControllerName = event.getController().getName();
        for (ControllerMatch match : resolveControllers(set, geckoControllerName)) {
            PlayState result = tryApplyController(event, player, animationId, geckoControllerName, match);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    static void clear() {
        STATES.clear();
    }

    private static PlayState tryApplyController(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        ResourceLocation animationId, String geckoControllerName, ControllerMatch match) {
        RuntimeState runtimeState = runtimeState(player, animationId, geckoControllerName, match.controller.name);
        OpenYsmControllerExpressionEvaluator.Context context = new OpenYsmControllerExpressionEvaluator.Context(
            event,
            player,
            runtimeState);
        prepareFrameVariables(geckoControllerName, player, runtimeState, context);
        State state = ensureState(event, match.controller, runtimeState, context);
        if (state == null) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            State nextState = applyTransition(event, match.controller, state, runtimeState, context);
            if (nextState == state) {
                break;
            }
            state = nextState;
        }

        String animationName = selectAnimation(state, match.preferredAnimationName, context);
        reportDroppedEntries(animationId, geckoControllerName, state, animationName, context);
        if (StringUtils.isBlank(animationName) || !animationExists(animationId, animationName)) {
            return null;
        }
        if (SWING_CONTROLLER.equals(geckoControllerName) && "attack_empty".equals(animationName)) {
            return null;
        }
        if (state.blendTransitionTicks >= 0f) {
            event.getController().transitionLengthTicks = state.blendTransitionTicks;
        }
        applyAnimation(event, runtimeState, state, animationName);
        return PlayState.CONTINUE;
    }

    private static RuntimeState runtimeState(EntityPlayer player, ResourceLocation animationId,
        String geckoControllerName, String openYsmControllerName) {
        StateKey key = new StateKey(player.getUniqueID(), animationId, geckoControllerName, openYsmControllerName);
        RuntimeState state = STATES.get(key);
        if (state == null) {
            state = new RuntimeState();
            STATES.put(key, state);
        }
        return state;
    }

    private static State ensureState(AnimationEvent<CustomPlayerEntity> event, Controller controller,
        RuntimeState runtimeState, OpenYsmControllerExpressionEvaluator.Context context) {
        State state = controller.states.get(runtimeState.currentState);
        if (state != null) {
            return state;
        }
        State initial = controller.getInitialState();
        if (initial == null) {
            return null;
        }
        runtimeState.currentState = initial.name;
        runtimeState.enteredTick = event.getAnimationTick();
        runtimeState.lastSelectedAnimationState = "";
        runtimeState.lastSelectedAnimation = "";
        OpenYsmControllerExpressionEvaluator.executeStatements(initial.onEntry, context);
        return initial;
    }

    private static State applyTransition(AnimationEvent<CustomPlayerEntity> event, Controller controller, State state,
        RuntimeState runtimeState, OpenYsmControllerExpressionEvaluator.Context context) {
        for (Transition transition : state.transitions) {
            State target = controller.states.get(transition.targetState);
            if (target == null) {
                continue;
            }
            if (!OpenYsmControllerExpressionEvaluator.evaluateBoolean(transition.condition, context)) {
                continue;
            }
            OpenYsmControllerExpressionEvaluator.executeStatements(state.onExit, context);
            runtimeState.currentState = target.name;
            runtimeState.enteredTick = event.getAnimationTick();
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
            OpenYsmControllerExpressionEvaluator.executeStatements(target.onEntry, context);
            return target;
        }
        return state;
    }

    private static String selectAnimation(State state, String preferredAnimationName,
        OpenYsmControllerExpressionEvaluator.Context context) {
        if (state.animations.isEmpty()) {
            return null;
        }
        if (StringUtils.isNotBlank(preferredAnimationName)) {
            for (AnimationEntry entry : state.animations) {
                if (preferredAnimationName.equals(entry.animationName)) {
                    return animationEntryActive(entry, context) ? entry.animationName : null;
                }
            }
        }
        for (AnimationEntry entry : state.animations) {
            if (animationEntryActive(entry, context)) {
                return entry.animationName;
            }
        }
        return null;
    }

    private static boolean animationEntryActive(AnimationEntry entry,
        OpenYsmControllerExpressionEvaluator.Context context) {
        return StringUtils.isBlank(entry.condition)
            || OpenYsmControllerExpressionEvaluator.evaluateBoolean(entry.condition, context);
    }

    /**
     * B-12: a Bedrock state lists several animations and plays every entry whose own condition holds, but a controller
     * here plays one clip at a time - the engine's queue is a sequential playlist, not a set of layers - so the other
     * matching entries are simply lost.
     * <p>
     * This does not implement the capability; it makes the loss visible. Reported once per (model, controller, state)
     * so a log answers the question the audit could not: whether any pack this client actually loads relies on it.
     * "The pack's second animation never plays" is exactly the kind of silence the rest of this audit keeps finding.
     */
    private static void reportDroppedEntries(ResourceLocation animationId, String geckoControllerName, State state,
        String selectedAnimationName, OpenYsmControllerExpressionEvaluator.Context context) {
        if (StringUtils.isBlank(selectedAnimationName) || state.animations.size() < 2) {
            return;
        }
        StringBuilder dropped = null;
        for (AnimationEntry entry : state.animations) {
            if (entry.animationName.equals(selectedAnimationName) || !animationEntryActive(entry, context)) {
                continue;
            }
            if (dropped == null) {
                dropped = new StringBuilder();
            } else {
                dropped.append(", ");
            }
            dropped.append(entry.animationName);
        }
        if (dropped == null) {
            return;
        }
        OpenYsmAnimationControllerRegistry.warnOnce(
            "multi-animation:" + animationId + ":" + geckoControllerName + ":" + state.name,
            "OpenYSM state '" + state.name
                + "' of controller "
                + geckoControllerName
                + " in "
                + animationId
                + " has more than one animation whose condition holds ("
                + dropped
                + "); this port plays one clip per controller, so only "
                + selectedAnimationName
                + " plays. Further occurrences are not logged.");
    }

    private static boolean animationExists(ResourceLocation animationId, String animationName) {
        // Installs the model's animations on first use; going straight to the cache would report a controller's
        // animation as missing for a model that simply has not been drawn yet.
        AnimationFile file = ClientModelManager.animationFileFor(animationId);
        if (file == null || !file.animations.containsKey(animationName)) {
            OpenYsmAnimationControllerRegistry.warnOnce(
                "missing-animation:" + animationId + ":" + animationName,
                "OpenYSM controller selected missing animation " + animationName + " for " + animationId);
            return false;
        }
        return true;
    }

    private static void applyAnimation(AnimationEvent<CustomPlayerEntity> event, RuntimeState runtimeState, State state,
        String animationName) {
        AnimationBuilder builder = new AnimationBuilder().addAnimation(animationName);
        boolean sameState = state.name.equals(runtimeState.lastSelectedAnimationState);
        boolean changedInSameState = sameState && StringUtils.isNotBlank(runtimeState.lastSelectedAnimation)
            && !runtimeState.lastSelectedAnimation.equals(animationName);
        runtimeState.lastAnimation = animationName;
        runtimeState.lastSelectedAnimationState = state.name;
        runtimeState.lastSelectedAnimation = animationName;
        if (changedInSameState) {
            double elapsedTick = Math.max(0.0D, event.getAnimationTick() - runtimeState.enteredTick);
            if (event.getController()
                .setAnimationPreservingTick(builder, event.getAnimationTick(), elapsedTick)) {
                return;
            }
        }
        event.getController()
            .setAnimation(builder);
    }

    private static void prepareFrameVariables(String geckoControllerName, EntityPlayer player, RuntimeState state,
        OpenYsmControllerExpressionEvaluator.Context context) {
        if (isPostSwingController(geckoControllerName)) {
            boolean newSwing = player.isSwingInProgress
                && (!state.lastSwingActive || player.swingProgressInt < state.lastSwingProgress);
            if (newSwing) {
                boolean swordSwing = OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                    "ctrl.swing('mainhand', ':sword')||ctrl.swing('offhand', ':sword')",
                    context);
                if (swordSwing) {
                    // A-03: written through the shared variable entry so the value lands in the same store the
                    // animation file and its timeline instructions use (see RuntimeState#setVariable).
                    state.setVariable("swing_sword", 1.0d);
                    state.setVariable(
                        "jump",
                        OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                            "q.is_jumping&&(q.vertical_speed<0)",
                            context) ? 1.0d : 0.0d);
                }
            }
            state.lastSwingActive = player.isSwingInProgress;
            state.lastSwingProgress = player.isSwingInProgress ? player.swingProgressInt : -1;
        }
    }

    private static boolean isPostSwingController(String geckoControllerName) {
        return OPENYSM_POST_SWING_CONTROLLER.equals(geckoControllerName)
            || SWING_CONTROLLER.equals(geckoControllerName);
    }

    private static List<ControllerMatch> resolveControllers(ControllerSet set, String geckoControllerName) {
        List<ControllerMatch> matches = new ArrayList<>();
        String preferredAnimationName = parallelAnimationName(geckoControllerName);
        for (String candidate : candidateControllerNames(geckoControllerName)) {
            addMatch(matches, set, candidate, preferredAnimationName);
        }
        return matches;
    }

    /**
     * Every name a pack may have declared this GeckoLib controller under, in the order they are trusted.
     * <p>
     * The port names its controllers after its own Java wiring while packs are written against upstream's
     * names, so one controller usually has several spellings and they belong in one reviewable table. The
     * per-slot armor aliases are the ones a pack actually writes - upstream keys them {@code player.armor_<slot>}
     * (client/controller/ArmorControllerDiscovery.java:35) and the shipped wine_fox/14_momo pack declares
     * {@code player.armor_head} (controller/armor.animation_controllers.json:4) - whereas this port names its
     * controller {@code <slot>_controller} (CustomPlayerEntity#registerControllers). Without those two lines a
     * pack's whole per-slot armor state machine could never be matched and only the Java
     * {@code <slot>:$id} / {@code <slot>:default} path would ever play.
     * <p>
     * Order is priority: a name both sides agree on is tried before an alias.
     */
    static List<String> candidateControllerNames(String geckoControllerName) {
        List<String> names = new ArrayList<>();
        if (geckoControllerName == null || geckoControllerName.isEmpty()) {
            return names;
        }
        if (getParallelIndex(geckoControllerName) >= 0) {
            // B-02: upstream pairs a parallel controller with its own clip by SUFFIX. A model that declares
            // `player.parallel_<i>` gets that definition as the state machine, and a model that declares none gets
            // the coded `parallel<i>` (PlayerControllerCollection.java:68-69 only builds ParallelPredicate(anim)
            // as the fallback, and ParallelControllerDiscovery.java:52-70 derives the controller name from the
            // animation `parallel<i>` itself). The grouped form - one `player.parallel_0` whose `animations` list
            // holds all eight - is therefore a fallback for the controller-0 spelling only, not the definition
            // every controller indexes into: 14_momo's list is `[parallel0, parallel2, parallel3, parallel1, ...]`,
            // an order that carries no meaning precisely because upstream plays every entry of a state at once.
            int parallelIndex = getParallelIndex(geckoControllerName);
            boolean preParallel = geckoControllerName.startsWith("pre_parallel_");
            String base = preParallel ? "pre_parallel_" : "parallel_";
            names.add("player." + base + parallelIndex);
            names.add(base + parallelIndex);
            names.add("player." + base + "0");
            names.add(base + "0");
        } else if (MAIN_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.main");
            names.add("player.base");
            names.add("player.move");
            names.add("main");
        } else if (HOLD_MAINHAND_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.hold_mainhand");
            names.add("hold_mainhand");
        } else if (HOLD_OFFHAND_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.hold_offhand");
            names.add("hold_offhand");
        } else if (SWING_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.swing");
            names.add("swing");
        } else if (USE_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.use");
            names.add("use");
        } else if (CAP_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.cap");
            names.add("cap");
        }
        names.add(geckoControllerName);
        if (geckoControllerName.startsWith("player.")) {
            names.add(geckoControllerName.substring("player.".length()));
        }
        if (geckoControllerName.endsWith("_controller")) {
            names.add(geckoControllerName.substring(0, geckoControllerName.length() - 11));
        }
        String armorSlot = armorSlotOf(geckoControllerName);
        if (armorSlot != null) {
            names.add("player.armor_" + armorSlot);
            names.add("armor_" + armorSlot);
        }
        return names;
    }

    /**
     * The armor slot a GeckoLib controller stands for - {@code head} / {@code chest} / {@code legs} /
     * {@code feet} - or {@code null} for every other controller. The slot vocabulary comes from the shared
     * table in {@link ConditionArmor} rather than a second copy of it.
     */
    static String armorSlotOf(String geckoControllerName) {
        if (geckoControllerName == null) {
            return null;
        }
        for (int slotIndex = 1; slotIndex <= 4; slotIndex++) {
            String slotName = ConditionArmor.getSlotNameFromIndex(slotIndex);
            if (!slotName.isEmpty() && geckoControllerName.equals(slotName + "_controller")) {
                return slotName;
            }
        }
        return null;
    }

    private static void addMatch(List<ControllerMatch> matches, ControllerSet set, String controllerName,
        String preferredAnimationName) {
        Controller controller = set.controllers.get(controllerName);
        if (controller == null) {
            return;
        }
        for (ControllerMatch match : matches) {
            if (match.controller == controller) {
                return;
            }
        }
        matches.add(new ControllerMatch(controller, preferredAnimationName));
    }

    /**
     * The clip a parallel controller stands for, by upstream's suffix rule: controller
     * {@code parallel_<i>_controller} plays {@code parallel<i>}, and {@code pre_parallel_<i>_controller} plays
     * {@code pre_parallel<i>} ({@code PlayerControllerCollection.java:31-32,68-69} plus
     * {@code ParallelControllerDiscovery.java:52-70}, which derives the controller name from the animation name).
     * <p>
     * Selecting by name rather than by position is what makes a controller independent of the order of a grouped
     * {@code animations} list - and that order demonstrably carries no meaning, because upstream plays every entry
     * of a state simultaneously ({@code BedrockAnimationController#process} builds one player per entry).
     *
     * @return the clip name, or {@code null} for a controller that is not a parallel one
     */
    static String parallelAnimationName(String geckoControllerName) {
        int parallelIndex = getParallelIndex(geckoControllerName);
        if (parallelIndex < 0) {
            return null;
        }
        return (geckoControllerName.startsWith("pre_parallel_") ? "pre_parallel" : "parallel") + parallelIndex;
    }

    private static int getParallelIndex(String geckoControllerName) {
        if (geckoControllerName == null || !geckoControllerName.endsWith("_controller")) {
            return -1;
        }
        String name = geckoControllerName.substring(0, geckoControllerName.length() - 11);
        String prefix = null;
        if (name.startsWith("pre_parallel_")) {
            prefix = "pre_parallel_";
        } else if (name.startsWith("parallel_")) {
            prefix = "parallel_";
        }
        if (prefix == null) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The prefix the engine registers scoped variables under (`ScopedMolangVariable#getName`). */
    private static final String VARIABLE_PREFIX = "v.";

    static final class RuntimeState {
        String currentState = "";
        String lastAnimation = "";
        String lastSelectedAnimationState = "";
        String lastSelectedAnimation = "";
        double enteredTick;
        boolean lastSwingActive;
        int lastSwingProgress = -1;
        /**
         * Fallback store used only while no engine frame scope is open. Normally {@code v.*} reads and writes go to
         * the engine's per-frame scope ({@link MolangPhysicsRuntime}), which is the same store the animation file's
         * MoLang and its timeline instructions use - that is the A-03 fix: one store for a name, shared between the
         * controllers and the animation, instead of one map per (player, model, controller).
         */
        final Map<String, Double> variables = new ConcurrentHashMap<>();

        /**
         * Writes {@code v.<name>} (name without the {@code v.} prefix). Prefers the engine scope; falls back to the
         * local map when no scope is open, so a controller that writes in one evaluation can still read it in the
         * next.
         */
        void setVariable(String name, double value) {
            if (!MolangPhysicsRuntime.setVariable(VARIABLE_PREFIX + name, value)) {
                variables.put(name, value);
            }
        }

        /**
         * Reads {@code v.<name>} (name without the {@code v.} prefix). The engine scope wins over the local
         * fallback, and when neither has the name {@code fallback} is returned.
         */
        double getVariable(String name, double fallback) {
            Double local = variables.get(name);
            return MolangPhysicsRuntime.getVariable(VARIABLE_PREFIX + name, local == null ? fallback : local);
        }
    }

    private static final class ControllerMatch {
        private final Controller controller;
        /**
         * The clip this controller is expected to play, or {@code null} when the definition's own state picks it.
         * Only parallel controllers set it (see {@link #parallelAnimationName}).
         */
        private final String preferredAnimationName;

        private ControllerMatch(Controller controller, String preferredAnimationName) {
            this.controller = controller;
            this.preferredAnimationName = preferredAnimationName;
        }
    }

    private static final class StateKey {
        private final UUID playerId;
        private final ResourceLocation animationId;
        private final String geckoControllerName;
        private final String openYsmControllerName;

        private StateKey(UUID playerId, ResourceLocation animationId, String geckoControllerName,
            String openYsmControllerName) {
            this.playerId = playerId;
            this.animationId = animationId;
            this.geckoControllerName = geckoControllerName;
            this.openYsmControllerName = openYsmControllerName;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof StateKey)) {
                return false;
            }
            StateKey other = (StateKey) obj;
            return playerId.equals(other.playerId) && animationId.equals(other.animationId)
                && geckoControllerName.equals(other.geckoControllerName)
                && openYsmControllerName.equals(other.openYsmControllerName);
        }

        @Override
        public int hashCode() {
            int result = playerId.hashCode();
            result = 31 * result + animationId.hashCode();
            result = 31 * result + geckoControllerName.hashCode();
            result = 31 * result + openYsmControllerName.hashCode();
            return result;
        }
    }
}
