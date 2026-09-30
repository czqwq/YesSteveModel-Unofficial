package com.fox.ysmu.client.animation.molang;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * The {@code ctrl.*} script API an OpenYSM pack script may use, and the mapping from its return value to a play state.
 * <p>
 * A pack binds a script to one controller by file name - {@code functions/<anything>@player_ctrl_<name>.molang} - and
 * upstream then evaluates it <b>instead of</b> that controller's built-in predicate
 * ({@code geckolib3/core/controller/CodedAnimationController.java:124-134} looks the handler up by
 * {@code name.replace(".", "_ctrl_")}). Its return value is a state code and its side effects are what actually picks
 * the animation, so both halves are needed: a script that runs without {@code ctrl.set_animation} would leave its
 * controller with nothing to play, which is worse than not binding it at all.
 * <p>
 * Everything below is upstream's, with the sources named at each entry: the codes from
 * {@code client/animation/molang/CtrlBinding.java:35-42}, {@code set_animation} from
 * {@code .../functions/SetAnimation.java}, {@code set_beginning_transition_length} from
 * {@code .../functions/SetBeginningTransitionLength.java}, {@code reset}/{@code indicate_reload} from their own files,
 * and the {@code ctrl.<state>} query from {@code CtrlBinding#testCondition} - "is the main controller currently
 * playing this state".
 */
public final class CtrlScriptBinding {

    /** State codes returned by a script; upstream {@code CtrlBinding.java:35-38}. */
    public static final int STATE_CONTINUE = 2;
    public static final int STATE_STOP = 3;
    public static final int STATE_PAUSE = 4;
    /** "No opinion this frame, use the built-in predicate"; upstream {@code CtrlBinding.java:38}. */
    public static final int STATE_BYPASS = 5;

    /** Loop-type codes a script may pass as {@code set_animation}'s second argument; {@code CtrlBinding.java:40-42}. */
    public static final int LOOP = 10;
    public static final int PLAY_ONCE = 11;
    public static final int HOLD_ON_LAST_FRAME = 12;

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private CtrlScriptBinding() {}

    /**
     * Registers the codes, the side-effect functions and one {@code ctrl.<state>} query per registered state name.
     * <p>
     * Called once, after the state table exists, because the queries are keyed by that table's names - the same
     * vocabulary a pack writes. A name the table does not have stays unregistered, and an unknown {@code ctrl.x} in a
     * script then reports itself instead of silently reading zero.
     */
    public static void register(MolangParser parser, Iterable<String> stateNames) {
        parser.setValue("ctrl.state_continue", () -> (double) STATE_CONTINUE);
        parser.setValue("ctrl.state_stop", () -> (double) STATE_STOP);
        parser.setValue("ctrl.state_pause", () -> (double) STATE_PAUSE);
        parser.setValue("ctrl.state_bypass", () -> (double) STATE_BYPASS);
        parser.setValue("ctrl.loop", () -> (double) LOOP);
        parser.setValue("ctrl.play_once", () -> (double) PLAY_ONCE);
        parser.setValue("ctrl.hold_on_last_frame", () -> (double) HOLD_ON_LAST_FRAME);

        parser.functions.put("ctrl.set_animation", SetAnimationFunction.class);
        parser.functions.put("ctrl.set_beginning_transition_length", SetBeginningTransitionLengthFunction.class);
        parser.functions.put("ctrl.reset", ResetFunction.class);
        parser.functions.put("ctrl.indicate_reload", IndicateReloadFunction.class);

        for (String stateName : stateNames) {
            if (stateName == null || stateName.isEmpty()) {
                continue;
            }
            parser.setValue("ctrl." + stateName, () -> isMainState(stateName) ? 1.0D : 0.0D);
        }
    }

    /**
     * Runs one controller's script and turns its return value into a play state.
     *
     * @param mainState the main controller's currently selected state name, for the {@code ctrl.<state>} queries
     * @return the play state, or {@code null} when the script has no opinion ({@link #STATE_BYPASS} and anything
     *         unmapped), which is the caller's signal to fall back to the built-in predicate
     */
    @Nullable
    public static PlayState evaluate(PackFunctionScript script, MolangParser parser, EntityPlayer player,
        AnimationController<?> controller, @Nullable String mainState) {
        CURRENT.set(new Frame(player, controller, mainState == null ? "" : mainState));
        try {
            int code = (int) script.evaluate(parser);
            if (code == STATE_CONTINUE) {
                return PlayState.CONTINUE;
            }
            if (code == STATE_STOP) {
                return PlayState.STOP;
            }
            if (code == STATE_PAUSE) {
                return PlayState.PAUSE;
            }
            return null;
        } finally {
            CURRENT.remove();
        }
    }

    private static boolean isMainState(String stateName) {
        Frame frame = CURRENT.get();
        return frame != null && stateName.equals(frame.mainState);
    }

    /** Reads a string argument back out of {@link MolangStringPool}; a missing one falls back, so a script cannot throw. */
    static String stringArgument(IValue[] args, int index, String fallback) {
        if (args == null || index < 0 || index >= args.length) {
            return fallback;
        }
        String value = MolangStringPool.get((int) args[index].get());
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** The controller and player a script is currently running for. */
    private static final class Frame {

        private final EntityPlayer player;
        private final AnimationController<?> controller;
        private final String mainState;

        private Frame(EntityPlayer player, AnimationController<?> controller, String mainState) {
            this.player = player;
            this.controller = controller;
            this.mainState = mainState;
        }
    }

    /** {@code ctrl.set_animation('<name>'[, loopType])}; upstream {@code functions/SetAnimation.java}. */
    public static final class SetAnimationFunction extends Function {

        public SetAnimationFunction(IValue[] values, String name) throws Exception {
            super(values, name);
        }

        @Override
        public double get() {
            Frame frame = CURRENT.get();
            if (frame == null || frame.controller == null) {
                return 0.0D;
            }
            String animation = stringArgument(this.args, 0, "");
            if (animation.isEmpty()) {
                return 0.0D;
            }
            AnimationBuilder builder = new AnimationBuilder();
            ILoopType loopType = loopTypeOf(this.args);
            if (loopType == null) {
                builder.addAnimation(animation);
            } else {
                builder.addAnimation(animation, loopType);
            }
            frame.controller.setAnimation(builder);
            return 0.0D;
        }

        @Nullable
        private static ILoopType loopTypeOf(IValue[] args) {
            if (args == null || args.length < 2) {
                return null;
            }
            int code = (int) args[1].get();
            if (code == LOOP) {
                return ILoopType.EDefaultLoopTypes.LOOP;
            }
            if (code == PLAY_ONCE) {
                return ILoopType.EDefaultLoopTypes.PLAY_ONCE;
            }
            if (code == HOLD_ON_LAST_FRAME) {
                return ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
            }
            return null;
        }
    }

    /** {@code ctrl.set_beginning_transition_length(seconds)}; upstream {@code functions/SetBeginningTransitionLength.java}. */
    public static final class SetBeginningTransitionLengthFunction extends Function {

        public SetBeginningTransitionLengthFunction(IValue[] values, String name) throws Exception {
            super(values, name);
        }

        @Override
        public double get() {
            Frame frame = CURRENT.get();
            if (frame == null || frame.controller == null || this.args == null || this.args.length < 1) {
                return 0.0D;
            }
            double seconds = this.args[0].get();
            if (seconds < 0.0D) {
                return 0.0D;
            }
            // The controller counts in ticks; a `blend_transition` in an animation_controllers file is converted the
            // same way when it overrides this (OpenYsmPlayerControllerRuntime#tryApplyController).
            frame.controller.transitionLengthTicks = seconds * 20.0D;
            return 0.0D;
        }
    }

    /** {@code ctrl.reset()}; upstream {@code functions/ResetController.java} clears the coded controller's clip. */
    public static final class ResetFunction extends Function {

        public ResetFunction(IValue[] values, String name) throws Exception {
            super(values, name);
        }

        @Override
        public double get() {
            Frame frame = CURRENT.get();
            if (frame == null || frame.controller == null) {
                return 0.0D;
            }
            frame.controller.clearAnimationCache();
            frame.controller.markNeedsReload();
            return 0.0D;
        }
    }

    /** {@code ctrl.indicate_reload()}; upstream {@code functions/IndicateReload.java}. */
    public static final class IndicateReloadFunction extends Function {

        public IndicateReloadFunction(IValue[] values, String name) throws Exception {
            super(values, name);
        }

        @Override
        public double get() {
            Frame frame = CURRENT.get();
            if (frame != null && frame.controller != null) {
                frame.controller.markNeedsReload();
            }
            return 0.0D;
        }
    }
}
