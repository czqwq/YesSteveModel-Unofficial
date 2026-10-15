package com.fox.ysmu.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * The {@code /ysm auth} and {@code /ysm model set} surfaces: the dispatch, the refusals, the extractable decisions -
 * upstream's three reset shapes in particular - and the help/tab wiring.
 * <p>
 * The port of upstream's {@code command/sub/AuthCommand.java:28-102} and {@code command/sub/ModelCommand.java:55-98}.
 * As in {@link YsmCommandTest}, every message is observable as a {@link ChatComponentTranslation} key without a client
 * language table.
 * <p>
 * <b>What a unit JVM cannot drive</b>, recorded rather than approximated:
 * <ul>
 * <li>the mutations themselves. They target an {@code EntityPlayerMP}, which cannot be fabricated here
 * ({@code EntityPlayerMP}'s constructor dereferences a live {@code WorldServer.provider} and
 * {@code MinecraftServer.getConfigurationManager()}), and the per-player tables live in EEPs that only exist on such
 * an entity. The storage half is pinned directly ({@code ExtendedAuthModelsTest}), the decision halves below.</li>
 * <li>{@code /ysm model set}'s texture-name validation against a real registry and its per-target apply loop: the
 * registry is the static {@code ServerModelManager.CACHE_NAME_INFO} (empty here, and its reset helper is
 * package-private, so a test in this package must not populate it). The validation predicate itself is driven
 * directly, and the constant-pool check at the end proves the command still calls the write path.</li>
 * <li>{@code /ysm reload}'s re-evaluation of online players ({@code resetSelectionIfUnauthorized}): it needs a server
 * with a player list. The decision it uses is {@link YsmCommand#grantReevaluationResetsSelection} below, and the
 * wiring is pinned by the constant-pool check.</li>
 * </ul>
 */
class YsmAuthCommandTest {

    /** Records what the command sends back, without a client or a world. */
    private static class RecordingSender implements ICommandSender {

        private final List<IChatComponent> messages = new ArrayList<>();

        @Override
        public String getCommandSenderName() {
            return "ysmu-test";
        }

        @Override
        public IChatComponent func_145748_c_() {
            return null;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            this.messages.add(message);
        }

        @Override
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
            return true;
        }

        @Override
        public World getEntityWorld() {
            return null;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }

        private int count() {
            return this.messages.size();
        }

        private String singleKey() {
            if (this.messages.size() != 1) {
                return null;
            }
            IChatComponent message = this.messages.get(0);
            return message instanceof ChatComponentTranslation ? ((ChatComponentTranslation) message).getKey() : null;
        }
    }

    /** A sender that denies every permission level; pins which subcommands the in-body gate exempts. */
    private static final class DenyingSender extends RecordingSender {

        @Override
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // dispatch and refusals
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void authWithoutATargetOrActionPrintsTheUsageInsteadOfGrantingAnything() {
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "auth" });

        assertEquals(1, sender.count());
        assertEquals("commands.yes_steve_model.auth.usage", sender.singleKey());
    }

    @Test
    void anUnknownActionIsRefusedBeforeAnyTargetIsResolved() {
        // "Grant"/"list"/a typo must not be treated as one of upstream's four literals. The refusal is the usage line
        // and it happens before resolveAuthTargets (a unit JVM has no server, so reaching that step would instead
        // throw commands.generic.player.notFound - the difference is what proves the order).
        for (String action : new String[] { "grant", "list", "", "ALL " }) {
            RecordingSender sender = new RecordingSender();

            new YsmCommand().processCommand(sender, new String[] { "auth", "Notch", action });

            assertEquals(
                "commands.yes_steve_model.auth.usage",
                sender.singleKey(),
                "action '" + action + "' is not one of add/remove/all/clear");
        }
    }

    @Test
    void addWithoutAModelIsAUsageErrorRatherThanAPlayerLookup() {
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "auth", "Notch", "add" });

        assertEquals(1, sender.count());
        assertEquals("commands.yes_steve_model.auth.usage", sender.singleKey());
    }

    @Test
    void theActionWordIsMatchedCaseInsensitively() {
        // "ADD" must reach the model branch (which then fails on the missing argument), not the usage line.
        RecordingSender sender = new RecordingSender();

        assertThrowsPlayerRefusal(
            () -> new YsmCommand().processCommand(sender, new String[] { "auth", "@a", "ADD", "default" }));
    }

    @Test
    void authStaysBehindTheCommandsLevelTwoGate() {
        // /ysm auth is not in LEVEL_ZERO_SUBCOMMANDS: upstream gates it at permission 2
        // (AuthCommand.java:29), and this port's inherited gate is that same level. A denied sender never reaches the
        // target resolution.
        for (String[] args : new String[][] { { "auth" }, { "auth", "@a", "all" }, { "auth", "Notch", "add", "x" } }) {
            CommandException exception = assertThrows(
                CommandException.class,
                () -> new YsmCommand().processCommand(new DenyingSender(), args));
            assertEquals("commands.generic.permission", exception.getMessage(), Arrays.toString(args));
        }
    }

    @Test
    void modelSetStaysBehindTheCommandsLevelTwoGate() {
        // upstream ModelCommand.java:35 requires permission 2, and `model` is not in LEVEL_ZERO_SUBCOMMANDS.
        CommandException exception = assertThrows(
            CommandException.class,
            () -> new YsmCommand().processCommand(
                new DenyingSender(),
                new String[] { "model", "set", "@a", "m", "t" }));
        assertEquals("commands.generic.permission", exception.getMessage());
    }

    @Test
    void aValidActionRefusesWithoutAServerInsteadOfThrowingAnNpe() {
        // The selector/name resolution dereferences MinecraftServer.getServer() (CommandBase.java:198); a unit JVM has
        // none, and the port's answer is the same refusal the vanilla helper would give for an unknown player.
        RecordingSender sender = new RecordingSender();

        assertThrowsPlayerRefusal(() -> new YsmCommand().processCommand(sender, new String[] { "auth", "@a", "all" }));
        assertThrowsPlayerRefusal(
            () -> new YsmCommand().processCommand(sender, new String[] { "auth", "Notch", "clear" }));
        assertEquals(0, sender.count(), "a refusal must not also print a receipt");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // upstream's three reset shapes (AuthCommand.java:60-64, :90-94; ServerModelSession.java:137-142)
    // ---------------------------------------------------------------------------------------------------------------

    private static final ResourceLocation MODEL_A = new ResourceLocation("ysmu", "model_a");
    private static final ResourceLocation MODEL_B = new ResourceLocation("ysmu", "model_b");

    @Test
    void removeResetsOnIdEqualityWithNoAccessTest() {
        // Upstream AuthCommand.java:60-64: `hash.equals(selection.getModelId()) && !selection.ignoresGrantsFor(hash)`.
        // The predicate has no access parameter at all, which IS the point: revoking a grant for a model that never
        // needed one still resets a player who is wearing exactly it (the test V-15 in
        // .agent/phase53-auth-verification.md asks for). The old port collapsed this onto "no longer selectable",
        // which made that case a silent no-op.
        assertTrue(YsmCommand.removeResetsSelection(MODEL_A, MODEL_A, false));
    }

    @Test
    void removeLeavesEveryOtherSelectionAndAForcedOneAlone() {
        assertFalse(YsmCommand.removeResetsSelection(MODEL_A, MODEL_B, false), "a different model is not the selection");
        assertFalse(YsmCommand.removeResetsSelection(null, MODEL_A, false), "no selection, nothing to reset");
        assertFalse(YsmCommand.removeResetsSelection(MODEL_A, null, false), "a null id cannot equal anything");
        // Upstream :61's second half, and the same suppression AuthCommand.java:94 has for clear.
        assertFalse(YsmCommand.removeResetsSelection(MODEL_A, MODEL_A, true));
    }

    @Test
    void clearResetsOnlyARestrictedSelection() {
        // Upstream AuthCommand.java:90-94: the current selection's root kind must be SESSION_AUTHORIZED (:91-93) and
        // the selection must not ignore grants (:94). Both halves in one test, as V-16 asks.
        assertTrue(YsmCommand.clearResetsSelection(MODEL_A, true, false), "a restricted wearer is reset");
        assertFalse(YsmCommand.clearResetsSelection(MODEL_A, false, false), "a public model is never reset by clear");
        assertFalse(YsmCommand.clearResetsSelection(null, true, false), "no selection, nothing to reset");
        assertFalse(YsmCommand.clearResetsSelection(MODEL_A, true, true), "a forced selection is left alone");
        assertFalse(
            YsmCommand.clearResetsSelection(MODEL_A, false, true),
            "the two suppressions compose; neither turns the other off");
    }

    @Test
    void aGrantReevaluationResetsAnIneligibleSelection() {
        // The third shape, ServerModelSession.setGrants:137-142, reached from SessionProtocolHandler.refreshGrants
        // (:374-386) - i.e. from any grant re-publication, not only the two command actions. /ysm reload is this
        // port's re-publication point (a reload can move a model into config/ysmu/auth).
        assertTrue(YsmCommand.grantReevaluationResetsSelection(MODEL_A, false, false));
        assertFalse(YsmCommand.grantReevaluationResetsSelection(MODEL_A, true, false), "still selectable, keep it");
        assertFalse(YsmCommand.grantReevaluationResetsSelection(MODEL_A, false, true), "forced selections survive");
        assertFalse(YsmCommand.grantReevaluationResetsSelection(null, false, false), "no selection, nothing to reset");
    }

    @Test
    void theThreeShapesDifferExactlyWhereUpstreamsDo() {
        // remove (id equality) vs clear (restricted only): the same "public model is the selection" input gives
        // different answers, and that asymmetry is upstream's, not a port accident.
        assertTrue(YsmCommand.removeResetsSelection(MODEL_A, MODEL_A, false));
        assertFalse(YsmCommand.clearResetsSelection(MODEL_A, false, false));
        // remove (with an access-blind test) vs setGrants (with a selectability test): remove resets even though the
        // model is still selectable, setGrants does not.
        assertTrue(YsmCommand.removeResetsSelection(MODEL_A, MODEL_A, false));
        assertFalse(YsmCommand.grantReevaluationResetsSelection(MODEL_A, true, false));
        // ... and both are suppressed by the same forced-selection flag.
        assertFalse(YsmCommand.removeResetsSelection(MODEL_A, MODEL_A, true));
        assertFalse(YsmCommand.grantReevaluationResetsSelection(MODEL_A, false, true));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the forced-selection state the command writes (upstream ModelInfoCapability:25-58)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aCommandSelectionStoresTheFlagATargetRefusalConsults() {
        // ExtendedModelInfo is a plain object here (no world needed) - its player field is never read by these paths.
        ResourceLocation model = new ResourceLocation("ysmu", "restricted");
        ResourceLocation texture = ModelIdUtil.getSubModelId(model, "default.png");
        ExtendedModelInfo info = new ExtendedModelInfo(null);

        info.setCommandSelection(model, texture, true);

        assertEquals(model, info.getModelId());
        assertEquals(texture, info.getSelectTexture());
        assertTrue(info.ignoresGrantsFor(model), "the flag is bound to this model");
        assertFalse(info.ignoresGrantsFor(new ResourceLocation("ysmu", "other")));
        assertFalse(info.ignoresGrantsFor(null));
    }

    @Test
    void anOrdinarySelectionKeepsTheFlagOnlyForTheSameModel() {
        // Upstream ModelInfoCapability.java:25-37: setModelAndTexture keeps ignoreGrants only while the model id is
        // unchanged, so a client-side selection of another model drops an operator's exception.
        ResourceLocation model = new ResourceLocation("ysmu", "restricted");
        ResourceLocation other = new ResourceLocation("ysmu", "other");
        ResourceLocation texture = ModelIdUtil.getSubModelId(model, "default.png");
        ExtendedModelInfo info = new ExtendedModelInfo(null);
        info.setCommandSelection(model, texture, true);

        info.setModelAndTexture(model, texture);
        assertTrue(info.ignoresGrantsFor(model), "the same model keeps the forced exception");

        info.setModelAndTexture(other, texture);
        assertFalse(info.ignoresGrantsFor(model), "a different model drops it");
        assertFalse(info.ignoresGrantsFor(other));

        info.clearIgnoreGrants();
        assertFalse(info.ignoresGrantsFor(other));
    }

    @Test
    void theCommandStillCallsTheWritePathAndTheGrantPublisher() throws IOException {
        // The compiled command must reference the forced-selection write (setCommandSelection), the per-player grant
        // publisher (ModelGrantSync.send) and the reload-wide one (ModelGrantSync.sendAll). Without this, the
        // predicates above would keep passing while the command stopped writing what they describe. Same technique as
        // ModelAuthorizationTest's call-site check: the constant pool of the compiled class.
        String pool = new String(readClassBytes(YsmCommand.class), StandardCharsets.ISO_8859_1);

        assertTrue(
            pool.contains("com/fox/ysmu/network/ModelGrantSync"),
            "YsmCommand must publish the grant set through network/ModelGrantSync");
        assertTrue(pool.contains("sendAll"), "a reload must republish the authorization state for every player");
        assertTrue(pool.contains("setCommandSelection"), "the operator path must write the forced-selection flag");
        assertTrue(
            pool.contains("com/fox/ysmu/api/ModelAuthorization"),
            "the command must decide through api/ModelAuthorization");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the extractable decisions of /ysm auth
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theActionIsTheThirdArgumentLowerCasedAndEmptyWhenMissing() {
        assertEquals("add", YsmCommand.parseAuthAction(new String[] { "auth", "@a", "add", "x" }));
        assertEquals("clear", YsmCommand.parseAuthAction(new String[] { "auth", "@a", "CLEAR" }));
        assertEquals("", YsmCommand.parseAuthAction(new String[] { "auth", "@a" }));
        assertEquals("", YsmCommand.parseAuthAction(new String[] { "auth" }));
    }

    @Test
    void onlyUpstreamsFourLiteralsAreKnownActions() {
        for (String known : new String[] { "add", "remove", "all", "clear" }) {
            assertTrue(YsmCommand.isKnownAuthAction(known), known);
        }
        for (String unknown : new String[] { "", "ADD", "grant", "all ", "clearx" }) {
            assertFalse(YsmCommand.isKnownAuthAction(unknown), unknown);
        }
    }

    @Test
    void aModelArgumentIsTheRemainderRejoinedAndOneQuotePairIsStripped() {
        assertEquals("default", YsmCommand.parseAuthModelArgument(new String[] { "auth", "@a", "add", "default" }));
        assertEquals(
            "wine_fox/01 taisho",
            YsmCommand.parseAuthModelArgument(new String[] { "auth", "@a", "add", "wine_fox/01", "taisho" }),
            "a model name the dispatcher split at spaces is re-joined");
        assertEquals(
            "example model",
            YsmCommand.parseAuthModelArgument(new String[] { "auth", "@a", "add", "\"example model\"" }));
    }

    @Test
    void theFeedbackKeysFollowTheActions() {
        assertEquals("commands.yes_steve_model.auth.add.done", YsmCommand.authFeedbackKey("add"));
        assertEquals("commands.yes_steve_model.auth.remove.done", YsmCommand.authFeedbackKey("remove"));
        assertEquals("commands.yes_steve_model.auth.all.done", YsmCommand.authFeedbackKey("all"));
        assertEquals("commands.yes_steve_model.auth.clear.done", YsmCommand.authFeedbackKey("clear"));
    }

    @Test
    void modelSuggestionsAreTheReadableNamesOfTheRegisteredModelsInAStableOrder() {
        String encoded = ModelIdUtil.getInternalModelId("example model");
        Set<String> registered = new HashSet<>(Arrays.asList("wine_fox/01_x", encoded, "default"));

        List<String> suggestions = YsmCommand.authModelSuggestions(registered);

        assertEquals(3, suggestions.size());
        assertEquals("default", suggestions.get(0), "sorted, so the completion list does not jump between calls");
        assertEquals("example model", suggestions.get(1), "an encoded id is offered as the name an operator typed");
        assertEquals("wine_fox/01_x", suggestions.get(2));
        assertNotNull(YsmCommand.authModelSuggestions());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // /ysm model set (upstream ModelCommand.java:41-98)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void modelWithoutSetOrWithoutItsArgumentsIsAUsageError() {
        // Upstream's tree is `model set <targets> <model> <texture> [bool]` (:41-50); every shorter or longer shape is
        // a parse failure there, and the usage line is this port's equivalent. The refusals happen before any target
        // lookup (a unit JVM has no server, so reaching resolveAuthTargets would throw playerNotFound instead).
        String[][] badUsages = {
            { "model" },
            { "model", "bogus" },
            { "model", "set" },
            { "model", "set", "@a" },
            { "model", "set", "@a", "m" },
            { "model", "set", "@a", "m", "t", "maybe" },
            { "model", "set", "@a", "m", "t", "true", "extra" } };

        for (String[] args : badUsages) {
            RecordingSender sender = new RecordingSender();

            new YsmCommand().processCommand(sender, args);

            assertEquals("commands.yes_steve_model.model.usage", sender.singleKey(), Arrays.toString(args));
        }
    }

    @Test
    void modelOnlyDispatchesUpstreamsSetLiteral() {
        // reload/errors/disable are upstream literals this port does not carry (see YsmCommand.MODEL_ACTIONS): a
        // command that reached them would be the invented stub the ExecPlan forbids.
        assertTrue(YsmCommand.isKnownModelAction("set"));
        assertTrue(YsmCommand.isKnownModelAction("SET"));
        for (String unsupported : new String[] { "reload", "errors", "disable", "", "sets" }) {
            assertFalse(YsmCommand.isKnownModelAction(unsupported), unsupported);
        }
    }

    @Test
    void aWellFormedModelSetFailsOnTheUnknownPathRatherThanOnTheTargets() {
        // The argument checks and the model resolution run before the target lookup: with an empty registry the
        // refusal is upstream's "Unknown or ambiguous model path" wording (ModelCommand.java:61), not the
        // playerNotFound a target lookup would throw in a unit JVM.
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "model", "set", "@a", "not_a_model", "default.png" });

        assertEquals(1, sender.count());
        assertEquals("commands.yes_steve_model.model.set.unknown_model", sender.singleKey());
    }

    @Test
    void aRestrictedTargetIsSkippedWithoutAbortingTheSelector() {
        // Upstream ModelCommand.java:82-88: the refusal is inside the per-target lambda and ends in `return`, so one
        // ungranted player does not stop the model from reaching the others (V-21).
        assertTrue(YsmCommand.targetRefusesModelSet(false, true, false), "restricted and not granted -> skip this one");
        assertFalse(YsmCommand.targetRefusesModelSet(false, true, true), "granted -> apply");
        assertFalse(YsmCommand.targetRefusesModelSet(false, false, false), "public -> apply");
        assertFalse(YsmCommand.targetRefusesModelSet(true, true, false), "ignore_auth -> apply");
    }

    @Test
    void theModelSetReceiptOnlyReportsAnActualApplication() {
        // The receipt is this port's addition (upstream answers nothing on success), so a run in which every target
        // was refused must not append a "0 player(s)" line on top of the refusal lines it already printed - silence
        // there is upstream's own shape. The predicate is what the command branches on.
        assertFalse(YsmCommand.reportsModelSetReceipt(0), "nothing applied -> no receipt");
        assertTrue(YsmCommand.reportsModelSetReceipt(1));
        assertTrue(YsmCommand.reportsModelSetReceipt(3));
    }

    @Test
    void theTextureArgumentIsOneOfTheModelsOwnNames() {
        // Upstream ModelCommand.java:71-74.
        Set<String> names = new HashSet<>(Arrays.asList("default.png", "magic.png"));

        assertTrue(YsmCommand.isKnownTexture("default.png", names));
        assertTrue(YsmCommand.isKnownTexture("magic.png", names));
        assertFalse(YsmCommand.isKnownTexture("default", names), "the name must match the model's list exactly");
        assertFalse(YsmCommand.isKnownTexture("", names));
        assertFalse(YsmCommand.isKnownTexture("default.png", null), "no registry entry -> nothing to validate against");
    }

    @Test
    void theDashTextureSpellingIsRecognisedAndRefused() {
        // Upstream ModelCommand.java:66 resolves "-" to the model's declared default texture. The port cannot: that
        // field is client-side state (ClientModelManager.DECLARED_DEFAULT_TEXTURES), and the server keeps only the
        // texture-name set. Recognising it is what lets the command say so instead of reporting an unknown texture.
        assertTrue(YsmCommand.isDeclaredDefaultTextureArgument("-"));
        assertFalse(YsmCommand.isDeclaredDefaultTextureArgument("default.png"));
        assertFalse(YsmCommand.isDeclaredDefaultTextureArgument(""));
    }

    @Test
    void ignoreAuthIsABooleanWordSoABadWordIsAUsageError() {
        // Upstream's BoolArgumentType.bool() (ModelCommand.java:48) reads true/false only.
        assertTrue(YsmCommand.isBooleanWord("true"));
        assertTrue(YsmCommand.isBooleanWord("TRUE"));
        assertTrue(YsmCommand.isBooleanWord("false"));
        assertFalse(YsmCommand.isBooleanWord("yes"));
        assertFalse(YsmCommand.isBooleanWord("1"));
        assertFalse(YsmCommand.isBooleanWord(""));
    }

    @Test
    void aQuotedSingleArgumentIsUnwrappedAndEverythingElseIsPassedThrough() {
        // 1.7.10 hands quotes through instead of consuming them (CommandHandler.java:38).
        assertEquals("default", YsmCommand.stripOneQuotePair("\"default\""));
        assertEquals("mid\"dle", YsmCommand.stripOneQuotePair("mid\"dle"), "only a surrounding pair is stripped");
        assertEquals("\"open", YsmCommand.stripOneQuotePair("\"open"));
        assertEquals("", YsmCommand.stripOneQuotePair(""));
        assertNull(YsmCommand.stripOneQuotePair(null));
    }

    @Test
    void theTextureSlotSuggestsTheModelsOwnNamesInAStableOrder() {
        List<String> suggestions = YsmCommand.textureSuggestions(new HashSet<>(Arrays.asList("b.png", "a.png")));

        assertEquals(2, suggestions.size());
        assertEquals("a.png", suggestions.get(0));
        assertEquals("b.png", suggestions.get(1));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // help and tab completion
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void authHasAHelpTopicWithUsageAndDescription() {
        assertTrue(YsmCommand.isKnownHelpTopic("auth"), "the new subcommand must be documented by /ysm help");

        RecordingSender sender = new RecordingSender();
        new YsmCommand().processCommand(sender, new String[] { "help", "auth" });

        assertEquals(3, sender.count());
        assertEquals("commands.yes_steve_model.help.detail.title", ((ChatComponentTranslation) sender.messages.get(0)).getKey());
        assertEquals(
            "commands.yes_steve_model.help.detail.auth.usage",
            ((ChatComponentTranslation) sender.messages.get(1)).getKey());
        assertEquals(
            "commands.yes_steve_model.help.detail.auth.desc",
            ((ChatComponentTranslation) sender.messages.get(2)).getKey());
    }

    @Test
    void modelSetHasAHelpTopicWithUsageAndDescription() {
        assertTrue(YsmCommand.isKnownHelpTopic("model"), "/ysm model must be documented by /ysm help");

        RecordingSender sender = new RecordingSender();
        new YsmCommand().processCommand(sender, new String[] { "help", "model" });

        assertEquals(3, sender.count());
        assertEquals(
            "commands.yes_steve_model.help.detail.model.usage",
            ((ChatComponentTranslation) sender.messages.get(1)).getKey());
        assertEquals(
            "commands.yes_steve_model.help.detail.model.desc",
            ((ChatComponentTranslation) sender.messages.get(2)).getKey());
    }

    @Test
    void tabCompletionOffersAuthItsActionsAndModelItsSet() {
        List<?> top = new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "au" });
        assertEquals(1, top.size());
        assertEquals("auth", top.get(0));

        List<?> actions = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "auth", "@a", "" });
        assertEquals(4, actions.size());
        for (String action : new String[] { "add", "remove", "all", "clear" }) {
            assertTrue(actions.contains(action), action + " must be offered");
        }

        List<?> filtered = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "auth", "@a", "re" });
        assertEquals(1, filtered.size());
        assertEquals("remove", filtered.get(0));

        // The model slot: one suggestion per registered model (the registry is empty in a clean unit JVM).
        List<?> models = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "auth", "@a", "add", "" });
        assertNotNull(models);
        assertEquals(com.fox.ysmu.model.ServerModelManager.CACHE_NAME_INFO.size(), models.size());
    }

    @Test
    void tabCompletionOffersTheModelSubcommandAndItsArgumentSlots() {
        List<?> actions = new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "model", "" });
        assertEquals(1, actions.size());
        assertEquals("set", actions.get(0), "upstream's reload/errors/disable are not ported");

        // The model slot, at the same position upstream suggests ALL_MODELS (ModelCommand.java:44).
        List<?> models = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "model", "set", "@a", "" });
        assertNotNull(models);
        assertEquals(com.fox.ysmu.model.ServerModelManager.CACHE_NAME_INFO.size(), models.size());

        // The texture slot needs the model to resolve; with an empty registry there is nothing to suggest.
        assertNull(
            new YsmCommand()
                .addTabCompletionOptions(new RecordingSender(), new String[] { "model", "set", "@a", "m", "" }));

        // The optional boolean slot offers both words (upstream BoolArgumentType.bool()).
        List<?> bools = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "model", "set", "@a", "m", "t", "" });
        assertEquals(2, bools.size());
        assertTrue(bools.contains("true"));
        assertTrue(bools.contains("false"));
    }

    @Test
    void tabCompletionForAuthAndModelTargetsDegradesWithoutAServerInsteadOfFailing() {
        // Same guard as /ysm reset: completion runs on the server thread and MinecraftServer.getServer() is null in a
        // unit JVM, so the port returns null (no suggestions) instead of dereferencing it.
        assertNull(new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "auth", "N" }));
        assertNull(
            new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "model", "set", "N" }));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the chat keys this command family emits (the %s-only rule and both language files)
    // ---------------------------------------------------------------------------------------------------------------

    /** Every key {@code processAuth}/{@code processModel} and their reset paths can send. */
    private static final String[] CHAT_KEYS = { "commands.yes_steve_model.auth.usage",
        "commands.yes_steve_model.auth.add.done", "commands.yes_steve_model.auth.remove.done",
        "commands.yes_steve_model.auth.all.done", "commands.yes_steve_model.auth.clear.done",
        "commands.yes_steve_model.auth.unknown_model", "message.yes_steve_model.auth.denied",
        "message.yes_steve_model.auth.reset", "commands.yes_steve_model.model.usage",
        "commands.yes_steve_model.model.set.unknown_model", "commands.yes_steve_model.model.set.unknown_texture",
        "commands.yes_steve_model.model.set.not_authorized",
        "commands.yes_steve_model.model.set.texture_dash_unavailable", "commands.yes_steve_model.model.set.done",
        "commands.yes_steve_model.help.line.auth", "commands.yes_steve_model.help.line.model",
        "commands.yes_steve_model.help.detail.auth.usage", "commands.yes_steve_model.help.detail.auth.desc",
        "commands.yes_steve_model.help.detail.model.usage", "commands.yes_steve_model.help.detail.model.desc" };

    @Test
    void everyChatKeyExistsInBothLanguagesAndUsesOnlySConversions() throws Exception {
        // Two silent failures this pins, both named in AGENTS.md:
        // - a key missing from one language file renders as the raw key in that language (the "keep new translation
        //   keys in sync" rule);
        // - a conversion other than %s/%% is refused by ChatComponentTranslation#initializeFromFormat
        //   (ChatComponentTranslation.java:121-124) *lazily*, from getFormattedText/getUnformattedTextForChat, i.e.
        //   while the chat is drawn - so a %d key never renders a line at all instead of failing loudly. Reading the
        //   language files is the only way to check this without a client language table.
        Path lang = Paths.get("src", "main", "resources", "assets", "ysmu", "lang");
        for (String file : List.of("en_US.lang", "zh_CN.lang")) {
            String content = "\n" + new String(Files.readAllBytes(lang.resolve(file)), StandardCharsets.UTF_8);
            for (String key : CHAT_KEYS) {
                String marker = "\n" + key + "=";
                int start = content.indexOf(marker);
                assertTrue(start >= 0, file + " is missing " + key);
                int end = content.indexOf('\n', start + marker.length());
                String value = content.substring(start + marker.length(), end < 0 ? content.length() : end);
                for (int i = 0; i < value.length(); i++) {
                    if (value.charAt(i) != '%') {
                        continue;
                    }
                    char conversion = i + 1 < value.length() ? value.charAt(i + 1) : '\0';
                    assertTrue(
                        conversion == 's' || conversion == '%',
                        file + ": " + key + " uses a conversion 1.7.10 refuses: %" + conversion);
                    i++;
                }
            }
        }
    }

    /** Asserts the command refused with the port's shared "no such player/selector" refusal. */
    private static void assertThrowsPlayerRefusal(Runnable command) {
        CommandException exception = assertThrows(CommandException.class, command::run);
        assertEquals("commands.generic.player.notFound", exception.getMessage());
    }

    /** The compiled bytes of {@code type}, straight from the test classpath. */
    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = type.getName()
            .replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader()
            .getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be readable from the classpath");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
