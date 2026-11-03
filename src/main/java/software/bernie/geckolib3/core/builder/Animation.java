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
