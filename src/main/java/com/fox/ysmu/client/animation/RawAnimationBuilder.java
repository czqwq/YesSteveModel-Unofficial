package com.fox.ysmu.client.animation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonPrimitive;

import software.bernie.geckolib3.core.ConstantValue;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.util.AnimationUtils;

/**
 * Builds the engine's {@link Animation} objects straight from the binary payload's animation structures.
 *
 * <p>
 * The OpenYSM channel carries animations as bones/channels/keyframes ({@link RawYsmModel.RawAnimation}), but this port
 * used to re-emit them as pretty-printed JSON and then parse that text back with Gson before handing it to the engine's
 * JSON reader. This class is the direct replacement: the same values, in the same units, in the same order, with no
 * text in between. A payload that really is JSON (a folder model, the built-in default, the legacy container) keeps
 * the old path untouched - {@code RawAnimationFile.sourceJson} tells the two apart.
 *
 * <p>
 * Every rule below is copied from the pair of methods that used to run on the other side of the text:
 * {@code RawYsmModelAdapter.createAnimationJson} (structure -> JSON) and the engine's
 * {@code JsonAnimationUtils.deserializeJsonToAnimation} / {@code JsonKeyFrameUtils} (JSON -> Animation), including the
 * choices that only make sense when both halves are read together:
 * <ul>
 *   <li>a single zero-time keyframe without pre-data is written as a bare vector, which also drops its
 *       {@code lerp_mode};</li>
 *   <li>{@code pre}/{@code post} are expanded into two keyframes {@code 1e-7} seconds apart, and each keyframe's
 *       {@code time} is the difference from the previous one, multiplied by 20;</li>
 *   <li>rotation degrees become radians with X and Y negated, but <b>only</b> for literal numbers: a MoLang string is
 *       left alone, which is what {@code xValue instanceof ConstantValue} does on the JSON side;</li>
 *   <li>timeline events sharing a timestamp are merged into one instruction string joined by {@code ";\n"}, and sound
 *       effects sharing one keep the last;</li>
 *   <li>{@code animation_length} comes from the payload when positive, and is then raised to at least the keyframe span
 *       (a step {@code post} on the last frame would otherwise never be reachable).</li>
 * </ul>
 * The {@code AnimEquivProbe} under {@code tmp/_analysis/probe-bake} compares this builder's output field by field
 * against the JSON path for every animation of the built-in packs; a change to either side must keep that probe
 * passing.
 */
public final class RawAnimationBuilder {

    private RawAnimationBuilder() {}

    /** One payload animation file as the engine wants it, or an empty file when it declares nothing usable. */
    public static AnimationFile toAnimationFile(RawYsmModel.RawAnimationFile source, MolangParser parser) {
        AnimationFile file = new AnimationFile();
        if (source == null) {
            return file;
        }
        for (RawYsmModel.RawAnimation animation : source.animations.values()) {
            if (StringUtils.isBlank(animation.name)) {
                continue;
            }
            try {
                file.putAnimation(animation.name, toAnimation(animation, parser));
            } catch (Exception failure) {
                // Same per-animation containment the JSON path has (ClientModelManager.animationFileFromJson): one
                // broken clip must not cost the model the rest of its animation set.
                ysmu.LOG.warn(
                    "Failed to build animation {}: {}: {}",
                    animation.name,
                    failure.getClass()
                        .getSimpleName(),
                    failure.getMessage());
            }
        }
        return file;
    }

