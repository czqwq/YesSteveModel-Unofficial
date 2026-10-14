package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The built-in "default controllers" model must keep shipping upstream's controller set, and that set must stay
 * consistent with the model's own animations.
 *
 * <p>Upstream bundles {@code builtin/misc/4_default_controllers/} as a controllers-only model: its
 * {@code controller/main_controllers.json} is the sword-guard chain every model can be measured against
 * (SOURCE {@code assets/ysm/builtin/misc/4_default_controllers/controller/main_controllers.json}, the same design is
 * present in the released 1.21.1 jar). The port shipped that model with a reduced, older animation set and no
 * controller file at all, so a model that fell back to it had no sword chain to play and no {@code v.swing_sword}
 * consumer - the reason a swing with a sword could look like a variable write and nothing else.
 *
 * <p>The chain is flag-driven, not counter-driven: the {@code swing:sword} clip writes {@code v.swing_sword=1}, the
 * controller transitions on that flag once the current clip finishes, and {@code on_exit} clears it
 * ({@code ExpressionEvaluatorImpl:78-103} is why a {@code v.attack=0} transition can never fire - a lone {@code =} is
 * an assignment whose value is what was assigned, so {@code asBoolean(0)} is false, upstream included). Those two
 * halves are what this test pins.
 *
 * <p>The second test is the one that would have caught the port's reduced asset set: every plain animation name a
 * state asks for has to exist in the model. Conditional names ({@code hold_offhand:sword}) and qualified names
 * ({@code hold_offhand$minecraft:mace}) are selection rules rather than clips, so they are excluded the same way the
 * port's own classifier excludes them.
 */
class BuiltinDefaultControllersTest {

    private static final String ROOT = "assets/ysmu/builtin/misc/4_default_controllers/";
    private static final String CONTROLLERS = "controller/main_controllers.json";
    private static final String[] ANIMATION_FILES = { "animations/main.animation.json", "animations/arm.animation.json",
        "animations/tac.animation.json" };

    @Test
    void shipsUpstreamSwordChainDrivenByTheSwingFlag() {
        JsonObject controllers = read(ROOT + CONTROLLERS).getAsJsonObject("animation_controllers");
        assertNotNull(controllers, CONTROLLERS + " must carry animation_controllers");

        Set<String> conditions = new TreeSet<>();
        Set<String> exits = new TreeSet<>();
        int controllersSeen = 0;
        for (Map.Entry<String, JsonElement> entry : controllers.entrySet()) {
            controllersSeen++;
            JsonObject states = entry.getValue()
                .getAsJsonObject()
                .getAsJsonObject("states");
            if (states == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> state : states.entrySet()) {
                JsonObject stateObj = state.getValue()
                    .getAsJsonObject();
                // Bedrock writes transitions as an array of single-key objects: [{"attack1": "<condition>"}].
                JsonArray transitions = stateObj.getAsJsonArray("transitions");
                if (transitions != null) {
                    for (JsonElement transition : transitions) {
                        for (Map.Entry<String, JsonElement> target : transition.getAsJsonObject()
                            .entrySet()) {
                            conditions.add(target.getValue()
                                .getAsString());
                        }
                    }
                }
                JsonArray onExit = stateObj.getAsJsonArray("on_exit");
                if (onExit != null) {
                    for (JsonElement exit : onExit) {
                        exits.add(exit.getAsString());
                    }
                }
            }
        }

        assertTrue(controllersSeen > 0, "the built-in set must define controllers");
        // The chain lives in player.post_swing: attack_empty -> attack1 -> attack2 -> ... while the clip writes the
        // flag, and each state clears it on the way out.
        assertTrue(
            conditions.stream()
                .anyMatch(c -> c.contains("v.swing_sword")),
            "a transition must be driven by the flag the swing:sword clip writes, saw " + conditions);
        assertTrue(
            exits.stream()
                .anyMatch(e -> e.contains("v.swing_sword")),
            "the flag must be cleared when the state is left, saw " + exits);
    }

    @Test
    void everyClipTheSwordChainNamesExistsInTheModel() {
        Set<String> available = new TreeSet<>();
        for (String file : ANIMATION_FILES) {
            JsonObject animations = read(ROOT + file).getAsJsonObject("animations");
            if (animations != null) {
                // gson on this platform predates JsonObject#keySet, so read the entries.
                for (Map.Entry<String, JsonElement> animation : animations.entrySet()) {
                    available.add(animation.getKey());
                }
            }
        }
        assertTrue(available.size() > 100, "the built-in model must ship its animation set, saw " + available.size());

        // Scope this to the sword chain: other controllers in the same file name parallel/pre controllers that live
        // in sibling built-in models rather than clips of this one.
        JsonObject controllers = read(ROOT + CONTROLLERS).getAsJsonObject("animation_controllers");
        JsonObject states = controllers.getAsJsonObject("player.post_swing")
            .getAsJsonObject("states");
        assertNotNull(states, "the built-in set must carry the sword chain state machine");

        Set<String> referenced = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> state : states.entrySet()) {
            JsonArray animations = state.getValue()
                .getAsJsonObject()
                .getAsJsonArray("animations");
            if (animations == null) {
                continue;
            }
            for (JsonElement element : animations) {
                if (element.isJsonPrimitive()) {
                    referenced.add(element.getAsString());
                } else {
                    for (Map.Entry<String, JsonElement> conditional : element.getAsJsonObject()
                        .entrySet()) {
                        referenced.add(conditional.getKey());
                    }
                }
            }
        }
        assertTrue(referenced.size() >= 5, "the chain must name its attack clips, saw " + referenced);

        Set<String> missing = new TreeSet<>();
        for (String name : referenced) {
            // A conditional name (`hold_offhand:sword`) is a selection rule the classifier resolves, not a literal
            // clip key.
            if (name.indexOf(':') >= 0 || name.indexOf('$') >= 0) {
                continue;
            }
            if (!available.contains(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "the sword chain names clips the model does not ship: " + missing);
    }

    private static JsonObject read(String path) {
        InputStream stream = BuiltinDefaultControllersTest.class.getClassLoader()
            .getResourceAsStream(path);
        assertNotNull(stream, "missing built-in resource: " + path);
        // JsonParser#parseReader arrived in a later gson than the one this platform ships.
        return new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
            .getAsJsonObject();
    }
}
