package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

/**
 * The rule that decides which models draw the player's own Minecraft skin instead of a texture of their own - the
 * port's half of upstream's {@code VanillaPlayerRenderEvent} ({@code tmp/YesSteveModel-dev-1.20/src/main/java/
 * com/elfmcys/ysm/client/event/VanillaPlayerRenderEvent.java:19-50}).
 *
 * <p>The listener itself cannot be driven here: it needs an {@code AbstractClientPlayer}, which needs a world. What is
 * pinned is the predicate it branches on - {@link ClientEventHandler#isVanillaPlayer(ResourceLocation)} - because that
 * is where the defect was: it compared the id's path to {@code "steve"}/{@code "alex"} while the built-in models live
 * under {@code misc/} and their ids are hex-encoded, so it never matched and the two models drew their own pack
 * texture (the one the model-selection preview shows) instead of the player's skin.
 *
 * <p>Every id here is one the client really registers: {@link ModelIdUtil#getInternalModelId} is the same encoder
 * {@code OpenYsmFormat}/{@code YsmFormat} use to name a model, and the two hex strings are the ids the live client
 * logs for the built-in {@code 1_alex} / {@code 2_steve} directories.
 */
class VanillaPlayerSkinModelTest {

    private static ResourceLocation builtIn(String relativePath) {
        return new ResourceLocation(ysmu.MODID, ModelIdUtil.getInternalModelId(relativePath));
    }

    /** The encoder really does produce the ids the client logs, so the cases below are the live shapes. */
    @Test
    void theBuiltInVanillaModelsCarryTheHexEncodedIdsTheClientRegisters() {
        assertEquals("_name_6d6973632f325f7374657665", ModelIdUtil.getInternalModelId("misc/2_steve"));
        assertEquals("_name_6d6973632f315f616c6578", ModelIdUtil.getInternalModelId("misc/1_alex"));
        assertEquals("misc/2_steve", ModelIdUtil.getModelDisplayName(builtIn("misc/2_steve")));
        assertEquals("misc/1_alex", ModelIdUtil.getModelDisplayName(builtIn("misc/1_alex")));
    }

    /** Upstream's two names, in this port's id encoding: the case the released predicate never matched. */
    @Test
    void theEncodedBuiltInSteveAndAlexAreVanillaPlayers() {
        assertTrue(ClientEventHandler.isVanillaPlayer(builtIn("misc/2_steve")));
        assertTrue(ClientEventHandler.isVanillaPlayer(builtIn("misc/1_alex")));
    }

    /**
     * The same models as they were published before the built-in pack moved under {@code misc/} - old installs still
     * have them in {@code config/ysmu/custom}, where the id has no folder and needs no encoding. The released
     * predicate accepted these two and nothing else, which is why nothing regressed for those installs; keeping them
     * accepted is what stops this fix from trading one regression for another.
     */
    @Test
    void theOlderTopLevelSteveAndAlexIdsStillCount() {
        assertTrue(ClientEventHandler.isVanillaPlayer(new ResourceLocation(ysmu.MODID, "steve")));
        assertTrue(ClientEventHandler.isVanillaPlayer(new ResourceLocation(ysmu.MODID, "alex")));
    }

    /**
     * The names are matched as whole model paths, never as last segments: a pack is free to have a model called
     * {@code alex} inside its own folder, and that model must keep drawing its own texture (the reference branch's
     * last-segment rule would have given it the player's skin).
     */
    @Test
    void aModelsOwnAlexOrSteveIsNotAVanillaPlayer() {
        assertFalse(ClientEventHandler.isVanillaPlayer(new ResourceLocation(ysmu.MODID, "wine_fox/alex")));
        assertFalse(ClientEventHandler.isVanillaPlayer(new ResourceLocation(ysmu.MODID, "wine_fox/2_steve")));
        assertFalse(ClientEventHandler.isVanillaPlayer(builtIn("wine_fox/alex")));
        assertFalse(ClientEventHandler.isVanillaPlayer(builtIn("wine_fox/2_steve")));
        assertFalse(ClientEventHandler.isVanillaPlayer(new ResourceLocation(ysmu.MODID, "default")));
        assertFalse(ClientEventHandler.isVanillaPlayer(null), "no model id is never a vanilla player");
    }

    /**
     * Sub-model ids are not model ids: the event carries the model (upstream posts {@code cap.getModelId()}, the arm
     * posts the selection's model), and an id with a {@code /main} or {@code /arm} tail describes a file of a model,
     * not the model. It must not be answered as the model it belongs to, or a caller passing one would silently
     * change which texture the model draws.
     */
    @Test
    void aSubModelIdIsNotAModelId() {
        assertFalse(ClientEventHandler.isVanillaPlayer(ModelIdUtil.getMainId(builtIn("misc/2_steve"))));
        assertFalse(ClientEventHandler.isVanillaPlayer(ModelIdUtil.getArmId(builtIn("misc/1_alex"))));
    }
}
