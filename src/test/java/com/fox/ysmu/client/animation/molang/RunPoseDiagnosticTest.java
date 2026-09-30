package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * Guards the thing that made sprinting look dead: every channel of {@code run} in 艾莲·乔1.4.0 is a colon-less
 * conditional such as {@code "(!v.hold?-32.5) + (v.hold?(v.speed?-33.51:-39.51))"}, so while the parser answered those
 * with a constant the whole animation collapsed to one frozen pose - while {@code walk}, whose channels are complete
 * ternaries, kept moving. That is exactly the "walk animates, sprint does not" the user reported.
 * <p>
 * This evaluates the real keyframes of the real pack and requires each driven channel to produce more than one value.
 * It skips itself when that pack is absent from the working tree, because the pack is test data and not a dependency.
 */
class RunPoseDiagnosticTest {

    @Test
    void runAndWalkKeyframesActuallyMove() throws Exception {
        File file = new File("tmp/\u827e\u83b2\u00b7\u4e541.4.0/animations/main.animation.json");
        assumeTrue(file.isFile(), "the pack is not present, so there is nothing to measure");
        JsonObject root = new JsonParser()
            .parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
            .getAsJsonObject();

        StringBuilder report = new StringBuilder();
        Map<String, Integer> distinctByChannel = new LinkedHashMap<>();
        for (String animation : new String[] { "walk", "run" }) {
            JsonObject bones = root.getAsJsonObject("animations")
                .getAsJsonObject(animation)
                .getAsJsonObject("bones");
            for (String bone : new String[] { "LeftLeg", "RightLeg", "LeftLowerLeg", "Root" }) {
                JsonElement entry = bones.get(bone);
                if (entry == null) {
                    continue;
                }
                for (String channel : new String[] { "rotation", "position" }) {
                    JsonElement value = entry.getAsJsonObject()
                        .get(channel);
                    if (value == null || !value.isJsonObject()) {
                        continue;
                    }
                    Set<String> distinct = new LinkedHashSet<>();
                    for (Map.Entry<String, JsonElement> keyframe : value.getAsJsonObject()
                        .entrySet()) {
                        JsonElement frame = keyframe.getValue();
                        JsonElement components = frame.isJsonObject() && frame.getAsJsonObject()
                            .has("post") ? frame.getAsJsonObject()
                                .get("post") : frame;
                        if (!components.isJsonArray()) {
                            continue;
                        }
                        for (JsonElement component : components.getAsJsonArray()) {
                            if (!component.isJsonPrimitive() || !component.getAsJsonPrimitive()
                                .isString()) {
                                continue;
                            }
                            // A fresh parser per evaluation, so nothing one keyframe assigns can leak into the next.
                            double evaluated = new MolangParser()
                                .parseExpression(
                                    component.getAsString()
                                        .trim())
                                .get();
                            distinct.add(String.format("%.3f", evaluated));
                        }
                    }
                    distinctByChannel.put(animation + "/" + bone + "/" + channel, distinct.size());
                    report.append(
                        String.format(
                            "%-6s %-14s %-9s distinct=%d%n",
                            animation,
                            bone,
                            channel,
                            distinct.size()));
                }
            }
        }
        System.out.println("\n" + report);
        assertTrue(
            distinctByChannel.containsKey("run/LeftLeg/rotation"),
            "the run animation must have been measured: " + report);
        // Rotations carry the motion; a leg *position* channel is legitimately constant when the pack's own
        // conditionals select a zero branch, so only the rotation channels are required to move.
        for (Map.Entry<String, Integer> entry : distinctByChannel.entrySet()) {
            if (!entry.getKey()
                .endsWith("/rotation")) {
                continue;
            }
            assertTrue(
                entry.getValue() > 1,
                entry.getKey() + " evaluates to a single value, so that animation cannot move: " + report);
        }
    }
}
