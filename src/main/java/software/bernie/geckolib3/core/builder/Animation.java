//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package software.bernie.geckolib3.core.builder;

import java.io.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.core.keyframe.ParticleEventKeyFrame;
import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.core.molang.expressions.MolangMultiStatement;

public class Animation implements Serializable {

    private static final long serialVersionUID = 42L;
    public String animationName;
    public Double animationLength;
    public ILoopType loop;
    /** YSMU: Bedrock-style per-animation custom time advance (anim_time_update).
     *  Molang expression evaluated each frame that returns the animation time
     *  in seconds (e.g. "query.anim_time + query.delta_time * 2"). Null/empty
     *  = normal time advance. YSM has no native equivalent; the name follows
     *  the Bedrock wiki.
     *  局限：过渡（blend）期间不生效（blend 不走 processCurrentAnimation）；
     *  若与 {@link #animSpeed} 同时设置则优先于 animSpeed；每帧表达式求值；
     *  二进制 .ysm 暂不支持。 */
    public String animTimeUpdate;
    /** YSMU: per-animation playback speed multiplier（数字或 Molang 表达式，返回
     *  播放倍率），映射到 GeckoLib AnimationController.animationSpeed，与防滑步
     *  倍率相乘。仅标量倍率，不支持任意时间曲线——需要自定义时间推进请用
     *  {@link #animTimeUpdate}。二进制 .ysm 暂不支持。 */
    public String animSpeed;
    /** Upstream AnimationPlayer:317-368: evaluated separately in each player's animation context. */
    public String blendWeight;

    /**
     * Parsed {@link #blendWeight} / {@link #animSpeed}, cached on the animation that declares them.
     *
     * <p>{@code AnimationController#processConcurrent} evaluated both by calling
     * {@code parser.parseExpression(text)} for every concurrent child, <b>every frame</b> - a full Molang
     * tokenise/lower-case/split/Pratt-parse of the same unchanged string, twice per child per frame, which is
     * the largest per-frame allocation left on the animation path. Caching the result on the declaration is
     * upstream's own shape: its {@code Animation} carries the already-parsed expression and the player only
     * evaluates it ({@code AnimationPlayer:318} {@code currentAnim.blendWeight.evalAsFloat(evaluator)},
     * {@code :346}, {@code :365}).</p>
     *
     * <p>{@code transient} because a parsed expression is live parser state (variable table, registered
     * functions) and belongs to the runtime, never to the serialised animation graph; a deserialised copy
     * simply parses once more. The source is kept beside the result so an animation whose text is replaced at
     * runtime reparses, and so a failed parse is remembered as "no expression" instead of being retried every
     * frame.</p>
     */
    public transient MolangExpression parsedBlendWeight;
    /** {@code blendWeight} text {@link #parsedBlendWeight} was produced from. */
    public transient String parsedBlendWeightSource;
    /** Parsed {@link #animSpeed}; see {@link #parsedBlendWeight}. */
    public transient MolangExpression parsedAnimSpeed;
    /** {@code animSpeed} text {@link #parsedAnimSpeed} was produced from. */
    public transient String parsedAnimSpeedSource;

    /**
     * Parsed {@link #customInstructionKeyframes}, aligned by index with it: one program per timeline entry.
     *
     * <p>{@code transient} for the same reason as {@link #parsedBlendWeight} - the parsed graph is live parser
     * state, never part of the serialised animation - so a deserialised animation re-parses once on its first
     * frame. Upstream parses these at bake time and its runtime only evaluates them
     * ({@code AnimationProtoMapper.java:77-87} builds {@code EventKeyFrame<IValue[]>},
     * {@code InstructionKeyFrameExecutor.java:18-35} only calls {@code value.eval(evaluator)}); this is the same
     * split with the parse moved to the first use of the animation, which is the shape this file already uses for
     * {@link #blendWeight} and {@link #animSpeed}.</p>
     *
     * <p>Without it every timeline dispatch re-tokenised and re-parsed the same text: a spark profile of a
     * 168-tick window showed {@code MolangInstructionExecutor.executeUncached -> MolangParser.parseExpression} at
     * 10.1 s of 65.6 s client-thread time (15.4 %), with the parser internals
     * ({@code MolangParser.isOperator}, {@code MathBuilder.breakdownChars}, the regex {@code String.matches}/
     * {@code String.split} calls) as its self time. One entry of a shipped pack is 11 198 characters and costs
     * 10.7 ms to parse; one LaPluma entry measures 19.0 ms.</p>
     */
    public transient List<EventKeyFrame<MolangExpression>> parsedInstructionKeyframes;
    /**
     * The {@link #customInstructionKeyframes} list {@link #parsedInstructionKeyframes} was built from. Identity, not
     * equality: a caller that replaces the list gets a fresh parse, and the unchanged list keeps the same cache.
     */
    public transient List<EventKeyFrame<String>> parsedInstructionKeyframesSource;

