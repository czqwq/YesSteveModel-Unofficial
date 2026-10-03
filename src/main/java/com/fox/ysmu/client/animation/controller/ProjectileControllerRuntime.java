package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Transition;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The controller state machines of a projectile sub-entity (an arrow, a trident, a thrown item).
 *
 * <p>A projectile is not an {@code IAnimatable} and never goes through the engine's controllers, so this is a
 * deliberately separate, much smaller runtime: it evaluates the same OpenYSM controller definitions
 * ({@code OpenYsmControllerDefinitions}, parsed from the {@code files.projectiles} entries of a pack's
 * {@code ysm.json}) with no {@code EntityPlayer} and no {@code AnimationEvent} in sight. The {@code ysm.*} values the
 * expressions read are published into the shared {@link MolangParser#VARIABLES} map by the renderer once per frame,
 * before {@link #getAnimationEntries} is called.</p>
 *
 * <p>State is keyed per entity, so two arrows in flight run their own machines; a projectile leaving the world is
 * forgotten through {@link #cleanupEntity}.</p>
 */
public final class ProjectileControllerRuntime {

    /** Per-controller runtime state. */
    private static final class RuntimeState {

        String currentState = "";
        double enteredTick;
    }

    /** Per-(entity, animation, controller) state key. */
    private static final class StateKey {

        final int entityId;
        final ResourceLocation animId;
        final String controllerName;

        StateKey(int entityId, ResourceLocation animId, String controllerName) {
            this.entityId = entityId;
            this.animId = animId;
            this.controllerName = controllerName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StateKey)) {
                return false;
            }
            StateKey that = (StateKey) other;
            return this.entityId == that.entityId && this.animId.equals(that.animId)
                && this.controllerName.equals(that.controllerName);
        }

        @Override
        public int hashCode() {
            int result = this.entityId;
            result = 31 * result + this.animId.hashCode();
            result = 31 * result + this.controllerName.hashCode();
            return result;
        }
    }

    private static final Map<StateKey, RuntimeState> STATES = new ConcurrentHashMap<>();

    /**
     * What the projectile entity is doing, which decides whether {@code air} / {@code ground} / {@code fire} /
     * {@code water} are selected.
     * <p>
     * YSM-wiki (animation/projectile): the four state animations plus {@code parallel0..7}. The four are not mutually
     * exclusive - an arrow can be both burning and in the ground - and their precedence comes from the order the
     * renderer applies them in, not from this class.
     */
    public static final class ProjectileState {

        /** The default: flying, not in the ground, not burning, not in water. */
        public static final ProjectileState AIRBORNE = new ProjectileState(true, false, false, false);

        final boolean inAir;
        final boolean inGround;
        final boolean onFire;
        final boolean inWater;

        public ProjectileState(boolean inAir, boolean inGround, boolean onFire, boolean inWater) {
            this.inAir = inAir;
            this.inGround = inGround;
            this.onFire = onFire;
            this.inWater = inWater;
        }

        /**
         * Derives the state from the entity. {@code inAir} only requires "not in the ground", because the water and
         * fire animations are layered over the flight animation rather than replacing it.
         */
        public static ProjectileState of(boolean inGround, boolean inWater, boolean onFire) {
            return new ProjectileState(!inGround, inGround, onFire, inWater);
        }
    }

    private ProjectileControllerRuntime() {}

    /**
     * One projectile animation that should play this frame, together with its <em>clock origin</em>: the animation is
     * sampled at {@code entity age - startTick}.
     *
     * <p>A controller state animation is sampled from the moment its state was entered, because YSM restarts a
     * state's animation on entry and a {@code hold_on_last_frame} clip only animates in its first few ticks - sampling
     * one of those against the entity's total age parks it on its final frame forever, so an arrow's landing "burst"
     * never plays. The non-controller animations ({@code air} / {@code ground} / {@code parallel*}) use origin 0,
     * i.e. the entity's age.</p>
     *
     * <p>This is an <b>origin</b>, not an elapsed duration: the renderer subtracts it from the age. Storing the
     * elapsed duration instead would make the renderer subtract twice and sample at the entry tick - which showed up
     * as sub-models invisible in flight and frozen mid-way for arrows that landed early.</p>
     */
    public static final class ActiveAnimation {

        public final String name;
        /** Clock origin in ticks: 0 = the entity's age, otherwise the entity age when the state was entered. */
        public final double startTick;

        ActiveAnimation(String name, double startTick) {
            this.name = name;
            this.startTick = startTick;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    /**
     * The animation names that should play for one projectile this frame; empty when the model declares no
     * controllers and no implicit animations.
     */
    public static List<String> getActiveAnimations(int entityId, ResourceLocation animId, double ageInTicks) {
        return getActiveAnimations(entityId, animId, ageInTicks, ProjectileState.AIRBORNE);
    }

    /** As above, but selecting the state animations from {@code state}. */
    public static List<String> getActiveAnimations(int entityId, ResourceLocation animId, double ageInTicks,
        ProjectileState state) {
        List<ActiveAnimation> entries = getActiveAnimationEntries(entityId, animId, ageInTicks, state);
        List<String> names = new ArrayList<>(entries.size());
        for (ActiveAnimation entry : entries) {
            names.add(entry.name);
        }
        return names;
    }

    /**
     * As {@link #getActiveAnimations(int, ResourceLocation, double, ProjectileState)}, but each entry also carries the
     * state-entry tick the renderer needs to sample its keyframes. The order is identical to
     * {@code getActiveAnimations}, and the machines are evaluated once either way.
     */
    public static List<ActiveAnimation> getActiveAnimationEntries(int entityId, ResourceLocation animId,
        double ageInTicks, ProjectileState state) {
        ControllerSet set = OpenYsmAnimationControllerRegistry.get(animId);
        if (set == null || set.controllers.isEmpty()) {
            if (Config.DEBUG_CONTROLLER) {
                ysmu.LOG.info(
                    "[YSMU-PROJ-CTRL] no controllers for {}, entityId={}",
                    animId,
                    entityId);
            }
            return selectImplicitAnimations(animId, state, new ArrayList<>(), Collections.emptySet());
        }

        if (Config.DEBUG_CONTROLLER) {
            ysmu.LOG.info(
                "[YSMU-PROJ-CTRL] entityId={}, animId={}, controllers={}, names={}",
                entityId,
                animId,
                set.controllers.size(),
                set.controllers.keySet());
        }

        // Every animation name any controller state can select. What is left over is a candidate for the implicit
        // state/parallel set below; it is also what keeps a controller-managed animation from being played twice.
        Set<String> managedAnims = new HashSet<>();
        for (Controller controller : set.controllers.values()) {
            for (State controllerState : controller.states.values()) {
                for (AnimationEntry entry : controllerState.animations) {
                    managedAnims.add(entry.animationName);
                }
            }
        }

        List<ActiveAnimation> result = new ArrayList<>();
        for (Controller controller : set.controllers.values()) {
            List<ActiveAnimation> controllerAnims = evaluateController(entityId, animId, controller, ageInTicks);
            if (Config.DEBUG_CONTROLLER) {
                ysmu.LOG.info(
                    "[YSMU-PROJ-CTRL] controller '{}': state='{}', anims={}",
                    controller.name,
                    currentStateName(entityId, animId, controller.name),
                    controllerAnims);
            }
            result.addAll(controllerAnims);
        }

        return selectImplicitAnimations(animId, state, result, managedAnims);
    }

    /**
     * Appends the animations this runtime selects by itself - the four state animations and the eight parallel slots -
     * and returns the ordered list. Later entries overwrite earlier ones bone by bone, so this order <em>is</em> the
     * priority: {@code air} and {@code ground} are low (other animations may cover them), {@code fire} and
     * {@code water} are higher, and the parallel family is highest.
     *
     * <p>Filtering by state here is the whole point. An earlier implementation played every animation no controller
     * referenced, which meant all four state animations ran at once and a model's per-state sub-models (bow, crossbow,
     * the burst, the stuck-in-ground variant) were drawn stacked on one projectile. Models with no controllers at all
     * - only state and parallel animations - were the worst affected.</p>
     */
    private static List<ActiveAnimation> selectImplicitAnimations(ResourceLocation animId, ProjectileState state,
        List<ActiveAnimation> result, Set<String> managedAnims) {
        AnimationFile animFile = GeckoLibCache.getInstance()
            .getAnimations()
            .get(animId);
        if (animFile == null || animFile.animations == null) {
            return result;
        }

        // Low priority: flight, then ground. High priority: fire, then water. Later wins on a shared bone.
        addIfPresent(result, animFile, state.inAir, "air");
        addIfPresent(result, animFile, state.inGround, "ground");
        addIfPresent(result, animFile, state.onFire, "fire");
        addIfPresent(result, animFile, state.inWater, "water");

        // The parallel family always plays, and a higher number wins.
        for (int i = 0; i < MAX_PARALLEL_SLOT; i++) {
            addIfPresent(result, animFile, true, "parallel" + i);
        }

        // Anything else is neither a state animation, a parallel slot, nor controller-managed, so it is not on the
        // projectile's animation list. The old "play everything" behaviour is exactly where the stacking came from.
        // With DebugController on they are listed, so a model that really depends on a non-standard spelling shows up.
        if (Config.DEBUG_CONTROLLER) {
            for (String name : animFile.animations.keySet()) {
                if (containsName(result, name) || managedAnims.contains(name) || isKnownImplicitAnimation(name)) {
                    continue;
                }
                ysmu.LOG.info("[YSMU-PROJ-CTRL] ignored non-standard unmanaged animation '{}' for {}", name, animId);
            }
        }
        return result;
    }

    private static boolean containsName(List<ActiveAnimation> entries, String name) {
        for (ActiveAnimation entry : entries) {
            if (entry.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** The number of {@code parallel0..7} slots (YSM-wiki: a projectile has a fixed eight). */
    private static final int MAX_PARALLEL_SLOT = 8;

    private static boolean isKnownImplicitAnimation(String name) {
        if (name == null) {
            return false;
        }
        if ("air".equals(name) || "ground".equals(name) || "fire".equals(name) || "water".equals(name)) {
            return true;
        }
        if (!name.startsWith("parallel")) {
            return false;
        }
        String digits = name.substring("parallel".length());
        return digits.length() == 1 && digits.charAt(0) >= '0' && digits.charAt(0) < '0' + MAX_PARALLEL_SLOT;
    }

    private static void addIfPresent(List<ActiveAnimation> result, AnimationFile animFile, boolean condition,
        String animationName) {
        if (!condition || containsName(result, animationName)) {
            return;
        }
        if (animFile.animations.containsKey(animationName)) {
            // Not a controller animation, so it is timed from the entity's birth.
            result.add(new ActiveAnimation(animationName, 0.0d));
        }
    }

    private static String currentStateName(int entityId, ResourceLocation animId, String controllerName) {
        RuntimeState state = STATES.get(new StateKey(entityId, animId, controllerName));
        return state != null ? state.currentState : "(no state)";
    }

    /**
     * Runs one controller's machine for this frame and returns the animations its current state selects, each with
     * that state's entry tick as the clock origin.
     */
    private static List<ActiveAnimation> evaluateController(int entityId, ResourceLocation animId,
        Controller controller, double ageInTicks) {
        RuntimeState state = getOrCreateState(entityId, animId, controller.name);

        State current = controller.states.get(state.currentState);
        if (current == null) {
            state.currentState = "";
            state.enteredTick = ageInTicks;
        }

        if (StringUtils.isBlank(state.currentState)) {
            State initial = controller.getInitialState();
            if (initial == null) {
                return Collections.emptyList();
            }
            state.currentState = initial.name;
            state.enteredTick = ageInTicks;
            current = initial;
        }

        // Chained transitions are allowed a few steps per frame, exactly as the player runtime does.
        for (int i = 0; i < 4; i++) {
            State next = applyTransition(controller, current, state, ageInTicks);
            if (next == current) {
                break;
            }
            current = next;
        }

        // The state's animations are timed from the moment the state was entered; see ActiveAnimation.
        double startTick = Math.max(0.0d, state.enteredTick);

        List<ActiveAnimation> animations = new ArrayList<>();
        if (current != null) {
            for (AnimationEntry entry : current.animations) {
                // Projectile controller states rarely use per-entry conditions, but the format allows them.
                if (StringUtils.isBlank(entry.condition) || evaluateExpression(entry.condition)) {
                    if (animationExists(animId, entry.animationName)) {
                        animations.add(new ActiveAnimation(entry.animationName, startTick));
                    }
                }
            }
        }
        return animations;
    }

    /**
     * Fires the first transition whose condition holds, or returns the current state.
     */
    private static State applyTransition(Controller controller, State current, RuntimeState state,
        double ageInTicks) {
        for (Transition transition : current.transitions) {
            State target = controller.states.get(transition.targetState);
            if (target == null) {
                continue;
            }
            if (!evaluateExpression(transition.condition)) {
                continue;
            }
            state.currentState = target.name;
            state.enteredTick = ageInTicks;
            return target;
        }
        return current;
    }

    private static boolean evaluateExpression(String expression) {
        if (StringUtils.isBlank(expression)) {
            return true;
        }
        double result = eval(expression);
        boolean truthy = Math.abs(result) > 0.000001d;
        if (Config.DEBUG_CONTROLLER) {
            ysmu.LOG.info("[YSMU-PROJ-CTRL] '{}' = {} ({})", expression, result, truthy);
        }
        return truthy;
    }

    private static double eval(String expression) {
        if (StringUtils.isBlank(expression)) {
            return 1.0d;
        }
        int[] index = { 0 };
        Double result = parseOr(expression, index);
        return result != null ? result : 0.0d;
    }

    // ── recursive descent over the expression subset projectile controllers use ──

    private static Double parseOr(String expression, int[] index) {
        Double left = parseAnd(expression, index);
        if (left == null) {
            return null;
        }
        while (true) {
            skipSpace(expression, index);
            if (!match(expression, index, "||")) {
                return left;
            }
            Double right = parseAnd(expression, index);
            if (right == null) {
                return null;
            }
            left = (truthy(left) || truthy(right)) ? 1.0d : 0.0d;
        }
    }

    private static Double parseAnd(String expression, int[] index) {
        Double left = parseEquality(expression, index);
        if (left == null) {
            return null;
        }
        while (true) {
            skipSpace(expression, index);
            if (!match(expression, index, "&&")) {
                return left;
            }
            Double right = parseEquality(expression, index);
            if (right == null) {
                return null;
            }
            left = (truthy(left) && truthy(right)) ? 1.0d : 0.0d;
        }
    }

    private static Double parseEquality(String expression, int[] index) {
        Double left = parseComparison(expression, index);
        if (left == null) {
            return null;
        }
        while (true) {
            skipSpace(expression, index);
            if (match(expression, index, "==")) {
                Double right = parseComparison(expression, index);
                if (right == null) {
                    return null;
                }
                left = nearlyEqual(left, right) ? 1.0d : 0.0d;
            } else if (match(expression, index, "!=")) {
                Double right = parseComparison(expression, index);
                if (right == null) {
                    return null;
                }
                left = nearlyEqual(left, right) ? 0.0d : 1.0d;
            } else {
                return left;
            }
        }
    }

    private static Double parseComparison(String expression, int[] index) {
        Double left = parseAdditive(expression, index);
        if (left == null) {
            return null;
        }
        while (true) {
            skipSpace(expression, index);
            if (match(expression, index, ">=")) {
                Double right = parseAdditive(expression, index);
                if (right == null) {
                    return null;
                }
                left = left >= right ? 1.0d : 0.0d;
            } else if (match(expression, index, "<=")) {
                Double right = parseAdditive(expression, index);
                if (right == null) {
                    return null;
                }
                left = left <= right ? 1.0d : 0.0d;
            } else if (match(expression, index, ">")) {
                Double right = parseAdditive(expression, index);
                if (right == null) {
                    return null;
                }
                left = left > right ? 1.0d : 0.0d;
            } else if (match(expression, index, "<")) {
                Double right = parseAdditive(expression, index);
                if (right == null) {
                    return null;
                }
                left = left < right ? 1.0d : 0.0d;
            } else {
                return left;
            }
        }
    }

    private static Double parseAdditive(String expression, int[] index) {
        Double left = parseMultiplicative(expression, index);
        if (left == null) {
            return null;
        }
        while (true) {
            skipSpace(expression, index);
            if (match(expression, index, "+")) {
                Double right = parseMultiplicative(expression, index);
                if (right == null) {
                    return null;
                }
                left = left + right;
            } else if (match(expression, index, "-")) {
                Double right = parseMultiplicative(expression, index);
                if (right == null) {
                    return null;
                }
                left = left - right;
            } else {
                return left;
            }
        }
    }

    private static Double parseMultiplicative(String expression, int[] index) {
        Double left = parseUnary(expression, index);
        if (left == null) {
            return null;
        }
        while (true) {
            skipSpace(expression, index);
            if (match(expression, index, "*")) {
                Double right = parseUnary(expression, index);
                if (right == null) {
                    return null;
                }
                left = left * right;
            } else if (match(expression, index, "/")) {
                Double right = parseUnary(expression, index);
                if (right == null) {
                    return null;
                }
                left = right == 0.0d ? 0.0d : left / right;
            } else if (match(expression, index, "%")) {
                Double right = parseUnary(expression, index);
                if (right == null) {
                    return null;
                }
                left = right == 0.0d ? 0.0d : left % right;
            } else {
                return left;
            }
        }
    }

    private static Double parseUnary(String expression, int[] index) {
        skipSpace(expression, index);
        if (match(expression, index, "!")) {
            Double operand = parseUnary(expression, index);
            return operand != null ? (truthy(operand) ? 0.0d : 1.0d) : null;
        }
        if (match(expression, index, "-")) {
            Double operand = parseUnary(expression, index);
            return operand != null ? -operand : null;
        }
        return parsePrimary(expression, index);
    }

    private static Double parsePrimary(String expression, int[] index) {
        skipSpace(expression, index);
        if (index[0] >= expression.length()) {
            return 0.0d;
        }
        char current = expression.charAt(index[0]);

        if (current == '(') {
            index[0]++;
            Double inner = parseOr(expression, index);
            match(expression, index, ")");
            return inner;
        }
        // A quoted string has no numeric value; treat it as false rather than as an error.
        if (current == '\'' || current == '"') {
            skipQuotedString(expression, index);
            return 0.0d;
        }
        if (Character.isDigit(current)
            || (current == '.' && index[0] + 1 < expression.length()
                && Character.isDigit(expression.charAt(index[0] + 1)))) {
            return parseNumber(expression, index);
        }

        String identifier = parseIdentifier(expression, index);
        if (identifier.isEmpty()) {
            index[0]++;
            return 0.0d;
        }
        skipSpace(expression, index);

        if ("true".equals(identifier)) {
            return 1.0d;
        }
        if ("false".equals(identifier)) {
            return 0.0d;
        }

        if (match(expression, index, "(")) {
            List<Double> arguments = new ArrayList<>();
            while (true) {
                skipSpace(expression, index);
                if (match(expression, index, ")")) {
                    break;
                }
                if (index[0] >= expression.length()) {
                    break;
                }
                arguments.add(eval(readRawArgument(expression, index)));
                skipSpace(expression, index);
                if (!match(expression, index, ",")) {
                    match(expression, index, ")");
                    break;
                }
            }
            return evaluateFunction(identifier, arguments);
        }
        return resolveVariable(identifier);
    }

    /**
     * Reads a {@code ysm.*} variable (or any other name) out of the shared parser table. The renderer publishes the
     * projectile's own values there before the machines run.
     */
    private static double resolveVariable(String name) {
        Map<String, LazyVariable> variables = MolangParser.VARIABLES;
        if (name.startsWith("ysm.")) {
            LazyVariable value = variables.get(name.substring(4));
            if (value == null) {
                value = variables.get(name);
            }
            return value != null ? value.get() : 0.0d;
        }
        // v.* state is not part of a projectile's namespace; a bare name is looked up as itself.
        LazyVariable value = variables.get(name);
        return value != null ? value.get() : 0.0d;
    }

    /**
     * The subset of {@code math.*} the controller conditions use. An unknown function answers 0 rather than raising,
     * because a condition that cannot be evaluated should leave the projectile in its current state instead of
     * breaking the frame.
     */
    private static double evaluateFunction(String name, List<Double> args) {
        int count = args.size();
        if ("math.abs".equals(name) && count >= 1) {
            return Math.abs(args.get(0));
        }
        if ("math.sqrt".equals(name) && count >= 1) {
            return args.get(0) < 0 ? 0.0d : Math.sqrt(args.get(0));
        }
        if ("math.floor".equals(name) && count >= 1) {
            return Math.floor(args.get(0));
        }
        if ("math.ceil".equals(name) && count >= 1) {
            return Math.ceil(args.get(0));
        }
        if ("math.round".equals(name) && count >= 1) {
            return Math.round(args.get(0));
        }
        if ("math.sin".equals(name) && count >= 1) {
            return Math.sin(Math.toRadians(args.get(0)));
        }
        if ("math.cos".equals(name) && count >= 1) {
            return Math.cos(Math.toRadians(args.get(0)));
        }
        if ("math.exp".equals(name) && count >= 1) {
            return Math.exp(args.get(0));
        }
        if ("math.ln".equals(name) && count >= 1) {
            return args.get(0) <= 0 ? 0.0d : Math.log(args.get(0));
        }
        if (("math.hermite_blend".equals(name) || "math.hermite".equals(name)) && count >= 1) {
            double value = args.get(0);
            return value * value * (3 - 2 * value);
        }
        if ("math.pow".equals(name) && count >= 2) {
            return Math.pow(args.get(0), args.get(1));
        }
        if ("math.max".equals(name) && count >= 2) {
            return Math.max(args.get(0), args.get(1));
        }
        if ("math.min".equals(name) && count >= 2) {
            return Math.min(args.get(0), args.get(1));
        }
        if ("math.mod".equals(name) && count >= 2) {
            double divisor = args.get(1);
            if (divisor == 0.0d) {
                return 0.0d;
            }
            double remainder = args.get(0) % divisor;
            return remainder < 0 ? remainder + Math.abs(divisor) : remainder;
        }
        if ("math.atan2".equals(name) && count >= 2) {
            return Math.toDegrees(Math.atan2(args.get(0), args.get(1)));
        }
        if ("math.atan".equals(name) && count >= 1) {
            return Math.toDegrees(Math.atan(args.get(0)));
        }
        if ("math.clamp".equals(name) && count >= 3) {
            return Math.max(args.get(1), Math.min(args.get(2), args.get(0)));
        }
        if ("math.lerp".equals(name) && count >= 3) {
            return args.get(0) + (args.get(1) - args.get(0)) * args.get(2);
        }
        if ("math.random".equals(name) && count >= 2) {
            double low = args.get(0);
            double high = args.get(1);
            return low >= high ? low : low + Math.random() * (high - low);
        }
        if ("math.min_angle".equals(name) && count >= 2) {
            double difference = (args.get(1) - args.get(0)) % 360;
            if (difference > 180) {
                difference -= 360;
            }
            if (difference <= -180) {
                difference += 360;
            }
            return difference;
        }
        if ("math.lerprotate".equals(name) && count >= 3) {
            double from = args.get(0);
            double to = args.get(1);
            double factor = args.get(2);
            double difference = (to - from) % 360;
            if (difference > 180) {
                difference -= 360;
            }
            if (difference <= -180) {
                difference += 360;
            }
            double result = from + difference * factor;
            if (result >= 360) {
                result -= 360;
            }
            if (result < 0) {
                result += 360;
            }
            return result;
        }
        boolean dieRoll = ("math.die_roll".equals(name) || "math.roll".equals(name)) && count >= 3;
        boolean dieRollInteger = ("math.die_roll_integer".equals(name) || "math.rolli".equals(name)) && count >= 3;
        if (dieRoll || dieRollInteger) {
            int rolls = Math.min((int) (double) args.get(0), 100);
            double low = args.get(1);
            double high = args.get(2);
            double sum = 0;
            for (int i = 0; i < rolls; i++) {
                if (dieRollInteger) {
                    int min = (int) low;
                    int max = (int) high;
                    if (min > max) {
                        int swap = min;
                        min = max;
                        max = swap;
                    }
                    sum += min + (int) (Math.random() * (max - min + 1));
                } else {
                    sum += low + Math.random() * (high - low);
                }
            }
            return sum;
        }
        if ("math.pi".equals(name) && count == 0) {
            return Math.PI;
        }
        if ("math.e".equals(name) && count == 0) {
            return Math.E;
        }
        return 0.0d;
    }

    private static Double parseNumber(String expression, int[] index) {
        int start = index[0];
        boolean dotSeen = false;
        while (index[0] < expression.length()) {
            char current = expression.charAt(index[0]);
            if (Character.isDigit(current)) {
                index[0]++;
            } else if (current == '.' && !dotSeen) {
                dotSeen = true;
                index[0]++;
            } else {
                break;
            }
        }
        return Double.parseDouble(expression.substring(start, index[0]));
    }

    private static String parseIdentifier(String expression, int[] index) {
        int start = index[0];
        while (index[0] < expression.length()) {
            char current = expression.charAt(index[0]);
            if (Character.isLetter(current) || Character.isDigit(current) || current == '_' || current == '.') {
                index[0]++;
            } else {
                break;
            }
        }
        return expression.substring(start, index[0]);
    }

    private static void skipQuotedString(String expression, int[] index) {
        if (index[0] >= expression.length()) {
            return;
        }
        char quote = expression.charAt(index[0]);
        index[0]++;
        while (index[0] < expression.length() && expression.charAt(index[0]) != quote) {
            index[0]++;
        }
        if (index[0] < expression.length()) {
            index[0]++;
        }
    }

    /** Reads one function argument as raw text, respecting nesting and quotes; the caller evaluates it. */
    private static String readRawArgument(String expression, int[] index) {
        int start = index[0];
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        while (index[0] < expression.length()) {
            char current = expression.charAt(index[0]);
            if (quoted) {
                if (current == quote) {
                    quoted = false;
                }
            } else if (current == '\'' || current == '"') {
                quoted = true;
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if ((current == ',' || current == ';') && depth == 0) {
                break;
            }
            index[0]++;
        }
        return expression.substring(start, index[0]);
    }

    private static void skipSpace(String expression, int[] index) {
        while (index[0] < expression.length() && expression.charAt(index[0]) <= ' ') {
            index[0]++;
        }
    }

    private static boolean match(String expression, int[] index, String expected) {
        if (expression.regionMatches(index[0], expected, 0, expected.length())) {
            index[0] += expected.length();
            return true;
        }
        return false;
    }

    private static boolean truthy(double value) {
        return Math.abs(value) > 0.000001d;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) < 0.000001d;
    }

    private static boolean animationExists(ResourceLocation animId, String animationName) {
        AnimationFile file = GeckoLibCache.getInstance()
            .getAnimations()
            .get(animId);
        return file != null && file.animations.containsKey(animationName);
    }

    private static RuntimeState getOrCreateState(int entityId, ResourceLocation animId, String controllerName) {
        return STATES.computeIfAbsent(new StateKey(entityId, animId, controllerName), key -> new RuntimeState());
    }

    /** Drops every machine, for a model cache clear or a reload. */
    public static void clear() {
        STATES.clear();
    }

    /** Drops one entity's machines, for a projectile that left the world. */
    public static void cleanupEntity(int entityId) {
        STATES.entrySet()
            .removeIf(entry -> entry.getKey().entityId == entityId);
    }

    /** Drops every machine registered for one animation id, for a model being re-registered. */
    public static void cleanupAnimation(ResourceLocation animId) {
        STATES.entrySet()
            .removeIf(entry -> entry.getKey().animId.equals(animId));
    }
}
