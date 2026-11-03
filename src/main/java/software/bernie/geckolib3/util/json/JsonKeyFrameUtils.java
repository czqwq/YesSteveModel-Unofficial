/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package software.bernie.geckolib3.util.json;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.math.NumberUtils;

import com.eliotlash.mclib.math.IValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import software.bernie.geckolib3.core.ConstantValue;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.util.AnimationUtils;

/**
 * Helper class to convert json to keyframes
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class JsonKeyFrameUtils {

    private static VectorKeyFrameList<KeyFrame<IValue>> convertJson(List<Map.Entry<String, JsonElement>> element,
        boolean isRotation, MolangParser parser) throws NumberFormatException, MolangException {
        IValue previousXValue = null;
        IValue previousYValue = null;
        IValue previousZValue = null;

        List<KeyFrame<IValue>> xKeyFrames = new ArrayList<>(element.size());
        List<KeyFrame<IValue>> yKeyFrames = new ArrayList<>(element.size());
        List<KeyFrame<IValue>> zKeyFrames = new ArrayList<>(element.size());

        for (int i = 0; i < element.size(); i++) {
            Map.Entry<String, JsonElement> keyframe = element.get(i);
            Map.Entry<String, JsonElement> previousKeyFrame = i == 0 ? null : element.get(i - 1);

            Double previousKeyFrameLocation = previousKeyFrame == null ? 0
                : Double.parseDouble(previousKeyFrame.getKey());
            Double currentKeyFrameLocation = NumberUtils.isNumber(keyframe.getKey())
                ? Double.parseDouble(keyframe.getKey())
                : 0;
            Double animationTimeDifference = currentKeyFrameLocation - previousKeyFrameLocation;

            JsonArray vectorJsonArray = getKeyFrameVector(keyframe.getValue());
            IValue xValue = parseExpression(parser, vectorJsonArray.get(0));
            IValue yValue = parseExpression(parser, vectorJsonArray.get(1));
            IValue zValue = parseExpression(parser, vectorJsonArray.get(2));

            IValue currentXValue = isRotation && xValue instanceof ConstantValue
                ? ConstantValue.fromDouble(Math.toRadians(-xValue.get()))
                : xValue;
            IValue currentYValue = isRotation && yValue instanceof ConstantValue
                ? ConstantValue.fromDouble(Math.toRadians(-yValue.get()))
                : yValue;
            IValue currentZValue = isRotation && zValue instanceof ConstantValue
                ? ConstantValue.fromDouble(Math.toRadians(zValue.get()))
                : zValue;
            KeyFrame<IValue> xKeyFrame;
            KeyFrame<IValue> yKeyFrame;
            KeyFrame<IValue> zKeyFrame;

            if (keyframe.getValue()
                .isJsonObject() && hasEasingType(keyframe.getValue())) {
                EasingType easingType = getEasingType(keyframe.getValue());
                if (hasEasingArgs(keyframe.getValue())) {
                    List<Double> easingArgs = getEasingArgs(keyframe.getValue(), parser);
                    xKeyFrame = new KeyFrame(
                        AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                        i == 0 ? currentXValue : previousXValue,
                        currentXValue,
                        easingType,
                        easingArgs);
                    yKeyFrame = new KeyFrame(
                        AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                        i == 0 ? currentYValue : previousYValue,
                        currentYValue,
                        easingType,
                        easingArgs);
                    zKeyFrame = new KeyFrame(
                        AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                        i == 0 ? currentZValue : previousZValue,
                        currentZValue,
                        easingType,
                        easingArgs);
                } else {
                    xKeyFrame = new KeyFrame(
                        AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                        i == 0 ? currentXValue : previousXValue,
                        currentXValue,
                        easingType);
                    yKeyFrame = new KeyFrame(
                        AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                        i == 0 ? currentYValue : previousYValue,
                        currentYValue,
                        easingType);
                    zKeyFrame = new KeyFrame(
                        AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                        i == 0 ? currentZValue : previousZValue,
                        currentZValue,
                        easingType);

                }
            } else {
                xKeyFrame = new KeyFrame(
                    AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                    i == 0 ? currentXValue : previousXValue,
                    currentXValue);
                yKeyFrame = new KeyFrame(
                    AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                    i == 0 ? currentYValue : previousYValue,
                    currentYValue);
                zKeyFrame = new KeyFrame(
                    AnimationUtils.convertSecondsToTicks(animationTimeDifference),
                    i == 0 ? currentZValue : previousZValue,
                    currentZValue);
            }

            previousXValue = currentXValue;
            previousYValue = currentYValue;
            previousZValue = currentZValue;

            xKeyFrames.add(xKeyFrame);
            yKeyFrames.add(yKeyFrame);
            zKeyFrames.add(zKeyFrame);
        }

        return new VectorKeyFrameList<>(xKeyFrames, yKeyFrames, zKeyFrames);
    }

    private static JsonArray getKeyFrameVector(JsonElement element) {
        if (element.isJsonArray()) {
            return element.getAsJsonArray();
        } else if (element.isJsonObject()) {
            return element.getAsJsonObject()
                .get("vector")
                .getAsJsonArray();
        } else {
            // Primitive value (e.g. "1.4583": 0.0) — expand to [val, val, val]
            JsonArray arr = new JsonArray();
            arr.add(element);
            arr.add(element);
            arr.add(element);
            return arr;
        }
    }

    private static boolean hasEasingType(JsonElement element) {
        if (!element.isJsonObject()) return false;
        return element.getAsJsonObject()
            .has("easing")
            || element.getAsJsonObject()
                .has("lerp_mode");
    }

    private static boolean hasEasingArgs(JsonElement element) {
        return element.getAsJsonObject()
            .has("easingArgs");
    }

    /**
     * Reads a keyframe's easing mode.
     *
     * <p>Upstream's answer, copied: {@code EasingType.fromJson}
     * ({@code tmp/YesSteveModel-dev-1.20 .../geckolib3/core/keyframe/bone/EasingType.java:11-27}) recognises
     * exactly two spellings, {@code linear} and {@code catmullrom}, and maps <b>everything else to LINEAR</b>.</p>
     *
     * <p>This method used to spell the value as an {@link EasingType} constant and {@code throw} when that
     * failed - and a throw here aborts the whole animation's assembly, so one unexpected word in one keyframe
     * cost the entire clip. {@code "step"} is the concrete case: this engine has an {@code EasingType.Step}, but
     * upstream has no such mode and a pack that writes it would be interpolated linearly by upstream. It is
     * therefore mapped to LINEAR here too, deliberately, together with every other spelling this engine has an
     * enum constant for - those constants remain reachable from the Java API
     * ({@code new AnimationController(..., EasingType, ...)}), they are just not a JSON vocabulary. The
     * alternatives are documented per enum constant in {@link EasingType}.</p>
     */
    private static EasingType getEasingType(JsonElement element) {
        final String easingString;
        if (element.getAsJsonObject()
            .has("easing"))
            easingString = element.getAsJsonObject()
                .get("easing")
                .getAsString();
        else easingString = element.getAsJsonObject()
            .get("lerp_mode")
            .getAsString();
        if ("linear".equalsIgnoreCase(easingString)) {
            return EasingType.Linear;
        }
        if ("catmullrom".equalsIgnoreCase(easingString)) {
            return EasingType.Catmullrom;
        }
        warnUnknownEasingType(easingString);
        return EasingType.Linear;
    }

    /** Distinct spellings already reported; a broken pack must not print one warning per keyframe. */
    private static final java.util.Set<String> REPORTED_EASING_TYPES = java.util.concurrent.ConcurrentHashMap
        .newKeySet();

    private static void warnUnknownEasingType(String easingString) {
        if (REPORTED_EASING_TYPES.add(easingString)) {
            System.err.println(
                "[GeckoLib] Unknown easing type '" + easingString + "', interpolating linearly (as upstream does)");
        }
    }

    /**
     * Reads {@code easingArgs} as the {@code List<Double>} that {@link KeyFrame#easingArgs} declares and that
     * {@code EasingManager.ease} consumes as {@code Double}.
     *
     * <p>It used to answer {@code JsonAnimationUtils.convertJsonArrayToList}'s raw {@code Gson}-built
     * {@code ArrayList} while declaring {@code List<IValue>}: numeric entries were {@code Double}, but a string
     * entry stayed a {@code String}, and {@code EasingManager.ease}'s {@code easingArgs.get(0)} assigns straight
     * into a {@code Double} - i.e. any pack writing a non-numeric argument threw {@code ClassCastException} at
     * animation-evaluation time, every frame. Easing arguments are numeric by definition (they are the step
     * count / overshoot factor / bounciness of the curve), so each entry is converted here, once, at parse time;
     * a text entry is evaluated as a Molang expression exactly once, which is the only timing consistent with the
     * {@code List<Double>} contract the evaluator has.</p>
     */
    private static List<Double> getEasingArgs(JsonElement element, MolangParser parser) {
        JsonElement easingArgs = element.getAsJsonObject()
            .get("easingArgs");
        if (easingArgs == null || !easingArgs.isJsonArray()) {
            return java.util.Collections.emptyList();
        }
        JsonArray array = easingArgs.getAsJsonArray();
        List<Double> args = new ArrayList<>(array.size());
        for (JsonElement arg : array) {
            args.add(easingArgValue(parser, arg));
        }
        return args;
    }

    private static double easingArgValue(MolangParser parser, JsonElement arg) {
        if (arg == null || arg.isJsonNull()) {
            return 0;
        }
        if (arg.isJsonPrimitive()) {
            if (arg.getAsJsonPrimitive()
                .isBoolean()) {
                return arg.getAsBoolean() ? 1 : 0;
            }
            if (arg.getAsJsonPrimitive()
                .isNumber()) {
                return arg.getAsDouble();
            }
            try {
                return parser.parseExpression(arg.getAsString())
                    .get();
            } catch (Exception unparsable) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Convert json to a rotation key frame vector list. This method also converts
     * degrees to radians.
     *
     * @param element The keyframe parent json element
     * @param parser
     * @return the vector key frame list
     * @throws NumberFormatException The number format exception
     */
    public static VectorKeyFrameList<KeyFrame<IValue>> convertJsonToKeyFrames(
        List<Map.Entry<String, JsonElement>> element, MolangParser parser)
        throws NumberFormatException, MolangException {
        List<Map.Entry<String, JsonElement>> list = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : element) {
            if (entry.getValue() instanceof JsonObject && !((JsonObject) entry.getValue()).has("vector")) {
                JsonObject entryObj = ((JsonObject) entry.getValue());
                String timestamp = entry.getKey();
                double time = NumberUtils.isNumber(timestamp) ? Double.parseDouble(timestamp) : 0;

                if (entryObj.has("pre")) {
                    JsonElement postElement = entryObj.get("pre");
                    JsonArray array = getKeyFrameVector(postElement);
                    JsonObject object = new JsonObject();
                    object.add("vector", array);
                    if (entryObj.has("lerp_mode")) {
                        object.add("lerp_mode", entryObj.get("lerp_mode"));
                    }
                    list.add(new AbstractMap.SimpleEntry<>(timestamp, object));
                }

                if (entryObj.has("post")) {
                    JsonElement postElement = entryObj.get("post");
                    JsonArray array = getKeyFrameVector(postElement);
                    JsonObject object = new JsonObject();
                    object.add("vector", array);
                    if (entryObj.has("lerp_mode")) {
                        object.add("lerp_mode", entryObj.get("lerp_mode"));
                    }
                    list.add(new AbstractMap.SimpleEntry<>(String.valueOf(time + 0.0000001), object));
                }

                continue;
            }

            list.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
        }
        return convertJson(list, false, parser);
    }

    /**
     * Convert json to normal json keyframes
     *
     * @param element The keyframe parent json element
     * @param parser
     * @return the vector key frame list
     * @throws NumberFormatException
     */
    public static VectorKeyFrameList<KeyFrame<IValue>> convertJsonToRotationKeyFrames(
        List<Map.Entry<String, JsonElement>> element, MolangParser parser)
        throws NumberFormatException, MolangException {
        List<Map.Entry<String, JsonElement>> list = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : element) {
            if (entry.getValue() instanceof JsonObject && !((JsonObject) entry.getValue()).has("vector")) {
                JsonObject entryObj = ((JsonObject) entry.getValue());
                String timestamp = entry.getKey();
                double time = NumberUtils.isNumber(timestamp) ? Double.parseDouble(timestamp) : 0;

                if (entryObj.has("pre")) {
                    JsonElement postElement = entryObj.get("pre");
                    JsonArray array = getKeyFrameVector(postElement);
                    JsonObject object = new JsonObject();
                    object.add("vector", array);
                    if (entryObj.has("lerp_mode")) {
                        object.add("lerp_mode", entryObj.get("lerp_mode"));
                    }
                    list.add(new AbstractMap.SimpleEntry<>(timestamp, object));
                }

                if (entryObj.has("post")) {
                    JsonElement postElement = entryObj.get("post");
                    JsonArray array = getKeyFrameVector(postElement);
                    JsonObject object = new JsonObject();
                    object.add("vector", array);
                    if (entryObj.has("lerp_mode")) {
                        object.add("lerp_mode", entryObj.get("lerp_mode"));
                    }
                    list.add(new AbstractMap.SimpleEntry<>(String.valueOf(time + 0.0000001), object));
                }

                continue;
            }

            list.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
        }
        VectorKeyFrameList<KeyFrame<IValue>> frameList = convertJson(list, true, parser);
        return new VectorKeyFrameList(frameList.xKeyFrames, frameList.yKeyFrames, frameList.zKeyFrames);
    }

    public static IValue parseExpression(MolangParser parser, JsonElement element) throws MolangException {
        if (element.getAsJsonPrimitive()
            .isString()) {
            return parser.parseJson(element);
        } else {
            return ConstantValue.fromDouble(element.getAsDouble());
        }
    }

}