    public List<BoneAnimation> boneAnimations;
    public List<EventKeyFrame<String>> soundKeyFrames;
    public List<ParticleEventKeyFrame> particleKeyFrames;
    public List<EventKeyFrame<String>> customInstructionKeyframes;

    public Animation() {
        this.loop = EDefaultLoopTypes.LOOP;
        this.soundKeyFrames = new ArrayList();
        this.particleKeyFrames = new ArrayList();
        this.customInstructionKeyframes = new ArrayList();
    }

    /** UP: YesSteveModel-dev-1.20 {@code com/elfmcys/ysm/geckolib3/core/builder/Animation.java:52-54} — an
     *  animation with no keyframes at all. OpenYSM's parallel discovery skips one
     *  ({@code ParallelControllerDiscovery.java:67}), because a controller built for it would have
     *  nothing to play. */
    public boolean isEmpty() {
        return boneAnimations.isEmpty() && soundKeyFrames.isEmpty() && particleKeyFrames.isEmpty() && customInstructionKeyframes.isEmpty();
    }

    /**
     * Parses {@link #blendWeight} once per (animation, text) and returns the expression, or {@code null} when the
     * animation declares none or its text does not parse. See {@link #parsedBlendWeight}.
     */
    public MolangExpression blendWeightExpression(MolangParser parser) {
        String source = this.blendWeight;
        if (source == null) {
            return null;
        }
        if (source.equals(this.parsedBlendWeightSource)) {
            return this.parsedBlendWeight;
        }
        MolangExpression parsed = parseQuietly(parser, source);
        if (parsed != null && isReusableParse(parsed)) {
            // Expression first, then the source it belongs to: a reader that sees the new source must see the
            // matching result, otherwise a concurrent pass could read "parsed" with a stale null.
            this.parsedBlendWeight = parsed;
            this.parsedBlendWeightSource = source;
        } else {
            // Either the text does not parse (remembered: a parse failure is deterministic, so the broken
            // declaration must not be retried every frame) or parsing it creates statement-local variables,
            // whose parsed graph is stateful and therefore must be rebuilt per evaluation, exactly as before.
            this.parsedBlendWeight = null;
            this.parsedBlendWeightSource = parsed == null ? source : null;
        }
        return parsed;
    }

    /** Parses {@link #animSpeed} once per (animation, text); see {@link #parsedBlendWeight}. */
    public MolangExpression animSpeedExpression(MolangParser parser) {
        String source = this.animSpeed;
        if (source == null) {
            return null;
        }
        if (source.equals(this.parsedAnimSpeedSource)) {
            return this.parsedAnimSpeed;
        }
        MolangExpression parsed = parseQuietly(parser, source);
        if (parsed != null && isReusableParse(parsed)) {
            this.parsedAnimSpeed = parsed;
            this.parsedAnimSpeedSource = source;
        } else {
            this.parsedAnimSpeed = null;
            this.parsedAnimSpeedSource = parsed == null ? source : null;
        }
        return parsed;
    }

    /** {@code MolangParser#parseExpression} without its checked exception; {@code null} means "does not parse". */
    private static MolangExpression parseQuietly(MolangParser parser, String source) {
        try {
            return parser.parseExpression(source);
        } catch (RuntimeException | MolangException failure) {
            return null;
        }
    }