    /** One animation, built exactly as the JSON path would build it from this payload's own JSON. */
    public static Animation toAnimation(RawYsmModel.RawAnimation source, MolangParser parser) throws MolangException {
        Animation animation = new Animation();
        animation.animationName = source.name;
        animation.boneAnimations = new ArrayList<>();
        animation.loop = loopType(source.loopMode);
        if (isFinite(source.length) && source.length > 0f) {
            // The JSON writer emits Float.toString so timeline keys stay on one decimal precision; see
            // createAnimationJson's comment. parseDouble of that same text is what the engine reads back.
            animation.animationLength = AnimationUtils
                .convertSecondsToTicks(Double.parseDouble(Float.toString(source.length)));
        }
        animation.blendWeight = molangText(source.blendWeight);
        animation.animSpeed = blankToNull(source.animSpeed);
        animation.animTimeUpdate = blankToNull(source.animTimeUpdate);

        Map<String, String> soundEffects = new LinkedHashMap<>();
        for (RawYsmModel.RawSoundEffect effect : source.soundEffects) {
            if (StringUtils.isBlank(effect.effectName)) {
                continue;
            }
            // JSON object keys are unique: a later effect at the same timestamp replaces the earlier one.
            soundEffects.put(timeKey(effect.timestamp), effect.effectName);
        }
        for (Map.Entry<String, String> entry : soundEffects.entrySet()) {
            animation.soundKeyFrames.add(new EventKeyFrame<>(Double.parseDouble(entry.getKey()) * 20, entry.getValue()));
        }

        Map<String, List<String>> timeline = new LinkedHashMap<>();
        for (RawYsmModel.RawTimelineEvent event : source.timelineEvents) {
            if (event.events.isEmpty()) {
                continue;
            }
            timeline.computeIfAbsent(timeKey(event.timestamp), ignored -> new ArrayList<>())
                .addAll(event.events);
        }
        for (Map.Entry<String, List<String>> entry : timeline.entrySet()) {
            List<String> instructions = entry.getValue();
            String text = instructions.size() == 1 ? instructions.get(0) : String.join(";\n", instructions);
            animation.customInstructionKeyframes.add(
                new EventKeyFrame<>(Double.parseDouble(entry.getKey()) * 20, text));
        }

        for (RawYsmModel.RawBoneAnimation bone : source.boneAnimations) {
            if (StringUtils.isBlank(bone.boneName)) {
                continue;
            }
            VectorKeyFrameList<KeyFrame<IValue>> rotation = channel(bone.rotation, true, parser);
            VectorKeyFrameList<KeyFrame<IValue>> position = channel(bone.position, false, parser);
            VectorKeyFrameList<KeyFrame<IValue>> scale = channel(bone.scale, false, parser);
            if (rotation.xKeyFrames.isEmpty() && position.xKeyFrames.isEmpty() && scale.xKeyFrames.isEmpty()) {
                // The JSON writer skips a bone object with no channel; the engine never sees such a bone.
                continue;
            }
            BoneAnimation boneAnimation = new BoneAnimation();
            boneAnimation.boneName = bone.boneName;
            boneAnimation.rotationKeyFrames = rotation;
            boneAnimation.positionKeyFrames = position;
            boneAnimation.scaleKeyFrames = scale;
            animation.boneAnimations.add(boneAnimation);
        }

        if (animation.animationLength == null) {
            animation.animationLength = calculateLength(animation.boneAnimations);
        } else {
            double keyFrameSpan = calculateLength(animation.boneAnimations);
            if (keyFrameSpan > 0 && keyFrameSpan < Double.MAX_VALUE) {
                animation.animationLength = Math.max(animation.animationLength, keyFrameSpan);
            }
        }
        return animation;
    }

    /**
     * The text used to detect which optional mods an animation refers to.
     * <p>
     * The JSON path scans {@code animationJsonElement.toString()} for substrings registered by
     * {@code ModDependency} (for example {@code ctrl.tac_hold_gun}). A structure has no JSON text, so this returns the
     * same set of values - every MoLang expression, timeline instruction, effect name and animation-level text - joined
     * with newlines. Keys and JSON punctuation were never part of a dependency pattern, and the numbers a pattern could
     * name are present here too ({@code Float#toString} of the literal).
     */
    public static String modScanText(RawYsmModel.RawAnimation source) {
        StringBuilder text = new StringBuilder(256);
        appendLine(text, molangText(source.blendWeight));
        appendLine(text, source.animSpeed);
        appendLine(text, source.animTimeUpdate);
        for (RawYsmModel.RawBoneAnimation bone : source.boneAnimations) {
            appendChannel(text, bone.rotation);
            appendChannel(text, bone.position);
            appendChannel(text, bone.scale);
        }
        for (RawYsmModel.RawTimelineEvent event : source.timelineEvents) {
            for (String instruction : event.events) {
                appendLine(text, instruction);
            }
        }
        for (RawYsmModel.RawSoundEffect effect : source.soundEffects) {
            appendLine(text, effect.effectName);
        }
        return text.toString();
    }

