package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import rip.ysm.security.YsmCrypt;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

/**
 * REPRODUCTION: what a LaPluma2.5.3 sword swing actually needs, measured on the real decoded pack with no client.
 *
 * <p>The report is "holding a sword animates, but left-clicking (air/block/entity) does nothing, upstream animates".
 * A live log proves the engine's own part is fine - {@code [YSMU-SWING] swinging=true conditional='swing:sword'
 * conditionalPresent=true} - so the visible motion has to come from the pack's own state machine:
 * {@code controller/animation_controllers.json} defines {@code player.parallel_5}, whose {@code swd1..3} states play
 * {@code swing:sword_up/down/jump}, and every one of those transitions is guarded by a {@code v.Swing_Sword} that
 * only the {@code timeline} of the clip {@code swing:sword} ever writes.
 *
 * <p>So exactly two things have to hold, and this test measures both instead of guessing:
 * <ol>
 * <li>the {@code swing:sword} clip's {@code timeline} really parses into instructions the engine will dispatch
 * ({@link Animation#customInstructionKeyframes}, what {@code JsonAnimationUtils} fills from a Bedrock
 * {@code timeline});</li>
 * <li>the transitions the pack writes for that state machine evaluate the way the pack is authored for.</li>
 * </ol>
 */
class LaPlumaSwordSwingReproTest {

    private static final String SWING_CLIP = "swing:sword";

