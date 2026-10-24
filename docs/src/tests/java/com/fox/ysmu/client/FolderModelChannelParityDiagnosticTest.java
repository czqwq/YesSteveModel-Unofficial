package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import rip.ysm.security.YSMByteBuf;

/**
 * A diagnostic: why does a folder model defeat the duplicate check, and what exactly differs?
 * <p>
 * The user's log answers the first half by measurement - 28 `wine_fox/*` folder models re-register six times per
 * session and are never skipped, while every `.ysm` model is skipped every time. The duplicate check hashes the
 * adapted geometry, texture and animation bytes, and the legacy channel adapts those from the folder's source JSON
 * while the OpenYSM channel adapts them from the model the client deserialises out of the baked payload. This prints
 * the difference so the second half stops being an assumption - including whether the baked side is missing something
 * the source side has, which is what would make the model render wrongly after the second registration wins.
 * <p>
 * Gated on the pack being present, because the question is about a real pack.
 */
class FolderModelChannelParityDiagnosticTest {

    private static final int OPEN_YSM_SYNC_FORMAT = 32;

    private static final String RELATIVE = "run/client/config/ysmu/custom/wine_fox/05_magical";

    private static final String MODEL_ID = "wine_fox/05_magical";

    @Test
    void printWhatDiffersBetweenTheTwoChannelsForAFolderModel() throws Exception {
        File directory = new File(RELATIVE);
        assumeTrue(directory.isDirectory(), "the wine_fox pack is not present, so there is nothing to compare");
        assumeTrue(new File(directory, "ysm.json").isFile(), "not an OpenYSM folder model");

        // The legacy channel, exactly as the server builds it (OpenYsmFormat:81).
        RawYsmModel fromFolder;
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(directory.toPath())) {
            fromFolder = deserializer.deserialize();
        }
        fromFolder.modelId = MODEL_ID;
        ModelData legacy = RawYsmModelAdapter.toLegacyModelData(fromFolder, MODEL_ID);

        // The OpenYSM channel: the server serialises the same model (ModelCacheWriter:97) and the client adapts what it
        // deserialises (OpenYsmModelSyncClient:383).
        byte[] payload;
        try (YSMByteBuf buffer = YSMBinarySerializer.serialize(fromFolder, OPEN_YSM_SYNC_FORMAT, true)) {
            payload = buffer.toArray();
        }
        RawYsmModel received;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(payload, OPEN_YSM_SYNC_FORMAT)) {
            received = deserializer.deserializeKeepOpen();
            deserializer.parseYSMFooter(received);
        }
        received.modelId = MODEL_ID;
        ModelData openYsm = RawYsmModelAdapter.toLegacyModelData(received, MODEL_ID);

        assertFalse(legacy.getModel().isEmpty(), "the folder channel must carry geometry");
        assertFalse(openYsm.getModel().isEmpty(), "the baked channel must carry geometry");

        // The identity each channel carries. The duplicate check mixes this into its digest, so whether the two
        // channels can ever agree on "same model" depends on whether these are equal or merely both present.
        System.out.println("FOLDERP identity: legacyMd5=" + md5(legacy) + " bakedMd5=" + md5(openYsm)
            + " same=" + String.valueOf(md5(legacy)).equals(String.valueOf(md5(openYsm))));
        // Declaration order matters: the last rung of the texture ladder is "the first texture", and upstream's
        // assembler writes the first declared texture into the model's own defaultTexture field.
        System.out.println("FOLDERP texture order: legacy=" + legacy.getTexture().keySet() + " baked="
            + openYsm.getTexture().keySet());

        report("geometry", legacy.getModel(), openYsm.getModel());
        report("texture", legacy.getTexture(), openYsm.getTexture());
        report("animation", legacy.getAnimation(), openYsm.getAnimation());
    }

    private static String md5(ModelData data) {
        return data.getInfo() == null ? "null" : String.valueOf(data.getInfo().getMd5());
    }

    private static void report(String what, Map<String, byte[]> legacy, Map<String, byte[]> openYsm) {
        boolean same = legacy.keySet()
            .equals(openYsm.keySet());
        System.out.println(
            "FOLDERP " + what + ": legacyKeys=" + new TreeSet<>(legacy.keySet()) + " bakedKeys="
                + new TreeSet<>(openYsm.keySet()) + " sameKeys=" + same);
        if (same) {
            for (String key : legacy.keySet()) {
                byte[] left = legacy.get(key);
                byte[] right = openYsm.get(key);
                if (!Arrays.equals(left, right)) {
                    same = false;
                    System.out.println(
                        "FOLDERP " + what + " DIFFERS for " + key + ": legacy=" + left.length + " bytes, baked="
                            + right.length + " bytes");
                    System.out.println(
                        "FOLDERP   legacy head: " + head(left) + "   ... tail: " + tail(left));
                    System.out.println(
                        "FOLDERP   baked  head: " + head(right) + "   ... tail: " + tail(right));
                }
            }
        }
        System.out.println("FOLDERP " + what + " identical=" + same);
    }

    private static String head(byte[] bytes) {
        return new String(bytes, 0, Math.min(300, bytes.length), java.nio.charset.StandardCharsets.UTF_8)
            .replace('\n', ' ');
    }

    private static String tail(byte[] bytes) {
        int from = Math.max(0, bytes.length - 200);
        return new String(bytes, from, bytes.length - from, java.nio.charset.StandardCharsets.UTF_8).replace('\n', ' ');
    }
}
