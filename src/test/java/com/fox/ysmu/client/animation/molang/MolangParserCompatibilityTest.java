package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.MathBuilder;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.animation.AnimationRegister;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * Engine parser behaviour that OpenYSM packs depend on and that the 1.7.10 port got wrong. These live here (rather
 * than in the engine repository) because YSMU is where the failing model packs are exercised; the engine jar is on
 * this test classpath.
 */
class MolangParserCompatibilityTest {

    /** Records the name it was called under, so the {@code q.} alias can be observed. */
    public static class EchoFunction extends Function {

        static String calledAs;

        public EchoFunction(IValue[] values, String name) throws Exception {
            super(values, name);
            calledAs = name;
        }

        @Override
        public double get() {
            return getArg(0) + 1;
        }
    }

    /**
     * The vector bone query as this engine implements it: one class behind every registered name
     * ({@code ysm.bone_rot}, {@code ysm.bone_rot_x}, …), answering the axis the name ends in and falling back to x for
     * the unsuffixed form. Records the name so the {@code ).x} rewrite can be observed, and returns the bone's
     * interned name id so the bone argument is observable too.
     */
    public static class VectorBoneFunction extends Function {

        static String calledAs;

        public VectorBoneFunction(IValue[] values, String name) throws Exception {
            super(values, name);
            calledAs = name;
        }

        @Override
        public int getRequiredArguments() {
            return 1;
        }

        @Override
        public double get() {
            return getArg(0);
        }
    }

    /**
     * The vector bone queries only work with the host table installed: the {@code _x}/{@code _y}/{@code _z} names
     * come from {@code MolangParser.doCoreRemaps}, and the unsuffixed names from
     * {@code AnimationRegister.registerFunctions} through the registrar hook the constructor runs. The same
     * installation {@code ClientProxy#init} performs in game.
     */
    private static MolangParser parserWithHostFunctions() {
        com.fox.ysmu.client.animation.AnimationRegister.registerMolangHooks();
        return new MolangParser();
    }

    /**
     * A ternary whose branch calls a function with a space inside a string argument must parse.
     *
     * <p>LaPluma2.5.3 ships fourteen such expressions, e.g.
     * {@code v.is_swordparticle_1>0?ysm.particle('minecraft:dust 0.75 0.51 1 1', ysm.bone_pivot_abs(...).x/21, …):0},
     * and the live client log shows the legacy parser rejecting them with
     * {@code There is no such operator ':'} - the space splits the argument list before the ternary is recognised, so
     * the trailing {@code ':'} reaches the operator table. Upstream has a real lexer whose string tokens carry spaces
     * ({@code molang/lexer/MolangLexerImpl.java:271} lexes {@code ':'} as {@code COLON} only outside a literal), so
     * it never sees this; the port's character-level splitter must instead keep a quoted run together.
     */
    @Test
    void ternaryBranchesWithSpacedStringArgumentsParse() throws Exception {
        MolangParser parser = parserWithHostFunctions();
        List<String> shapes = List.of("ysm.particle('minecraft:dust 0.75 0.51 1 1', 1, 2)",
            "v.a?1:0",
            "v.a?ysm.particle('minecraft:dust 0.75 0.51 1 1', 1, 2):0",
            "(v.a||v.b)&&v.c?ysm.particle('minecraft:dust 0.75 0.51 1 1', 1, 2):0",
            "v.is_swordparticle_1>0?ysm.particle('minecraft:dust 0.75 0.51 1 1', math.max(1, 2)/21, 3):0",
            // A colon inside a quoted string argument is the shape the pack actually ships, and a splitter that
            // looks for ':' without tracking quotes would cut the literal in half here.
            "ctrl.hold('mainhand','#minecraft:swords')?1:0",
            "(ctrl.hold('mainhand','#minecraft:swords')||ctrl.swing('mainhand','#minecraft:swords'))&&v.a"
                + "?ysm.particle('minecraft:dust 0.0 0.6 0.7 1', 1, 2):0",
            // The expression the live log finally named, plus its minimal shapes: the else branch begins with a unary
            // minus in front of a parenthesised call, `:-(math.clamp(...))`. That is the one thing the shapes above do
            // not cover - there the minus sits in front of a bare call inside a longer expression.
            "(query.vertical_speed>0)?(math.clamp((5*query.vertical_speed),0,60)):-(math.clamp((5*query.vertical_speed),-60,0))",
            "v.a?1:-(2)",
            "v.a?1:-(math.clamp(1,0,2))",
            "v.a?-(math.clamp(1,0,2)):0",
            "-(math.clamp(1,0,2))",
            "v.a?1:(-2)",
            "v.a?1:-2");
        for (String shape : shapes) {
            try {
                assertNotNull(parser.parseExpression(shape), "must parse: " + shape);
            } catch (Throwable failure) {
                // Re-throw with the reason attached: MolangException's message names only the whole expression, and
                // the interesting part is what the parser disliked about it.
                throw new AssertionError("rejected: " + shape + "\n  -> " + failure, failure);
            }
        }
    }

    @Test
    void qPrefixResolvesToQueryFunctions() throws Exception {
        MolangParser parser = new MolangParser();
        parser.functions.put("query.echo", EchoFunction.class);

        IValue byQuery = parser.parseExpression("query.echo(1)");
        assertEquals(2.0D, byQuery.get(), 0.0D);

        // `q.` is normalised for variables already; function names used to be looked up verbatim, so a pack writing
        // q.position(...) never saw the host's query.position.
        IValue byShortPrefix = parser.parseExpression("q.echo(1)");
        assertEquals(2.0D, byShortPrefix.get(), 0.0D);
        assertEquals("query.echo", EchoFunction.calledAs);
    }