    /**
     * Parses {@link #customInstructionKeyframes} once and returns one evaluated program per timeline entry, aligned
     * by index with the source keyframes. This is the method that keeps timeline text out of the per-frame path;
     * see {@link #parsedInstructionKeyframes} for the profile that motivated it.
     *
     * <p>A keyframe whose text does not parse becomes {@link MolangParser#ZERO} (a constant 0), which is what
     * upstream's {@code MolangParser#parseExpression} answers on failure - it logs the reason and returns zero
     * rather than leaving the text for a later retry. The parse itself is reported once by this engine's parser
     * ({@code MolangParser#reportParseFailure}), which names the failing expression. Entries are parsed whole (all
     * {@code ;}-separated statements of the entry together), which is also upstream's shape: one conditionless
     * program per instruction keyframe, not one per statement.
     *
     * <p><b>Whole-entry parse failure falls back to statement-by-statement parsing.</b> Upstream's instruction
     * keyframe carries an {@code IValue[]} - one value per statement - and its executor evaluates each value on its
     * own ({@code AnimationProtoMapper.java:77-87} -> {@code InstructionKeyFrameExecutor.java:18-35}), so one
     * statement that does not parse costs only that statement. This engine's {@code MolangParser#parseExpression}
     * instead throws for the whole text as soon as one statement fails, which made a single marker statement discard
     * every assignment in the entry: shipped packs use bare {@code [} / {@code ]} section markers inside timeline
     * entries (Furina's {@code parallel0} is 365 statements with such markers), and that silently killed the whole
     * physics block. The fallback reproduces the old per-statement executor (and upstream's array shape): the
     * statements that parse are kept as separate programs at the same start tick, the ones that do not are skipped.
     *
     * <p>Called once per animation by whichever path uses it first - both animation builders warm it during the
     * model bake - so the cost is paid once per animation per model install instead of once per keyframe dispatch.
     */
    public List<EventKeyFrame<MolangExpression>> instructionKeyframes(MolangParser parser) {
        List<EventKeyFrame<String>> source = this.customInstructionKeyframes;
        if (source == null) {
            return java.util.Collections.emptyList();
        }
        if (this.parsedInstructionKeyframesSource == source) {
            return this.parsedInstructionKeyframes;
        }
        List<EventKeyFrame<MolangExpression>> parsed = new ArrayList<>(source.size());
        for (EventKeyFrame<String> keyFrame : source) {
            if (keyFrame == null) {
                parsed.add(new EventKeyFrame<>(0.0D, MolangParser.ZERO));
                continue;
            }
            double startTick = keyFrame.getStartTick();
            MolangExpression program = parseQuietly(parser, keyFrame.getEventData());
            if (program != null) {
                parsed.add(new EventKeyFrame<>(startTick, program));
                continue;
            }
            // One bad statement must not discard the entry's other statements; see the javadoc above.
            List<String> statements;
            try {
                statements = MolangParser.splitStatements(keyFrame.getEventData());
            } catch (Exception failure) {
                statements = java.util.Collections.emptyList();
            }
            boolean any = false;
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                MolangExpression statementProgram = parseQuietly(parser, trimmed);
                if (statementProgram == null) {
                    continue;
                }
                parsed.add(new EventKeyFrame<>(startTick, statementProgram));
                any = true;
            }
            if (!any) {
                parsed.add(new EventKeyFrame<>(startTick, MolangParser.ZERO));
            }
        }
        // Program list first, source list second: a reader that sees the new source must see the matching programs.
        this.parsedInstructionKeyframes = parsed;
        this.parsedInstructionKeyframesSource = source;
        return parsed;
    }

    /**
     * Whether a parsed expression may be shared across evaluations.
     *
     * <p>The one shape that may not is a statement creating <em>statement-local</em> variables:
     * {@link MolangParser#parseExpression} answers a {@link MolangMultiStatement}, and an assignment to a name
     * that is neither {@code v.} nor an already-registered variable puts a mutable {@code LazyVariable} into its
     * {@code locals} ({@code MolangParser:703-717}). Re-parsing per evaluation gave that variable a fresh zero
     * every time; sharing the parse would carry it across frames, so a recursive {@code x=x+0.1} would
     * accumulate instead of restarting. Everything else in a parsed graph is a pure operator/function over
     * variables that are read at evaluation time, so sharing it is invisible.</p>
     */
    private static boolean isReusableParse(MolangExpression parsed) {
        return !(parsed instanceof MolangMultiStatement) || ((MolangMultiStatement) parsed).locals.isEmpty();
    }

    public static Animation copy(Animation animation) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(animation);
            objectOutputStream.flush();
            String serialized = Base64.getEncoder()
                .encodeToString(byteArrayOutputStream.toByteArray());

            byte[] data = Base64.getDecoder()
                .decode(serialized);
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
            ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
            return (Animation) objectInputStream.readObject();
        } catch (Exception e) {
            return animation;
        }
    }
}
