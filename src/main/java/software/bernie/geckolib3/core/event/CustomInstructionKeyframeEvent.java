/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package software.bernie.geckolib3.core.event;

import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;

public class CustomInstructionKeyframeEvent<T> extends KeyframeEvent<T> {

    /**
     * The timeline entry that fired, already parsed.
     *
     * <p>YSMU: a parsed program, not the source text. Upstream parses an instruction keyframe's source once at bake
     * time and its runtime only evaluates it ({@code AnimationProtoMapper.java:77-87} stores
     * {@code EventKeyFrame<IValue[]>}, {@code InstructionKeyFrameExecutor.java:18-35} only calls
     * {@code value.eval(evaluator)}); carrying the text here made every dispatch re-tokenise and re-parse it, which a
     * spark profile measured at 15.4 % of client-thread time. The text lives on
     * {@link AnimationController}'s keyframe list; this field is what a listener needs to run it.
     */
    public final MolangExpression program;

    /**
     * This stores all the fields that are needed in the AnimationTestEvent
     *
     * @param entity        the entity
     * @param animationTick The amount of ticks that have passed in either the current transition or animation,
     *                      depending on the controller's AnimationState.
     * @param program       the parsed program of every instruction line of this timeline entry, executed in order
     * @param controller    the controller
     */
    public CustomInstructionKeyframeEvent(T entity, double animationTick, MolangExpression program,
        AnimationController controller) {
        super(entity, animationTick, controller);
        this.program = program;
    }
}