    @Test
    void mathAliasesPointAtRealFunctions() {
        MolangParser parser = new MolangParser();

        for (String name : new String[] { "math.random", "math.random_integer", "math.die_roll",
            "math.die_roll_integer", "math.hermite_blend", "math.clamp", "math.mod" }) {
            assertNotNull(parser.functions.get(name), name + " must resolve to a function class");
        }
        assertFalse(parser.functions.containsKey("random_integer"), "the wrong source name must not be remapped");
        assertFalse(parser.functions.containsKey("hermite_blend"), "the wrong source name must not be remapped");
        // math.pi is a constant, not a function
        assertFalse(parser.functions.containsKey("math.pi"));
    }

    @Test
    void mathRandomIntegerEvaluates() throws Exception {
        MolangParser parser = new MolangParser();

        double value = parser.parseExpression("math.random_integer(1, 5)")
            .get();

        assertTrue(value >= 1 && value <= 5, "expected 1..5, got " + value);
    }

    @Test
    void remapIgnoresMissingSources() {
        MolangParser parser = new MolangParser();

        parser.remap("definitely_not_registered", "math.nope");

        assertFalse(
            parser.functions.containsKey("math.nope"),
            "a missing source must not leave a null entry that later reports 'couldn't be found'");
    }

    @Test
    void unknownFunctionsStillFail() {
        MolangParser parser = new MolangParser();

        assertThrows(Exception.class, () -> parser.parseExpression("nope(1)"));
    }

    @Test
    void equalityOperatorParses() throws Exception {
        // `==` used to throw StringIndexOutOfBoundsException inside breakdownChars.
        MolangParser parser = new MolangParser();

        assertEquals(1.0D, parser.parseExpression("4==4")
            .get(), 0.0D);
        assertEquals(0.0D, parser.parseExpression("4==5")
            .get(), 0.0D);
        assertEquals(1.0D, parser.parseExpression("4!=5")
            .get(), 0.0D);
        assertEquals(1.0D, parser.parseExpression("2<=2")
            .get(), 0.0D);
    }

    @Test
    void nullCoalescingOperatorParses() throws Exception {
        // Pack animation channels use Bedrock's `??`: `"scale": "v.player_size??1"` must scale by 1 until the player
        // picks a size. Without the operator the two '?' characters stayed in the token buffer, parseSymbols saw an
        // empty symbol ("Index -1 out of bounds for length 0"), the channel evaluated to 0 and the whole model
        // collapsed to a point - invisible in game while its geometry, texture and registration were all fine.
        MolangParser parser = new MolangParser();

        assertEquals(1.0D, parser.parseExpression("1??2")
            .get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("0??2")
            .get(), 0.0D);
        // An unset v.* variable reads 0, so the fallback applies.
        assertEquals(1.0D, parser.parseExpression("v.player_size??1")
            .get(), 0.0D);
        // Once the model's own config form sets it, the variable wins.
        parser.parseExpression("v.player_size=2.5")
            .get();
        assertEquals(2.5D, parser.parseExpression("v.player_size??1")
            .get(), 0.0D);
        // The same expression through the animation-file entry point.
        assertEquals(
            1.0D,
            parser.parseJson(new com.google.gson.JsonPrimitive("v.never_set_size??1"))
                .get(),
            0.0D);
    }

    @Test
    void nullCoalesceConsultsTheExplicitVariableLookupHook() throws Exception {
        // 引擎声明的宿主钩子：`MolangParser.ExplicitVariableLookup`（`MolangParser.java:80-84`）。`??` 的
        // **本意**（`MolangParser.java:666-669` 自己的注释）是"左边的变量被**显式设置过**就用它 —— 显式设为 0
        // 也算 —— 否则用右边"。左边没有钩子时那条分支永远不成立（`userSet` 恒 false，
        // `MolangParser.java:685-690`），于是 `a??b` 退化成 `l != 0 ? l : r`，显式设为 0 的值被当成"从未设置"。
        //
        // 这个用例钉的是**引擎自己那一半**，用两个桩证明"钩子的返回值真的决定分支"；至于宿主有没有把这个
        // 钩子接到一个真值来源上，见 `.agent/phase41-controller-path-values.md` 行 30（本仓没有可接的来源，
        // 所以它是**未接线**，不是"已修好"）。
        MolangParser parser = new MolangParser();
        // 探针变量：名字一次性的，避免与别的用例共用这张静态表时互撞（`parseExpression` 会整体小写化，
        // 所以下面表达式里的写法是小写）。
        parser.register(new LazyVariable("v.zz_coalesce_probe", 0));
        parser.parseExpression("v.zz_coalesce_probe=0")
            .get();

        try {
            // 钩子说"一个名字都没被显式设置过"：左边为 0 时取右边。
            MolangParser.explicitVariableLookup = name -> false;
            assertEquals(2.0D, parser.parseExpression("v.zz_coalesce_probe??2")
                .get(), 0.0D);
            // 左边非 0 时仍然取左边 —— 这一半在接线前后都必须成立。
            parser.parseExpression("v.zz_coalesce_probe=3")
                .get();
            // 探针名字里含 "zz_coalesce_probe"，所以这里必须让钩子说"没有"才是"未被设置"的形态。
            assertEquals(3.0D, parser.parseExpression("v.zz_coalesce_probe??2")
                .get(), 0.0D);
            // 变量被改回 0：一个诚实的"没设置过"来源也不能让非 0 分支凭空成立。
            parser.parseExpression("v.zz_coalesce_probe=0")
                .get();
            assertEquals(2.0D, parser.parseExpression("v.zz_coalesce_probe??2")
                .get(), 0.0D);

            // 钩子说"这个名字被显式设置过"（哪怕值是 0）：`??` 必须答 0，而不是右边。
            MolangParser.explicitVariableLookup = name -> "v.zz_coalesce_probe".equals(name);
            assertEquals(
                0.0D,
                parser.parseExpression("v.zz_coalesce_probe??2")
                    .get(),
                0.0D,
                "an explicitly-set 0 must win over the fallback - that is the whole point of the hook");
            // 而且钩子收到的必须正是**左边表达式文本**（`MolangParser.java:688` 传的是 `leftExpr`，
            // 已经 trim 过），不是变量的规范名、也不是整条表达式。
            MolangParser.explicitVariableLookup = name -> {
                assertEquals("v.zz_coalesce_probe", name, "the hook must receive the left-hand expression text");
                return true;
            };
            assertEquals(0.0D, parser.parseExpression("v.zz_coalesce_probe ?? 2")
                .get(), 0.0D);

            // 左边不是变量（一个整串表达式）时钩子照旧被问；一个总说"没有"的钩子必须让它退回旧语义。
            MolangParser.explicitVariableLookup = name -> false;
            assertEquals(1.0D, parser.parseExpression("1??2")
                .get(), 0.0D);
            assertEquals(2.0D, parser.parseExpression("0??2")
                .get(), 0.0D);
        } finally {
            // 钩子是**整个 JVM 一份**的静态字段，本用例必须自己复位：`nullCoalescingOperatorParses`
            // 与本类其余用例都跑在"未接线"的语义上。
            MolangParser.explicitVariableLookup = null;
        }

        // 复位之后回到未接线的语义（与 nullCoalescingOperatorParses 一致）。
        assertEquals(2.0D, parser.parseExpression("0??2")
            .get(), 0.0D);
    }

