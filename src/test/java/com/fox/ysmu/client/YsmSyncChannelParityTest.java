package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.YSMBinarySerializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import rip.ysm.security.YSMByteBuf;
import rip.ysm.security.YsmCrypt;

/**
 * The two sync channels must carry the same model, byte for byte.
 * <p>
 * A `.ysm` model is served on both: the legacy channel adapts the file straight into a legacy {@code ModelData}, and
 * the OpenYSM channel serialises the same model with the sync format, which the client deserialises and adapts again.
 * {@code ClientModelManager} skips a re-registration only when the content signature matches, and that signature
 * covers the model's geometry, texture and animation bytes - so if the two channels produced different bytes the model
 * would register twice per join, and the second registration invalidates the first publish: the ready marker, the
 * four per-model tables and the installed animation file are all dropped, which is a visible flicker back to the
 * built-in default plus a duplicate parse of the whole model. That was raised as a possible regression and it is not
 * one; this test is what says so, and what will notice if a change to the serializer or the adapter breaks it.
 * <p>
 * Only OpenYSM-binary packs are served on both channels - the legacy ones are single-channel, and the server tells
 * them apart before it decides whether to write a sync payload at all - so each pack is probed and the ones this path
 * cannot read are skipped. Gated on real packs being present, because the question is about real files.
 */
class YsmSyncChannelParityTest {

    private static final int OPEN_YSM_SYNC_FORMAT = 32;

    private static final String MODEL_ID = "parity_probe";

    @Test
    void bothChannelsAdaptToTheSameBytes() throws Exception {
        File directory = new File("run/client/config/ysmu/custom");
        File[] packs = directory.listFiles((dir, name) -> name.endsWith(".ysm"));
        assumeTrue(packs != null && packs.length > 0, "no .ysm pack present, so there is nothing to compare");

        // One pack is enough to answer the question, and the packs are megabytes: reading all of them, decrypting
        // them and holding two adapted copies of each is enough memory pressure to disturb the rest of the suite. The
        // smallest file is tried first so the comparison stays cheap.
        File[] ordered = packs.clone();
        Arrays.sort(ordered, java.util.Comparator.comparingLong(File::length));
        for (File file : ordered) {
            RawYsmModel served;
            try {
                served = deserialize(YsmCrypt.decryptYsmFile(Files.readAllBytes(file.toPath())), -1);
            } catch (Exception notThisFormat) {
                // A legacy pack: served on one channel only, so this question does not arise for it.
                continue;
            }
            served.modelId = MODEL_ID;
            ModelData legacy = RawYsmModelAdapter.toLegacyModelData(served, MODEL_ID);

            byte[] payload;
            try (YSMByteBuf buffer = YSMBinarySerializer.serialize(served, OPEN_YSM_SYNC_FORMAT, true)) {
                payload = buffer.toArray();
            }
            RawYsmModel received = deserialize(payload, OPEN_YSM_SYNC_FORMAT);
            received.modelId = MODEL_ID;
            ModelData openYsm = RawYsmModelAdapter.toLegacyModelData(received, MODEL_ID);

            assertFalse(legacy.getModel().isEmpty(), "the legacy channel must carry geometry");
            assertFalse(openYsm.getModel().isEmpty(), "the OpenYSM channel must carry geometry");

            assertSameBytes(file.getName(), "geometry", legacy.getModel(), openYsm.getModel());
            assertSameBytes(file.getName(), "texture", legacy.getTexture(), openYsm.getTexture());
            assertSameBytes(file.getName(), "animation", legacy.getAnimation(), openYsm.getAnimation());
            return;
        }
        assumeTrue(false, "no .ysm pack in the folder could be read as an OpenYSM payload");
    }

    /** The format is explicit for a sync payload, exactly as the client's own sync reader passes it. */
    private static RawYsmModel deserialize(byte[] bytes, int format) {
        try (YSMBinaryDeserializer deserializer = format < 0 ? new YSMBinaryDeserializer(bytes)
            : new YSMBinaryDeserializer(bytes, format)) {
            RawYsmModel raw = deserializer.deserializeKeepOpen();
            deserializer.parseYSMFooter(raw);
            return raw;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the payload", e);
        }
    }

    private static void assertSameBytes(String pack, String what, Map<String, byte[]> legacy,
        Map<String, byte[]> openYsm) {
        String keys = " (legacy " + new TreeSet<>(legacy.keySet()) + ", openYsm " + new TreeSet<>(openYsm.keySet()) + ")";
        assertTrue(
            legacy.keySet()
                .equals(openYsm.keySet()),
            pack + ": the two channels must adapt the same " + what + " entries" + keys);
        for (String key : legacy.keySet()) {
            assertTrue(
                Arrays.equals(legacy.get(key), openYsm.get(key)),
                pack + ": " + what + " entry " + key + " differs between the channels, so the duplicate check would "
                    + "not match and the model would register twice" + keys);
        }
    }
}