    /**
     * A digest of the exact structures this builder would turn into {@code Animation} objects.
     * <p>
     * Used as the disk cache key's content component, so a stale identity (a pack may declare a hash about itself that
     * does not match its bytes) can only misplace an entry, never serve one model's animations for another. It covers
     * everything the builder reads, in order: animation metadata, every bone/channel keyframe with its timestamp, both
     * vectors, pre-data flag and interpolation mode, the timeline, and the sound effects.
     */
    public static String structureDigest(RawYsmModel.RawAnimationFile source) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            if (source == null) {
                digest.update((byte) 0);
                return com.fox.ysmu.util.Md5Utils.toHexString(digest.digest());
            }
            digest.update((byte) 1);
            updateInt(digest, source.animations.size());
            for (Map.Entry<String, RawYsmModel.RawAnimation> entry : source.animations.entrySet()) {
                updateString(digest, entry.getKey());
                RawYsmModel.RawAnimation animation = entry.getValue();
                if (animation == null) {
                    digest.update((byte) 0);
                    continue;
                }
                digest.update((byte) 1);
                updateString(digest, animation.name);
                updateInt(digest, Float.floatToRawIntBits(animation.length));
                updateInt(digest, animation.loopMode);
                updateString(digest, molangText(animation.blendWeight));
                updateString(digest, animation.animSpeed);
                updateString(digest, animation.animTimeUpdate);
                updateInt(digest, animation.boneAnimations.size());
                for (RawYsmModel.RawBoneAnimation bone : animation.boneAnimations) {
                    updateString(digest, bone.boneName);
                    updateKeyframes(digest, bone.rotation);
                    updateKeyframes(digest, bone.position);
                    updateKeyframes(digest, bone.scale);
                }
                updateInt(digest, animation.timelineEvents.size());
                for (RawYsmModel.RawTimelineEvent event : animation.timelineEvents) {
                    updateInt(digest, Float.floatToRawIntBits(event.timestamp));
                    updateInt(digest, event.events.size());
                    for (String instruction : event.events) {
                        updateString(digest, instruction);
                    }
                }
                updateInt(digest, animation.soundEffects.size());
                for (RawYsmModel.RawSoundEffect effect : animation.soundEffects) {
                    updateInt(digest, Float.floatToRawIntBits(effect.timestamp));
                    updateString(digest, effect.effectName);
                }
            }
            return com.fox.ysmu.util.Md5Utils.toHexString(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }

    private static void updateKeyframes(java.security.MessageDigest digest,
        List<RawYsmModel.RawKeyframe> keyframes) {
        updateInt(digest, keyframes.size());
        for (RawYsmModel.RawKeyframe keyframe : keyframes) {
            updateInt(digest, Float.floatToRawIntBits(keyframe.timestamp));
            updateInt(digest, keyframe.hasPreData ? 1 : 0);
            updateInt(digest, keyframe.interpolationMode);
            updateValues(digest, keyframe.preData);
            updateValues(digest, keyframe.postData);
        }
    }

    private static void updateValues(java.security.MessageDigest digest, Object[] values) {
        if (values == null) {
            updateInt(digest, -1);
            return;
        }
        updateInt(digest, values.length);
        for (Object value : values) {
            updateString(digest, value == null ? null : value.toString());
        }
    }