    @Test
    void colonlessConditionalOperatorParses() throws Exception {
        // YSM/Bedrock packs also write `cond?value` without a colon (meaning `cond ? value : 0`), and they nest it
        // inside a full ternary. parseSymbols used to answer any expression that still contained a bare '?' after
        // tryTernary with the constant 0, so every one of these channels silently became 0: 艾莲·乔's cover preview
        // pinned the whole model at "-4+(!v.fm?50)" = -4 instead of sliding it to 46 for the first two seconds, and
        // walk/run Root positions plus the tac/arm swing scales were wrong the same way.
        MolangParser parser = new MolangParser();

        // `!v.fm?50`: an unset variable is 0, so !0 == 1 and the value is taken.
        assertEquals(50.0D, parser.parseExpression("!v.fm?50")
            .get(), 0.0D);
        assertEquals(46.0D, parser.parseExpression("-4+(!v.fm?50)")
            .get(), 0.0D);
        // Once the model's own initialiser (parallel1's timeline: `v.roaming.player_size=v.roaming.player_size
        // ?v.roaming.player_size:1;`) has run, the same expression answers 0.
        parser.parseExpression("v.fm=1")
            .get();
        assertEquals(0.0D, parser.parseExpression("!v.fm?50")
            .get(), 0.0D);
        assertEquals(-4.0D, parser.parseExpression("-4+(!v.fm?50)")
            .get(), 0.0D);

        // The colon-less form nested inside a real ternary, and the parenthesised chain from `walk`.
        MolangParser second = new MolangParser();
        second.parseExpression("v.hold=1")
            .get();
        second.parseExpression("v.speed=1")
            .get();
        assertEquals(-0.1D, second.parseExpression("v.hold?(v.speed?-0.1:-0.05)")
            .get(), 0.0D);
        second.parseExpression("v.hold=0")
            .get();
        assertEquals(0.0D, second.parseExpression("v.hold?(v.speed?-0.1:-0.05)")
            .get(), 0.0D);
        assertEquals(
            0.2D,
            second.parseExpression("(!v.leftbow&&!v.rightbow?(0.2))")
                .get(),
            0.0D);
        // Comparison binding: `v.attack!=5?2` must be `(attack != 5) ? 2 : 0`.
        assertEquals(2.0D, second.parseExpression("v.attack!=5?2")
            .get(), 0.0D);

        // A well-formed ternary is still claimed by tryTernary, not by the binary operator.
        assertEquals(2.0D, second.parseExpression("1?2:3")
            .get(), 0.0D);
        assertEquals(3.0D, second.parseExpression("0?2:3")
            .get(), 0.0D);
    }