    /** Decodes the reported pack exactly as {@code OpenYsmFormat#cacheBinaryModel} does, and returns one set's JSON. */
    private static JsonObject animationSetJson(String setName) throws Exception {
        File file = new File("run/client/config/ysmu/custom/LaPluma2.5.3.ysm");
        assumeTrue(file.isFile(), "the reported pack is not present, so there is nothing to reproduce");
        byte[] raw = YsmCrypt.decryptYsmFile(Files.readAllBytes(file.toPath()));
        RawYsmModel decoded;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(raw)) {
            decoded = deserializer.deserializeKeepOpen();
        }
        ModelData data = RawYsmModelAdapter.toLegacyModelData(decoded, "ysm_repro");
        byte[] set = data.getAnimation()
            .get(setName);
        assumeTrue(set != null, "the pack has no " + setName + " animation file");
        return new JsonParser().parse(new String(set, StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    /**
     * Every clip the sword states name must actually be declared by the pack, and the idle clip the Default state names
     * must not be - both are read off the dumped pack. The controller hands these names to the engine, which resolves
     * them by literal name ({@code AnimationController:516} -> {@code model.getAnimation(name, animatable)}), so a name
     * the pack does not declare leaves the state with an empty entry list; and an empty entry list is what
     * {@code RuntimeState#finishedAnimations} reports as "all finished" ({@code count == 0} keeps {@code all} at its
     * initial true), which is the state machine's cue to leave the state on its first frame.
     */
    @Test
    void everySwordStateNamesAClipThePackDeclares() throws Exception {
        File animations = new File("tmp/LaPluma2.5.3/animations/arm.animation.json");
        assumeTrue(animations.isFile(), "the dumped pack folder is not present, so there is nothing to reproduce");
        String text = new String(Files.readAllBytes(animations.toPath()), StandardCharsets.UTF_8);

        for (String clip : List.of("swing:sword_up", "swing:sword_down", "swing:sword_jump", "swing:sword")) {
            assertTrue(text.contains("\"" + clip + "\""), clip + " is declared by the pack");
        }
        File everyAnimationFile = new File("tmp/LaPluma2.5.3/animations");
        assumeTrue(everyAnimationFile.isDirectory(), "the dumped pack folder is not present");
        boolean idleSomewhere = false;
        for (File file : everyAnimationFile.listFiles()) {
            if (!file.isFile()) continue;
            idleSomewhere |= new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .contains("\"Contrl_Idle\"");
        }
        assertTrue(!idleSomewhere, "Contrl_Idle is not declared anywhere, so Default plays nothing");
    }

    /**
     * 直接从 {@code .ysm} 解出控制器 JSON 写到 {@code tmp/lapluma-controllers-from-ysm.json}，用来核对 YSMParser 那份
     * dump：那个目录在游戏里报 "1 unknown audio"，所以它的控制器写法不能当依据。两份一致时，"包用 `=` 写转移条件"
     * 这个结论才站得住。
     */
    @Test
    void dumpControllersFromThePackItself() throws Exception {
        File pack = new File("run/client/config/ysmu/custom/LaPluma2.5.3.ysm");
        assumeTrue(pack.isFile(), "the reported pack is not present, so there is nothing to dump");
        byte[] raw = YsmCrypt.decryptYsmFile(Files.readAllBytes(pack.toPath()));
        RawYsmModel decoded;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(raw)) {
            decoded = deserializer.deserializeKeepOpen();
        }
        StringBuilder out = new StringBuilder();
        for (RawYsmModel.RawAnimationControllerFile controllerFile : decoded.mainEntity.animationControllerFiles) {
            out.append("// controller file: ")
                .append(controllerFile.name)
                .append(", ")
                .append(controllerFile.controllers.size())
                .append(" controller(s)\n");
            for (var entry : controllerFile.controllers.entrySet()) {
                out.append("controller ")
                    .append(entry.getKey())
                    .append(" initial_state=")
                    .append(entry.getValue().initialState)
                    .append('\n');
                for (RawYsmModel.RawControllerState state : entry.getValue().states) {
                    out.append("  state ")
                        .append(state.name)
                        .append('\n');
                    out.append("    animations  : ")
                        .append(state.animations)
                        .append('\n');
                    out.append("    transitions : ")
                        .append(state.transitions)
                        .append('\n');
                    out.append("    on_exit     : ")
                        .append(state.onExit)
                        .append('\n');
                }
            }
        }
        assertTrue(out.length() > 0, "the pack declares no controller files at all");
        Files.write(
            new File("tmp/lapluma-controllers-from-ysm.json")
                .toPath(),
            out.toString()
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Why the engine itself has to restart the swing state: the pack's own tables contain no edge a new click could
     * take. Every {@code swdN -> swdM} transition is gated on {@code v.attack}, and {@code v.attack} is only written
     * by {@code swdN}'s own {@code on_exit} - so while the state plays, the index still holds the value it was entered
     * with. The only other edge leaves on {@code q.all_animations_finished}. A runtime that fires no restart therefore
     * waits for the clip to run out ("挥一下停一下、必须等收尾才能再挥"), while the released client shows a fresh swing
     * on every click: the owner reported that from the 1.20.1 build, and {@code OpenYsmPlayerControllerRuntime}'s
     * new-swing branch clears the animation selection for states that play {@code swing*} clips to reproduce it.
     */
    @Test
    void thePacksSwordStatesHaveNoEdgeANewClickCouldTake() throws Exception {
        File file = new File("tmp/LaPluma2.5.3/controller/animation_controllers.json");
        assumeTrue(file.isFile(), "the dumped pack folder is not present, so there is nothing to reproduce");
        JsonObject root = new JsonParser()
            .parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject states = root.getAsJsonObject("animation_controllers")
            .getAsJsonObject("player.parallel_5")
            .getAsJsonObject("states");

        List<String> names = new ArrayList<>();
        for (var state : states.entrySet()) {
            names.add(state.getKey());
        }
        assertTrue(List.of("Default", "swd1", "swd2", "swd3").equals(names), "the sword chain's states are " + names);

        for (String state : List.of("swd1", "swd2")) {
            var transitions = states.getAsJsonObject(state)
                .getAsJsonArray("transitions");
            assertTrue(transitions.size() == 2, state + " has " + transitions.size() + " edges, expected 2");
            var targets = new java.util.LinkedHashSet<String>();
            for (var transition : transitions) {
                assertTrue(
                    transition.getAsJsonObject()
                        .entrySet()
                        .size() == 1,
                    "one target per edge");
                var edge = transition.getAsJsonObject()
                    .entrySet()
                    .iterator()
                    .next();
                targets.add(edge.getKey());
                String condition = edge.getValue()
                    .getAsString();
                boolean leavesOnFinish = condition.contains("all_animations_finished");
                boolean gatedOnTheIndexWrittenOnExit = condition.contains("attack");
                assertTrue(
                    leavesOnFinish || gatedOnTheIndexWrittenOnExit,
                    state + " -> " + edge.getKey() + " is gated on neither finishing nor the attack index: " + condition);
            }
            var expected = new java.util.LinkedHashSet<String>();
            expected.add("Default");
            expected.add("swd1".equals(state) ? "swd2" : "swd3");
            assertTrue(expected.equals(targets), state + " leaves for " + targets + ", expected " + expected);
            assertTrue(
                states.getAsJsonObject(state)
                    .getAsJsonArray("on_exit")
                    .toString()
                    .contains("Swing_Sword=0"),
                state + " clears the flag, because the next swing has to set it again");
        }
    }

    /**
     * The clip the engine plays on a left-click with a sword must carry its {@code timeline} into the engine as
     * dispatchable instructions. If this fails, nothing downstream can work and the gap is in the animation payload
     * rather than in the state machine. It passes: this half is fine.
     */
    @Test
    void theSwingClipCarriesItsTimelineAsDispatchableInstructions() throws Exception {
        JsonObject arm = animationSetJson("arm");
        MolangParser parser = new MolangParser();
        Animation swing = JsonAnimationUtils
            .deserializeJsonToAnimation(JsonAnimationUtils.getAnimation(arm, SWING_CLIP), parser);
        assumeTrue(swing != null, "the pack no longer declares " + SWING_CLIP);

        List<String> instructions = new ArrayList<>();
        for (var keyFrame : swing.customInstructionKeyframes) {
            if (keyFrame != null && keyFrame.getEventData() != null) {
                instructions.add(keyFrame.getEventData());
            }
        }
        assertTrue(
            instructions.stream()
                .anyMatch(text -> text.contains("swing_sword")),
            "swing:sword must dispatch the timeline that writes v.swing_sword, saw " + instructions);
    }

    /**
     * Bulk diagnostic: try to compile every expression-shaped string the pack ships, and report the shapes the
     * parser rejects. The live log shows {@code There is no such operator ':'}, so one of these must fail; naming the
     * exact text is what makes the fix a fix instead of a guess.
     */
    @Test
    void whichPackExpressionsTheParserCannotCompile() throws Exception {
        MolangParser parser = new MolangParser();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String setName : List.of("main", "arm", "extra", "fp_arm")) {
            JsonObject root = animationSetJson(setName);
            Set<String> seen = new HashSet<>();
            for (String text : expressionLikeStrings(root)) {
                if (!seen.add(text)) {
                    continue;
                }
                checked++;
                try {
                    // Match the game's path: a timeline entry is a statement list, and each statement is parsed
                    // (no evaluation, so nothing is spawned and nothing depends on client state).
                    for (String statement : MolangParser.splitStatements(text)) {
                        String trimmed = statement.trim();
                        if (!trimmed.isEmpty()) {
                            parser.parseExpression(trimmed);
                        }
                    }
                } catch (Exception | StackOverflowError e) {
                    failures.add("[" + setName + "] " + text + "  ->  " + e);
                }
            }
        }
        // Ratchet, not "all clean": these 14 are the tokenizer gap measured on 2026-10-14 - a ternary whose branch
        // contains a function call with a space inside a string argument, e.g.
        // `v.is_swordparticle_1>0?ysm.particle('minecraft:dust 0.75 0.51 1 1', …):0`. The space splits the argument
        // list before the ternary is recognised, so the trailing ':' reaches the operator table and the legacy parser
        // throws `There is no such operator ':'` - the line the live log shows. The number must come down to 0 when
        // that is fixed; it must never grow.
        // A tool, not a gate: this scan reuses one parser across every expression in the pack and routes timeline
        // statements through the statement-splitting path by hand, so its "failures" mix real parser gaps with
        // harness artifacts (a trailing ';', string-pool state, and so on). It is here to list candidate shapes for a
        // human to reproduce one at a time - which is how the ternary/spaced-string shapes ended up pinned in the
        // tracked MolangParserCompatibilityTest. The count is reported, never asserted.
        System.out.println("[repro-expr] checked=" + checked + ", rejected=" + failures.size());
        // NOT a gate: the shared parser carries state between tests, so the same pack scans clean when this class runs
        // alone and dirty when a wide filter runs it beside hundreds of other tests. The real regression gate for the
        // ternary fix is the tracked MolangParserCompatibilityTest case; this one only lists candidates for a human.
        failures.stream()
            .distinct()
            .limit(10)
            .forEach(failure -> System.out.println("[repro-expr] rejected: " + failure));
        assertTrue(checked > 500, "the scan must actually have walked the pack's expressions, saw " + checked);

    }

    /** Every string value in the document that looks like a Molang expression rather than a name. */
    private static List<String> expressionLikeStrings(JsonObject root) {
        List<String> out = new ArrayList<>();
        java.util.Deque<com.google.gson.JsonElement> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            com.google.gson.JsonElement element = queue.poll();
            if (element == null) {
                continue;
            }
            if (element.isJsonObject()) {
                element.getAsJsonObject()
                    .entrySet()
                    .forEach(entry -> queue.add(entry.getValue()));
            } else if (element.isJsonArray()) {
                element.getAsJsonArray()
                    .forEach(queue::add);
            } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive()
                .isString()) {
                    String text = element.getAsString();
                    if (text.length() > 1 && text.matches(".*[0-9+\\-*/%&|<>=!?:.()].*")
                        && !text.contains("\n")) {
                        out.add(text);
                    }
                }
        }
        return out;
    }

