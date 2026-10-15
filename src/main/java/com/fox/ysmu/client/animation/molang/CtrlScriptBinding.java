package com.fox.ysmu.client.animation.molang;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import com.fox.ysmu.client.ClientModelManager;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.file.AnimationFile;

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
     * Runs one controller's script and turns its return value into a play state, checking a
     * {@code ctrl.set_animation} name against the animation file the controller will read.
     *
     * @param mainState   the main controller's currently selected state name, for the {@code ctrl.<state>} queries
     * @param animationId the animation file id this controller's animatable resolves to, i.e. exactly what
     *                    {@code CustomPlayerModel#getAnimationFileLocation} hands the engine
     *                    ({@code client/model/CustomPlayerModel.java:128-133} -> {@code CustomPlayerEntity#getAnimation}
     *                    {@code :244-252}); {@code null} skips the check
     * @return the play state, or {@code null} when the script has no opinion ({@link #STATE_BYPASS} and anything
     *         unmapped), which is the caller's signal to fall back to the built-in predicate
     */
    @Nullable
    public static PlayState evaluate(PackFunctionScript script, MolangParser parser, EntityPlayer player,
        AnimationController<?> controller, @Nullable String mainState, @Nullable ResourceLocation animationId) {
        CURRENT.set(new Frame(player, controller, mainState == null ? "" : mainState, animationId));
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

    /**
     * Whether the animation file the controller will read actually declares {@code animationName}.
     * <p>
     * The port's own existence check, and it reuses the lookup {@code OpenYsmPlayerControllerRuntime#animationExists}
     * already uses ({@code client/animation/controller/OpenYsmPlayerControllerRuntime.java:367-384}): install the
     * model's animations first, then {@code AnimationFile#getAnimation} - not {@code containsKey} - so the engine's
     * built-in fallbacks ({@code software/bernie/geckolib3/file/AnimationFile.java:28-37}) answer for the names they
     * serve.
     */
    public static boolean animationFileContains(@Nullable ResourceLocation animationId, @Nullable String animationName) {
        if (animationId == null || animationName == null || animationName.isEmpty()) {
            return false;
        }
        // Installs the model's animations on first use; going straight to the cache would report a clip as missing for
        // a model that simply has not been drawn yet - the same reason animationExists asks through this entry point.
        AnimationFile file = ClientModelManager.animationFileFor(animationId);
        return file != null && file.getAnimation(animationName) != null;
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
        /**
         * The animation file id this frame's controller resolves to. Resolved by the caller because a script literal
         * is only a name: the file it has to be looked up in lives on the animatable, which never reaches
         * {@link SetAnimationFunction#get()} - see the note there for why the id is carried on the frame instead of
         * being resolved from the controller.
         */
        @Nullable
        private final ResourceLocation animationId;

        private Frame(EntityPlayer player, AnimationController<?> controller, String mainState,
            @Nullable ResourceLocation animationId) {
            this.player = player;
            this.controller = controller;
            this.mainState = mainState;
            this.animationId = animationId;
        }
    }

    /**
     * {@code ctrl.set_animation('<name>'[, loopType])}; upstream {@code functions/SetAnimation.java}.
     * <p>
     * <b>Port-specific addition, port-specific reason.</b> The reference branch has no equivalent of this pack-script
     * layer at all: it binds {@code ctrl.set_animation} only through
     * {@code AnimationManager.applyControlScript -> decideControlScript}, which already skips a name the model's file
     * does not declare (this port's {@code AnimationManager.java:546-554}, reached from its
     * {@code AnimationManager.java:463-464}). The port's own per-controller script path
     * ({@code AnimationManager.predicateWithPackScript}, {@code AnimationManager.java:654-672}, evaluated at
     * {@code :665}) is new, so there is no upstream line to copy and the fix has to be the port's own existence check.
     * <p>
     * The audit is {@code tmp/unguarded-animation-names.md} §2.1, site C-1: {@code CtrlScriptBinding.java:161} hands
     * {@code builder} over with no check at all. The name is a pack's own string literal
     * ({@code stringArgument(this.args, 0, "")}, {@code :115-121}), and because the script is bound by controller name
     * while the animation file is resolved per animatable, the same literal can name a clip that exists only in the
     * other domain (a body controller's script naming an {@code fp_arm}-only clip and vice versa - §2.1 "跨域"). One
     * script file is also shared by every model under a pack's parent id, so a name valid for model A can be missing
     * for model B. {@code AnimationController#setAnimation} then takes its early return
     * ({@code software/bernie/geckolib3/core/controller/AnimationController.java:345-358}): it prints
     * "Could not load animation: &lt;name&gt;. Is it missing?" to stdout <em>and</em> returns before filling
     * {@code animationQueue} ({@code :360}) and before updating {@code currentAnimationBuilder} ({@code :362}) - so the
     * controller re-evaluates the same builder as "changed" every frame, spams once per frame, and is left with no
     * fallback. Refusing the name here is also the port's existing "a missing name does not override the caller"
     * semantic ({@code decideControlScript}, {@code AnimationManager.java:546-554}).
     * <p>
     * The check reuses {@link CtrlScriptBinding#animationFileContains}, which is the lookup
     * {@code OpenYsmPlayerControllerRuntime#animationExists}
     * ({@code client/animation/controller/OpenYsmPlayerControllerRuntime.java:367-384}) already performs, and it asks
     * about the same file id the engine will ({@code CustomPlayerModel#getAnimationFileLocation},
     * {@code client/model/CustomPlayerModel.java:128-133}).
     */
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
            // A script literal is global; the clip has to exist in this controller's own animation file, or the engine
            // is asked for something that is not there and the controller ends up with no animation at all.
            //
            // The file id cannot be resolved here: `get()` holds only the player and the controller, and the id lives
            // on the animatable (`CustomPlayerEntity#getAnimation`, `client/entity/CustomPlayerEntity.java:244-252`,
            // which is what `CustomPlayerModel#getAnimationFileLocation` hands the engine). Reaching it from here would
            // mean casting the controller's animatable to `CustomPlayerEntity` inside the MoLang binding and would give
            // a wrong answer for any other animatable. It is resolved once per frame where the event already exposes
            // the animatable (`AnimationManager.predicateWithPackScript`, `AnimationManager.java:665`) and carried on
            // the frame, whose whole job is already to carry this frame's binding context (`player`, `controller`,
            // `mainState`); it cannot change while the script is being evaluated, so this adds no state to track.
            if (frame.animationId != null && !animationFileContains(frame.animationId, animation)) {
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