    @Test
    void ternaryImmediatelyAfterAssignmentParses() throws Exception {
        // Real timeline instructions from a pack whose `t=0` block failed with
        // "Couldn't parse '<lower-cased>' expression!". Every failing instruction is a ternary at the top level
        // right after `=`, and the mechanism is in breakdownChars: when it merges the `==`, the assignment `=`
        // that happens to be the last symbol matches the character being merged and is deleted, so parseOneLine
        // (:393-396) no longer recognises the assignment and tryTernary reads `[v.x, v.y, ==, 0]` as the
        // condition - two adjacent operands with no operator, which reaches operationForOperator(symbols.get(-1)).
        // The parenthesised ternaries in the same block were never affected, which is the empirical split pinned
        // here in both directions.
        MolangParser parser = new MolangParser();

        // `v.roaming.player_size = v.roaming.player_size==0?1:v.roaming.player_size`; both branches.
        parser.register(new LazyVariable("v.roaming.player_size", 0));
        assertEquals(
            1.0D,
            parser.parseExpression("v.roaming.player_size = v.roaming.player_size==0?1:v.roaming.player_size")
                .get(),
            0.0D);
        assertEquals(
            1.0D,
            parser.parseExpression("v.roaming.player_size")
                .get(),
            0.0D,
            "condition true, so the assignment must have taken the 1 branch");
        parser.register(new LazyVariable("v.roaming.player_size", 2));
        assertEquals(
            2.0D,
            parser.parseExpression("v.roaming.player_size = v.roaming.player_size==0?1:v.roaming.player_size")
                .get(),
            0.0D);
        assertEquals(
            2.0D,
            parser.parseExpression("v.roaming.player_size")
                .get(),
            0.0D,
            "condition false, so the else branch keeps the value");

        // The same shape with spaces, and the `?` glued to a bare `query.` variable rather than a call.
        parser.register(new LazyVariable("query.body_x_rotation", 42));
        parser.register(new LazyVariable("v.right_handsmove", 0));
        assertEquals(
            42.0D,
            parser.parseExpression(
                "v.Right_body_x_rotation = v.Right_handsmove == 0 ?query.body_x_rotation:v.Right_handsmove")
                .get(),
            0.0D);
        assertEquals(
            42.0D,
            parser.parseExpression("v.right_body_x_rotation")
                .get(),
            0.0D);
        parser.register(new LazyVariable("v.right_handsmove", 3));
        assertEquals(
            3.0D,
            parser.parseExpression(
                "v.Right_body_x_rotation = v.Right_handsmove == 0 ?query.body_x_rotation:v.Right_handsmove")
                .get(),
            0.0D);

        // The half that already worked, from the same `t=0` block: the ternary sits inside parentheses
        // (`v.ground_speed_count=((…) ? 1 : -1)*…`, its condition and multiplier elided in the log), so the
        // assignment is recognised before the `==` merge can matter. It must not regress; both branches pinned.
        parser.register(new LazyVariable("query.ground_speed", 0));
        assertEquals(
            -4.0D,
            parser.parseExpression("v.ground_speed_count=((query.ground_speed>0.1) ? 1 : -1)*4")
                .get(),
            0.0D);
        assertEquals(
            -4.0D,
            parser.parseExpression("v.ground_speed_count")
                .get(),
            0.0D);
        parser.register(new LazyVariable("query.ground_speed", 1));
        assertEquals(
            4.0D,
            parser.parseExpression("v.ground_speed_count=((query.ground_speed>0.1) ? 1 : -1)*4")
                .get(),
            0.0D);

        // `!=` after an assignment was never broken by the merge (its merged first character is `!`, not `=`),
        // so it stays pinned too - a future change to that merge must not take it out.
        parser.register(new LazyVariable("v.merge_a", 1));
        parser.register(new LazyVariable("v.merge_b", 0));
        assertEquals(
            1.0D,
            parser.parseExpression("v.merge_neq = v.merge_a!=v.merge_b?1:2")
                .get(),
            0.0D);
    }

    @Test
    void bracePlaceholdersEvaluateToZeroInsteadOfKillingTheChannel() throws Exception {
        // 艾莲·乔1.4.0 writes these in the position channels of its run and walk animations - 302 expressions in all -
        // and nothing in that pack defines `data3`. '{' is an illegal character for the tokenizer, so before this the
        // whole channel failed to parse and the animation lost the bones it drove: that model walked and ran without
        // the motion those channels carry while all of its numeric channels played normally.
        MolangParser parser = new MolangParser();
        parser.parseExpression("v.hold=1")
            .get();
        parser.parseExpression("v.speed=1")
            .get();
        assertEquals(
            0.24D,
            parser.parseExpression("(!v.hold?0) + (v.hold?(v.speed?0.24:{data3}))")
                .get(),
            1.0E-9D);
        // The placeholder is the neutral value, so the branch that uses it contributes nothing.
        parser.parseExpression("v.speed=0")
            .get();
        assertEquals(
            0.0D,
            parser.parseExpression("(!v.hold?0) + (v.hold?(v.speed?0.24:{data3}))")
                .get(),
            1.0E-9D);
        // Several placeholders in one expression still parse, each contributing the neutral value.
        assertEquals(
            1.0D,
            parser.parseExpression("1+{data0}+{data1}+{data2}")
                .get(),
            1.0E-9D);
    }

    @Test
    void booleanLiteralsBehaveAsOneAndZero() throws Exception {        // AnimationRegister registers them in the client; the raw parser answering 0 for `true` is what made
        // `v.north=true` assign 0.
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("true", 1));
        parser.register(new LazyVariable("false", 0));