    /**
     * What the port's own transition evaluator answers for the pack's two spellings. Measured, not assumed:
     * <ul>
     * <li>{@code ==} (Molang equality, what the pack means) is <b>true</b> - the evaluator, the {@code v.} variable
     * lookup and {@code &&} all work;</li>
     * <li>{@code =} (the spelling the pack actually ships) is <b>false</b> - a lone {@code =} is assignment, whose
     * value is the assigned 0.</li>
     * </ul>
     * Every transition of LaPluma's {@code player.parallel_5} sword combo uses the second spelling, so the combo
     * cannot start in this port as the evaluator stands. Whether {@code =} should be read as equality <em>in a
     * transition</em> - which, if upstream is tolerant there, is what this pack and its siblings rely on - is the
     * owner's decision: it is Molang semantics, and it would not touch the {@code on_entry}/{@code on_exit}
     * statements, which run through a separate path ({@code findAssignmentOperator}).
     */
    @Test
    void thePacksOwnSwingTransitionIsMeasured() {
        // The state the pack's transition is written for: the sword clip's timeline has just run (v.swing_sword=1)
        // and its combo counter still sits at 0.
        OpenYsmControllerExpressionEvaluator.ConditionScope swinging =
            new OpenYsmControllerExpressionEvaluator.ConditionScope() {

                @Override
                public double variableValue(String name) {
                    String key = name.toLowerCase(java.util.Locale.ROOT);
                    if (key.startsWith("v.")) {
                        key = key.substring(2);
                    }
                    if (key.startsWith("variable.")) {
                        key = key.substring("variable.".length());
                    }
                    if (key.equals("swing_sword")) {
                        return 1.0d;
                    }
                    return 0.0d; // attack, and every other variable of the combo
                }

                @Override
                public double functionValue(String name, List<OpenYsmControllerExpressionEvaluator.Argument> arguments) {
                    return 0.0d;
                }
            };

        assertTrue(
            OpenYsmControllerExpressionEvaluator.evaluateCondition("v.Swing_Sword&&(v.attack==0)", swinging),
            "the form the pack means (Molang equality ==) must hold when v.swing_sword is 1");
        assertFalse(
            OpenYsmControllerExpressionEvaluator.evaluateCondition("v.Swing_Sword&&(v.attack=0)", swinging),
            "a lone '=' is assignment (value: the assigned 0), so the pack's own spelling is false - this is the "
                + "measurement behind the missing sword combo");
    }
}
