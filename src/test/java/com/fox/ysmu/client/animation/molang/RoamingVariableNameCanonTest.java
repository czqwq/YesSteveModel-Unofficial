package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.RemoteAnimationVariables;

/**
 * One rule for a Molang variable name, and the two registration paths that both have to obey it.
 * <p>
 * A pack declares its settings as {@code v.roaming.<name>} and reads them from expressions like
 * {@code v.roaming.C==0?1.2:0}. The parser lower-cases a whole expression before parsing it, so the name a value is
 * <em>stored</em> under has to be lower-cased as well; when it was not, {@code v.roaming.C} was stored as written and
 * looked up as {@code v.roaming.c}, read as the neutral 0, and every such expression took its first branch - the
 * pack's own toggles silently did nothing. Upstream canonicalises identifiers the same way, in
 * {@code com.elfmcys.ysm.api.molang.MolangNames#identifier}.
 * <p>
 * Two paths register these names and both must agree: the wire path, where a synced value arrives with the pack's
 * own spelling and goes through {@link RemoteAnimationVariables#normalize}, and the parser's own registry, reached by
 * {@code setValue} and by a pack's own {@code v.roaming.x=...} assignment. This pins both.
 */
class RoamingVariableNameCanonTest {

    @Test
    void theRuleLowerCasesAndFoldsTheInterchangeablePrefixes() {
        assertEquals("v.roaming.c", MolangParser.canonicalVariableName("v.roaming.C"));
        assertEquals("v.roaming.c", MolangParser.canonicalVariableName("V.Roaming.C"));
        assertEquals("v.roaming.c", MolangParser.canonicalVariableName("v.roaming.c"));
        assertEquals("query.life_time", MolangParser.canonicalVariableName("q.life_time"));
        assertEquals("query.life_time", MolangParser.canonicalVariableName("Q.Life_Time"));
        assertEquals("v.x", MolangParser.canonicalVariableName("variable.X"));
    }

    @Test
    void theWirePathStoresTheNameTheParserWillLookUp() {
        // A bare member name is promoted into the roaming namespace, exactly as RemoteAnimationVariables documents.
        assertEquals("v.roaming.c", RemoteAnimationVariables.normalize("C"));
        assertEquals("v.roaming.c", RemoteAnimationVariables.normalize("v.roaming.C"));
        // An explicit variable prefix is kept as a variable, not re-homed under roaming.
        assertEquals("v.c", RemoteAnimationVariables.normalize("variable.C"));
        assertEquals("v.roaming.player_size", RemoteAnimationVariables.normalize("player_size"));
        // Degenerate inputs keep the namespace they had before, minus the case.
        assertEquals("v.roaming.", RemoteAnimationVariables.normalize(""));
        assertEquals("v.roaming.", RemoteAnimationVariables.normalize(null));
    }

    @Test
    void theTwoSpellingsResolveToTheSameVariable() throws Exception {
        MolangParser parser = new MolangParser();
        parser.setValue("v.roaming.C", () -> 4.0D);

        // A pack may write the switch in either case; Molang is case-insensitive, so both must read the same value.
        assertEquals(4.0D, parser.parseExpression("v.roaming.C")
            .get(), 0.0D);
        assertEquals(4.0D, parser.parseExpression("v.roaming.c")
            .get(), 0.0D);
        assertEquals(7.0D, parser.parseExpression("v.roaming.C==4?7:0")
            .get(), 0.0D);
        assertEquals(7.0D, parser.parseExpression("v.roaming.c==4?7:0")
            .get(), 0.0D);
    }

    @Test
    void aPackAssigningInOneCaseIsReadableInTheOther() throws Exception {
        MolangParser parser = new MolangParser();

        // This is the shape a pack uses in a timeline: "v.roaming.b=v.roaming.a;" - an assignment through the parser,
        // not through the wire path.
        parser.parseExpression("v.roaming.Switch=3;")
            .get();

        assertEquals(3.0D, parser.parseExpression("v.roaming.switch")
            .get(), 0.0D);
        assertEquals(1.0D, parser.parseExpression("v.roaming.Switch==3?1:0")
            .get(), 0.0D);
    }
}