    private static void updateString(java.security.MessageDigest digest, String value) {
        if (value == null) {
            updateInt(digest, -1);
            return;
        }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(java.security.MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
    private static void appendChannel(StringBuilder text, List<RawYsmModel.RawKeyframe> keyframes) {
        for (RawYsmModel.RawKeyframe keyframe : keyframes) {
            appendValues(text, keyframe.preData);
            appendValues(text, keyframe.postData);
        }
    }

    private static void appendValues(StringBuilder text, Object[] values) {
        if (values == null) {
            return;
        }
        for (Object value : values) {
            if (value != null) {
                appendLine(text, value.toString());
            }
        }
    }

    private static void appendLine(StringBuilder text, String value) {
        if (value != null && !value.isEmpty()) {
            text.append(value)
                .append('\n');
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Keyframes
    // -------------------------------------------------------------------------------------------------------------

    private static VectorKeyFrameList<KeyFrame<IValue>> channel(List<RawYsmModel.RawKeyframe> keyframes,
        boolean rotation, MolangParser parser) throws MolangException {
        VectorKeyFrameList<KeyFrame<IValue>> empty = new VectorKeyFrameList<>();
        if (keyframes == null || keyframes.isEmpty()) {
            return empty;
        }
        if (keyframes.size() == 1) {
            RawYsmModel.RawKeyframe only = keyframes.get(0);
            if (!only.hasPreData && isZeroTime(only.timestamp)) {
                // createAnimationJson.putChannel's bare-vector form: no lerp_mode reaches the engine either.
                List<Sample> samples = new ArrayList<>(1);
                samples.add(new Sample(timeKey(only.timestamp), only.postData, null));
                return convert(samples, rotation, parser);
            }
        }
        Map<String, Sample> byKey = new LinkedHashMap<>();
        for (RawYsmModel.RawKeyframe keyframe : keyframes) {
            String lerpMode = blankToNull(lerpMode(keyframe.interpolationMode));
            String key = timeKey(keyframe.timestamp);
            if (!keyframe.hasPreData) {
                byKey.put(key, new Sample(key, keyframe.postData, lerpMode));
                continue;
            }
            byKey.put(key, new Sample(key, keyframe.preData, lerpMode));
            double time = NumberUtils.isNumber(key) ? Double.parseDouble(key) : 0;
            String postKey = String.valueOf(time + 0.0000001);
            byKey.put(postKey, new Sample(postKey, keyframe.postData, lerpMode));
        }
        return convert(new ArrayList<>(byKey.values()), rotation, parser);
    }

    private static VectorKeyFrameList<KeyFrame<IValue>> convert(List<Sample> samples, boolean rotation,
        MolangParser parser) throws MolangException {
        List<KeyFrame<IValue>> xKeyFrames = new ArrayList<>(samples.size());
        List<KeyFrame<IValue>> yKeyFrames = new ArrayList<>(samples.size());
        List<KeyFrame<IValue>> zKeyFrames = new ArrayList<>(samples.size());
        IValue previousX = null;
        IValue previousY = null;
        IValue previousZ = null;
        double previousLocation = 0;
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            double currentLocation = NumberUtils.isNumber(sample.key) ? Double.parseDouble(sample.key) : 0;
            double difference = currentLocation - previousLocation;
            double length = AnimationUtils.convertSecondsToTicks(difference);

            IValue xValue = rotateIfLiteral(parse(parser, sample.vector, 0), rotation, -1);
            IValue yValue = rotateIfLiteral(parse(parser, sample.vector, 1), rotation, -1);
            IValue zValue = rotateIfLiteral(parse(parser, sample.vector, 2), rotation, 1);

            KeyFrame<IValue> xKeyFrame;
            KeyFrame<IValue> yKeyFrame;
            KeyFrame<IValue> zKeyFrame;
            if (sample.lerpMode != null) {
                EasingType easing = easingType(sample.lerpMode);
                xKeyFrame = new KeyFrame<>(length, index == 0 ? xValue : previousX, xValue, easing);
                yKeyFrame = new KeyFrame<>(length, index == 0 ? yValue : previousY, yValue, easing);
                zKeyFrame = new KeyFrame<>(length, index == 0 ? zValue : previousZ, zValue, easing);
            } else {
                xKeyFrame = new KeyFrame<>(length, index == 0 ? xValue : previousX, xValue);
                yKeyFrame = new KeyFrame<>(length, index == 0 ? yValue : previousY, yValue);
                zKeyFrame = new KeyFrame<>(length, index == 0 ? zValue : previousZ, zValue);
            }
            previousX = xValue;
            previousY = yValue;
            previousZ = zValue;
            previousLocation = currentLocation;
            xKeyFrames.add(xKeyFrame);
            yKeyFrames.add(yKeyFrame);
            zKeyFrames.add(zKeyFrame);
        }
        return new VectorKeyFrameList<>(xKeyFrames, yKeyFrames, zKeyFrames);
    }

    /** Mirrors {@code JsonKeyFrameUtils.parseExpression} for the two value shapes the payload can carry. */
    private static IValue parse(MolangParser parser, Object[] vector, int index) throws MolangException {
        Object raw = vector != null && vector.length > index ? vector[index] : Float.valueOf(0f);
        if (raw instanceof Number) {
            // The JSON path writes the number with Number#toString and reads it back with getAsDouble, so a Float's
            // shortest representation ("16.7") reaches the engine as the double 16.7 rather than as
            // 16.700000762939453. Going through the same text keeps every keyframe value bit-identical.
            return ConstantValue.fromDouble(Double.parseDouble(raw.toString()));
        }
        if (raw instanceof Boolean) {
            // The JSON path throws here (JsonPrimitive#getAsDouble on a boolean), which drops the whole animation.
            throw new IllegalArgumentException("boolean keyframe component");
        }
        return parser.parseJson(new JsonPrimitive(raw == null ? "0" : raw.toString()));
    }

    private static IValue rotateIfLiteral(IValue value, boolean rotation, int sign) {
        if (!rotation || !(value instanceof ConstantValue)) {
            return value;
        }
        return ConstantValue.fromDouble(Math.toRadians(sign * value.get()));
    }

    private static EasingType easingType(String lerpMode) {
        String name = Character.toUpperCase(lerpMode.charAt(0)) + lerpMode.substring(1);
        return EasingType.valueOf(name);
    }

    private static String lerpMode(int interpolationMode) {
        if (interpolationMode == 1) {
            return "step";
        }
        if (interpolationMode == 2) {
            return "catmullrom";
        }
        return "";
    }

    /**
     * The loop type the JSON writer's {@code loop} value would produce: 1 = {@code true}, 3 =
     * {@code "hold_on_last_frame"}, 0 = {@code false}, anything else = the key is not written at all, which the engine
     * reads as {@code PLAY_ONCE}.
     */
    private static ILoopType loopType(int loopMode) {
        if (loopMode == 1) {
            return EDefaultLoopTypes.LOOP;
        }
        if (loopMode == 3) {
            return EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
        }
        return EDefaultLoopTypes.PLAY_ONCE;
    }

    /** {@code JsonAnimationUtils.calculateLength}: the longest sum of keyframe lengths across every channel. */
    private static double calculateLength(List<BoneAnimation> boneAnimations) {
        double longest = 0;
        for (BoneAnimation animation : boneAnimations) {
            longest = Math.max(
                longest,
                Math.max(
                    Math.max(
                        animation.rotationKeyFrames.getLastKeyframeTime(),
                        animation.positionKeyFrames.getLastKeyframeTime()),
                    animation.scaleKeyFrames.getLastKeyframeTime()));
        }
        // The engine returns Double.MAX_VALUE when no channel has a keyframe, so a pure timeline animation keeps its
        // declared length. Reproduce that sentinel for the caller's "cannot be a span" test.
        return longest == 0 ? Double.MAX_VALUE : longest;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean isZeroTime(float seconds) {
        return isFinite(seconds) && Math.abs(seconds) < 0.000001f;
    }

    /** The same key text the JSON writer produces, so the double parsed here matches the engine's exactly. */
    private static String timeKey(float seconds) {
        return isFinite(seconds) ? Float.toString(seconds) : "0.0";
    }

    private static String molangText(Object value) {
        return value == null ? null : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.trim()
            .isEmpty() ? null : value;
    }

    /** One keyframe as the JSON path sees it: the time key plus the vector (and optional lerp mode). */
    private static final class Sample {

        private final String key;
        private final Object[] vector;
        private final String lerpMode;

        private Sample(String key, Object[] vector, String lerpMode) {
            this.key = key;
            this.vector = vector;
            this.lerpMode = lerpMode;
        }
    }
}