        assertEquals(1.0D, parser.parseExpression("true")
            .get(), 0.0D);
        assertEquals(0.0D, parser.parseExpression("false")
            .get(), 0.0D);
    }

    @Test
    void ordinaryExpressionsAndAssignmentsStillWork() throws Exception {
        MolangParser parser = new MolangParser();

        assertEquals(3.0D, parser.parseExpression("1+2")
            .get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("math.max(1,2)")
            .get(), 0.0D);
        assertEquals(3.0D, parser.parseExpression("v.tmp=3")
            .get(), 0.0D);
        assertEquals(6.0D, parser.parseExpression("v.tmp*2")
            .get(), 0.0D);
    }

    @Test
    void vectorBoneFunctionIsABareStatement() throws Exception {
        // The `.x` member access is not the only way a pack uses a vector bone query, and the rewrite in
        // `MolangParser.rewriteVectorFunction` only fires when the call's own `)` is followed by `).<axis>`. The bare
        // form is registered directly, and answers the axis the function falls back to - upstream hands back a struct
        // here and this parser cannot, so the honest answer is the x component, not a parse failure.
        MolangParser parser = parserWithHostFunctions();
        parser.functions.put("ysm.bone_rot", VectorBoneFunction.class);
        VectorBoneFunction.calledAs = null;

        int bone = MolangStringPool.intern("bare");
        assertEquals(bone, parser.parseExpression("ysm.bone_rot('bare')")
            .get(), 0.0D);
        assertEquals("ysm.bone_rot", VectorBoneFunction.calledAs, "the bare name must be what the function is called as");

        // And the whole instruction as a timeline carries it, assigned to a variable and read back - the shape that
        // used to be dropped whole.
        assertEquals(
            bone,
            parser.parseExpression("v.bare_statement=ysm.bone_rot('bare');v.bare_statement")
                .get(),
            0.0D);
    }

    @Test
    void vectorBoneFunctionIsAnArgumentToAnotherFunction() throws Exception {
        // `v.bv=math.max(ysm.bone_pivot_abs('Root'),ysm.bone_pivot_abs('AllBody'));` - the log's first parse failure.
        // Neither call has a `).x`, so the rewrite leaves both alone; before the bare names were registered this threw
        // `Function 'ysm.bone_pivot_abs' couldn't be found!` and the whole timeline instruction was dropped.
        MolangParser parser = parserWithHostFunctions();
        parser.functions.put("ysm.bone_pivot_abs", VectorBoneFunction.class);

        int root = MolangStringPool.intern("Root");
        int allBody = MolangStringPool.intern("AllBody");
        double expected = Math.max(root, allBody);

        assertEquals(
            expected,
            parser.parseExpression("v.bv=math.max(ysm.bone_pivot_abs('Root'),ysm.bone_pivot_abs('AllBody'));v.bv")
                .get(),
            0.0D);
        // The same instruction without the assignment, and with the font's own spacing.
        assertEquals(
            expected,
            parser.parseExpression("math.max(ysm.bone_pivot_abs('Root'), ysm.bone_pivot_abs('AllBody'))")
                .get(),
            0.0D);
    }

    @Test
    void vectorBoneFunctionMemberAccessInsideAnArgumentList() throws Exception {
        // The log's second shape: the `.x` access sits **inside** a larger argument list
        // (`(ysm.bone_pivot_abs('RightHandPTC').x)/25`), where the call's own `)` is still immediately followed by
        // `).x`, so the rewrite does reach it - the instruction failed because a *second*, bare-looking name in the
        // same expression was unregistered, and because the logged form of its z argument is `-(…)/25`, a unary minus
        // in front of a parenthesised group (see `unaryMinusBeforeAGroupParses`). Both positions are pinned here
        // because one expression uses both.
        MolangParser parser = parserWithHostFunctions();
        // Both names the rewrite can pick: `).x` becomes `ysm.bone_pivot_abs_x`, while the bare argument the same
        // instruction writes stays `ysm.bone_pivot_abs`. The host's real class is registered for all of them, so the
        // stub has to cover all of them too, or a rewritten call silently reaches the real function - which answers 0
        // (no bone) with no render frame open.
        parser.functions.put("ysm.bone_pivot_abs", VectorBoneFunction.class);
        for (String axis : new String[] { "x", "y", "z" }) {
            parser.functions.put("ysm.bone_pivot_abs_" + axis, VectorBoneFunction.class);
        }

        int hand = MolangStringPool.intern("RightHandPTC");
        assertEquals(
            hand / 25.0D,
            parser.parseExpression("(ysm.bone_pivot_abs('RightHandPTC').x)/25")
                .get(),
            0.0D);
        assertEquals("ysm.bone_pivot_abs_x", VectorBoneFunction.calledAs, "the rewrite must pick the x component name");

        // The same read for the y and z components - the y one with the logged instruction's own neighbour
        // arithmetic, the z one with the `-(…)/25` unary-minus shape.
        assertEquals(
            hand,
            parser.parseExpression("ysm.bone_pivot_abs('RightHandPTC').y")
                .get(),
            0.0D);
        assertEquals("ysm.bone_pivot_abs_y", VectorBoneFunction.calledAs);
        assertEquals(
            (hand + 3.0D) / 25.0D,
            parser.parseExpression("(ysm.bone_pivot_abs('RightHandPTC').y+3)/25")
                .get(),
            0.0D);
        assertEquals(
            -hand / 25.0D,
            parser.parseExpression("-(ysm.bone_pivot_abs('RightHandPTC').z)/25")
                .get(),
            0.0D,
            "the unary minus applies to the group, so the result is negative");
        assertEquals("ysm.bone_pivot_abs_z", VectorBoneFunction.calledAs);
    }

    @Test
    void unaryMinusBeforeAGroupParses() throws Exception {
        // `-(…)` followed by `*` or `/`. `breakdownChars` folds a minus into the token buffer for every follower
        // except `(`, and the `(` branch pushes the buffered `-` as its own symbol, so the symbol list is
        // `[-, [1], /, 25]`. `parseSymbols` then split at the `/`, recursed into `[-, [1]]`, found no operator
        // (lastOp == -1) and threw IndexOutOfBoundsException from `operationForOperator(symbols.get(-1))` - the whole
        // expression died. `-(1)` alone only worked because it has no binary operator; `-(1)+25` / `-(1)-(2)` parsed
        // but with every following additive term pulled inside the negation, which the assertions below pin. The logged
        // form `-(ysm.bone_pivot_abs('RightHandPTC').z)/25` is exactly the failing shape.
        MolangParser parser = parserWithHostFunctions();

        assertEquals(-0.04D, parser.parseExpression("-(1)/25")
            .get(), 0.0D);
        assertEquals(
            -0.04D,
            parser.parseExpression("-(1)/25 + 0")
                .get(),
            1.0E-9D,
            "the surrounding arithmetic must keep working too");
        // Precedence: the unary minus binds to its group, not to the rest of the expression.
        assertEquals(-25.0D, parser.parseExpression("-(1)*25")
            .get(), 0.0D);
        assertEquals(-20.0D, parser.parseExpression("10*-(2)")
            .get(), 0.0D);
        assertEquals(-1.5D, parser.parseExpression("2*-(3)/4")
            .get(), 0.0D);
        // ... and "the rest of the expression" includes every following `+`/`-` term, which the negation used to
        // swallow: `-(A)+B` was read as `-((A)+B)`. `*`/`/` hid it (associativity), and a `+ 0` term hid it too
        // (the assertion above), so only a non-zero additive term shows the sign flip.
        assertEquals(24.0D, parser.parseExpression("-(1)+25")
            .get(), 0.0D);
        assertEquals(-26.0D, parser.parseExpression("-(1)-25")
            .get(), 0.0D);
        assertEquals(-17.0D, parser.parseExpression("10*-(2)+3")
            .get(), 0.0D);
        // Subtraction and negative literals keep their meanings - a bare `-` is unary only in operand position, so
        // the binary cases must not change.
        assertEquals(7.0D, parser.parseExpression("10-3")
            .get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("4-2")
            .get(), 0.0D);
        assertEquals(-6.0D, parser.parseExpression("2*-3")
            .get(), 0.0D);
        assertEquals(-0.04D, parser.parseExpression("-1/25")
            .get(), 0.0D);
        // The human-readable spacing still tokenises the same way.
        assertEquals(7.0D, parser.parseExpression("10 - 3")
            .get(), 0.0D);
    }

    /**
     * Wine Fox's jump clip verbatim ({@code wine_fox/06_hanfu}'s {@code jump} animation; 04_magic and the rest of the
     * pack are written the same way): a leading {@code -(…)} followed by a ternary term. That ternary is what made this
     * look like a ternary bug - the negation used to pull the whole {@code +(has_offhand?-50:-80)} term inside itself,
     * so the forearm evaluated to {@code +88°} where the pack asked for {@code -72°} and pointed backwards for the whole
     * airborne phase. The renderer's X-axis flip turned that into the {@code -88°} / {@code -95.9°} the live client log
     * records for {@code LeftForeArm} / {@code RightForeArm}. {@code default} and the packs that render fine were
     * unaffected because their jump clips are numeric keyframes, which never reach this parser.
     */
    @Test
    void wineFoxJumpClipNegationBindsToItsGroup() throws Exception {
        MolangParser jump = parserWithHostFunctions();
        jump.parseExpression("v.zz_jump_vs=-3.1")
            .get();
        jump.parseExpression("v.zz_jump_offhand=0")
            .get();
        // `(vs/|vs|*(1-e^(-0.1|vs|))*30)` is -8 at vs = -3.1, so the clip's `-(…)` makes it +8.
        double negatedTerm = 30 * (1 - Math.exp(-0.1 * 3.1));
        String jumpForeArm = "-(v.zz_jump_vs/math.abs(v.zz_jump_vs)*(1-math.exp(-0.1*math.abs(v.zz_jump_vs)))*30)"
            + "+(v.zz_jump_offhand?-50:-80)";
        assertEquals(
            negatedTerm - 80,
            jump.parseExpression(jumpForeArm)
                .get(),
            1.0E-9D,
            "the leading minus must not swallow the ternary term");
        jump.parseExpression("v.zz_jump_offhand=1")
            .get();
        assertEquals(
            negatedTerm - 50,
            jump.parseExpression(jumpForeArm)
                .get(),
            1.0E-9D,
            "the offhand branch of the ternary stays selected");
        // The upper arm's Z channel of the same clip has no leading `-(…)`, so it was never affected and the pack's own
        // sign survives - which is why only the forearms pointed the wrong way.
        assertEquals(
            -20.0D - negatedTerm,
            jump.parseExpression(
                "-20+(v.zz_jump_vs/math.abs(v.zz_jump_vs)*(1-math.exp(-0.1*math.abs(v.zz_jump_vs)))*30)")
                .get(),
            1.0E-9D,
            "a leading negative literal is a merged token and keeps its meaning");
    }

    @Test
    void supportedVectorNamesAreNotReportedAsUnregistered() throws Exception {
        // `MolangInstructionExecutor` reports a function it cannot answer. It must not report one this parser
        // supports through the rewrite (the old probe compared the literal name against `parser.functions` *before*
        // parsing, so it announced `ysm.bone_rot`/`ysm.bone_scale`/`ysm.bone_pivot_abs` as "ignored" and then ran the
        // same instruction).
        MolangParser parser = parserWithHostFunctions();

        assertTrue(
            MolangInstructionExecutor
                .unregisteredFunctions(parser, "v.Right_handsmove = query.is_sprinting||query.is_jumping?90:"
                    + "ysm.bone_rot('RightFingersContrl').x")
                .isEmpty(),
            "a vector name the rewrite handles is supported, not missing");
        assertTrue(
            MolangInstructionExecutor
                .unregisteredFunctions(
                    parser,
                    "v.RightEye_Wink_Contrl_P1dot=0.1*(v.LEyes_K3*math.max(v.RightEye_Wink_Contrl,"
                        + "(-ysm.bone_scale('RightEyesControl').y)*-1)-v.RightEye_Wink_Contrl_P1)"
                        + "/v.LEyes_K2")
                .isEmpty());
        assertTrue(
            MolangInstructionExecutor
                .unregisteredFunctions(
                    parser,
                    "v.bv=math.max(ysm.bone_pivot_abs('Root'),ysm.bone_pivot_abs('AllBody'))")
                .isEmpty(),
            "the bare vector argument is registered too, so it is not a missing name either");
    }

    @Test
    void genuinelyUnregisteredFunctionsAreStillReported() throws Exception {
        // The check stays - it is what tells a pack author which instruction was dropped. A name nothing registers
        // must still be reported, or the warning has been silenced instead of corrected.
        MolangParser parser = parserWithHostFunctions();

        assertEquals(
            java.util.Collections.singleton("ysm.no_such_function"),
            MolangInstructionExecutor
                .unregisteredFunctions(parser, "ysm.no_such_function(1)+ysm.bone_rot('Arm').x"));
        // An abbreviated query is normalised by `createFunction`, so a literal probe would call it missing; the
        // registered `query.*` name is what decides.
        assertTrue(
            MolangInstructionExecutor.unregisteredFunctions(parser, "q.debug_output('lvl', 3)")
                .isEmpty());
    }

    @Test
    void topLevelSemicolonsStillSplitStatements() throws Exception {
        // The `;`-separated list kept working before the brace work and must keep working after it.
        List<String> statements = MolangParser.splitStatements("v.x = 1; v.y = 2");
        assertEquals(2, statements.size());
        assertEquals("v.x = 1", statements.get(0).trim());
        assertEquals("v.y = 2", statements.get(1).trim());

        MolangParser parser = new MolangParser();
        // The list's value is the last statement's, exactly as before.
        assertEquals(
            2.0D,
            parser.parseExpression("v.x = 1; v.y = 2")
                .get(),
            0.0D);
        assertEquals(1.0D, parser.parseExpression("v.x").get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("v.y").get(), 0.0D);
    }

    @Test
    void bracedBlockIsOneStatementAndRunsTheTakenBranchInOrder() throws Exception {
        // LaPluma2.5.3's timeline writes Bedrock execution scopes (`cond?{stmt;stmt}:{stmt}`, and the colon-less
        // `cond?{stmt;}`). Splitting on `;` at any depth cut them into fragments starting with `}` or `}:{` or `?{`,
        // which is the client log's "Failed to execute OpenYSM timeline Molang instruction '}:{v.OutRigged_Path_X =
        // ...'". A block is upstream's ExecutionScopeExpression, i.e. one statement unit.
        String instruction = "v.x = cond?{v.a=1;v.b=2}:{v.a=3}";
        List<String> statements = MolangParser.splitStatements(instruction);
        assertEquals(1, statements.size(), "a braced block must stay one statement unit");
        assertEquals(instruction, statements.get(0).trim());

        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("cond", 1));
        // The block's value is its last statement (upstream ExpressionEvaluatorImpl.java:241-257), and the statements
        // before it ran in order - so both `v.a=1` and `v.b=2` happened, and the assignment saw 2.
        assertEquals(2.0D, parser.parseExpression(instruction).get(), 0.0D);
        assertEquals(1.0D, parser.parseExpression("v.a").get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("v.b").get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("v.x").get(), 0.0D);

        // Only the taken arm runs (upstream ExpressionEvaluatorImpl.java:393-399): the else block writes v.a=3 and
        // leaves v.b alone.
        parser.register(new LazyVariable("cond", 0));
        assertEquals(3.0D, parser.parseExpression(instruction).get(), 0.0D);
        assertEquals(3.0D, parser.parseExpression("v.a").get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("v.b").get(), 0.0D, "the untaken block must not run");

        // The statement form a pack actually writes: no assignment, and the block holds 90-character lists of them.
        MolangParser bare = new MolangParser();
        bare.parseExpression("v.cond=1")
            .get();
        assertEquals(
            9.0D,
            bare.parseExpression("v.cond?{v.a=9;}").get(),
            0.0D,
            "a colon-less `cond?{...}` is `cond?{...}:0`, so its value is the block's last statement");
        assertEquals(9.0D, bare.parseExpression("v.a").get(), 0.0D);
        bare.parseExpression("v.cond=0")
            .get();
        assertEquals(0.0D, bare.parseExpression("v.cond?{v.a=8;}").get(), 0.0D);
        assertEquals(9.0D, bare.parseExpression("v.a").get(), 0.0D, "the false branch runs nothing");
    }

    @Test
    @Timeout(5)
    void blocksSpanningJoinedTimelineElementsAreStillOneUnit() throws Exception {
        // A pack may open a block in one timeline element and close it in a later one. `JsonAnimationUtils.
        // instructionString` joins the elements with ";\n", so the joined text really does carry `?{;` and `;;`
        // inside the block, and the element that closes it contributes the final `};`.
        String spanning = "v.cond?{;v.b=4;;v.c=5;};v.d=6";
        List<String> parts = MolangParser.splitStatements(spanning);
        assertEquals(2, parts.size());
        assertEquals("v.cond?{;v.b=4;;v.c=5;}", parts.get(0).trim());
        assertEquals("v.d=6", parts.get(1).trim());

        MolangParser parser = new MolangParser();
        parser.parseExpression("v.cond=1")
            .get();
        assertEquals(6.0D, parser.parseExpression(spanning).get(), 0.0D);
        assertEquals(4.0D, parser.parseExpression("v.b").get(), 0.0D);
        assertEquals(5.0D, parser.parseExpression("v.c").get(), 0.0D);
        assertEquals(6.0D, parser.parseExpression("v.d").get(), 0.0D);
    }

    @Test
    @Timeout(5)
    void unbalancedBracesNeitherHangNorEscapeTheSplitter() throws Exception {
        // An extra `}` is one statement that fails to parse. It must not push the depth negative and swallow every
        // later `;`, which would turn a whole timeline into a single unparsable statement.
        List<String> extraClose = MolangParser.splitStatements("v.a=1};v.b=2");
        assertEquals(2, extraClose.size());
        assertEquals("v.a=1}", extraClose.get(0).trim());

        // An unclosed `{` keeps the rest as one statement: no exception from the splitter, and (with the timeout) no
        // loop. The `{data3}` placeholder path is unaffected - see the test above.
        List<String> unclosed = MolangParser.splitStatements("v.a={v.b=1;v.c=2");
        assertEquals(1, unclosed.size());

        // Such a statement is still a plain syntax error - the same MolangException every failing instruction already
        // goes through, which MolangInstructionExecutor catches per statement. Not a hang, and not an Error.
        MolangParser parser = new MolangParser();
        assertThrows(MolangException.class, () -> parser.parseExpression("v.a=1}"));
        assertThrows(MolangException.class, () -> parser.parseExpression("v.a={v.b=1;"));
    }

    /**
     * The arrow's host as these tests drive it: it records what it was handed and answers a value that makes the
     * enclosing expression's parenthesisation observable ({@code 100 + right}), so a test can tell
     * {@code (a->b)*2} from {@code a->(b*2)}.
     */
    public static final class RecordingStructHost implements MathBuilder.StructAccessHost {

        double leftSeen = Double.NaN;
        boolean rightEvaluated = false;

        @Override
        public IValue access(MathBuilder builder, IValue left, IValue right) {
            return new IValue() {

                @Override
                public double get() {
                    leftSeen = left.get();
                    rightEvaluated = true;
                    return 100.0D + right.get();
                }
            };
        }
    }

    /**
     * {@code ->} (the arrow expression / struct access) is upstream's highest-precedence binary operator, evaluated by
     * running its right side in a child evaluator whose subject is the left side's value
     * ({@code tmp/YesSteveModel-dev-1.20 .../molang/parser/MolangParserImpl.java:313},
     * {@code runtime/ExpressionEvaluatorImpl.java:58-69}) - that is how {@code ysm.projectile_owner->query.health}
     * reaches the shooter. This engine's values are plain doubles, so the two halves belong to the host
     * ({@code MathBuilder.StructAccessHost}); these four tests pin the parse side, the precedence, the honest failure
     * when no host is installed, and the expression the variables wiki actually documents.
     */
    @Test
    void arrowExpressionIsOneTokenAndAsksTheHostForBothSides() throws Exception {
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("ysm.owner", 7));
        RecordingStructHost host = new RecordingStructHost();
        MathBuilder.StructAccessHost previous = MathBuilder.structAccessHost;
        MathBuilder.structAccessHost = host;
        try {
            IValue value = parser.parseExpression("ysm.owner->query.health");
            assertEquals(100.0D, value.get(), 0.0D);
            assertEquals(7.0D, host.leftSeen, 0.0D);
            assertTrue(host.rightEvaluated, "the right side must be evaluated by the host");
        } finally {
            MathBuilder.structAccessHost = previous;
        }
    }

    @Test
    void arrowBindsTighterThanEveryOtherBinaryOperator() throws Exception {
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("ysm.owner", 7));
        MathBuilder.StructAccessHost previous = MathBuilder.structAccessHost;
        MathBuilder.structAccessHost = new RecordingStructHost();
        try {
            // (ysm.owner->query.health) * 2 == (100 + 0) * 2. If the arrow bound looser than '*', the host would be
            // handed `query.health*2` and answer 100 instead - which is what upstream's parser must not do either.
            assertEquals(200.0D, parser.parseExpression("ysm.owner->query.health*2")
                .get(), 0.0D);
            // And tighter than '+': (100 + 0) + 1.
            assertEquals(101.0D, parser.parseExpression("ysm.owner->query.health+1")
                .get(), 0.0D);
        } finally {
            MathBuilder.structAccessHost = previous;
        }
    }

    @Test
    void arrowWithoutAHostFailsWithTheMissingHostAsTheReason() {
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("ysm.owner", 7));
        MathBuilder.StructAccessHost previous = MathBuilder.structAccessHost;
        MathBuilder.structAccessHost = null;
        try {
            // Parse-time failure, and the message names what is missing. The alternative - quietly answering one of
            // the two sides - is the shape this repository treats as a defect: a pack would see a plausible number
            // instead of a broken expression.
            Exception failure = assertThrows(Exception.class, () -> parser.parse("ysm.owner->query.health"));
            assertTrue(
                String.valueOf(failure.getMessage())
                    .contains("host"),
                "the reason must name the missing host, got: " + failure.getMessage());
        } finally {
            MathBuilder.structAccessHost = previous;
        }
    }

    @Test
    void theDocumentedProjectileOwnerArrowExpressionParsesAndEvaluates() throws Exception {
        // 变量 wiki 的原句（tmp/wiki/molang_misc.md:148，同一句也出现在 log_120.md:225）：
        //   v.flame_level ?? (v.flame_level = ysm.projectile_owner->ysm.equipped_enchantment_level('Mainhand',
        //   'minecraft:flame'))
        // 升级前 `->` 连分词都过不去，整条 timeline 指令被丢掉。这里用**真实的**宿主与函数表跑一遍：
        // 句柄 0（没有登记过 owner）对应上游的"左侧为 null"，因此 ?? 取右侧、结果为 0——不抛异常是这条用例的重点。
        MathBuilder.StructAccessHost previous = MathBuilder.structAccessHost;
        try {
            AnimationRegister.registerMolangHooks();
            MolangParser parser = new MolangParser();
            IValue value = parser.parseExpression(
                "v.flame_level ?? (v.flame_level = ysm.projectile_owner->ysm.equipped_enchantment_level('Mainhand', 'minecraft:flame'))");
            assertNotNull(value);
            assertEquals(0.0D, value.get(), 0.0D);
        } finally {
            MathBuilder.structAccessHost = previous;
        }
    }
}
