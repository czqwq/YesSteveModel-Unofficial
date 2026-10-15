package com.fox.ysmu.model.roaming;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import com.fox.ysmu.util.Md5Utils;

/**
 * The 32-bit identifier of one model's roaming-variable namespace (see
 * {@code .agent/phase15-roaming-variables.md}).
 * <p>
 * A <em>roaming variable</em> is a named float that belongs to one model of one player, read by packs as
 * {@code v.roaming.<name>} and edited by the model's {@code 模型设置} panel. The server stores the values per player
 * and per model, so both sides have to agree on what "this model" means without exchanging anything extra. They
 * derive it from the model's own content hash, which is already present on both sides and already travels in the
 * OpenYSM sync payload ({@code RawProperties.sha256}), so no payload field and no
 * {@code ModelCacheWriter.OPEN_YSM_BAKE_VERSION} bump are needed.
 * <p>
 * Upstream builds the same value as {@code Hash256.roamingHash()}, the first four bytes of its 32-byte model hash in
 * big-endian order; this class reproduces that for the hexadecimal hashes YSMU works with.
 */
public final class ModelRoamingKey {

    /**
     * How many hexadecimal characters the content hash must have before it is trusted. A shorter string is either a
     * placeholder or a truncated hash, and using it would risk two unrelated models sharing one settings namespace.
     */
    private static final int MIN_HASH_HEX_LENGTH = 8;

    private ModelRoamingKey() {}

    /**
     * Key of the namespace that holds this model's roaming variables.
     *
     * @param modelHashHex the model's content hash as hexadecimal, normally {@code RawProperties.sha256}. May be
     *                     {@code null}, empty or malformed; see {@link #MIN_HASH_HEX_LENGTH}.
     * @param modelId      the resolved model id, used only when the hash cannot be used. May be {@code null}, which is
     *                     treated as the empty string.
     * @return a key that is identical on every client and on the server for the same arguments
     */
    public static int of(@Nullable String modelHashHex, @Nullable String modelId) {
        byte[] fromHash = firstFourBytesOfHex(modelHashHex);
        if (fromHash != null) {
            return bigEndianInt(fromHash);
        }
        String fallbackSource = modelId == null ? "" : modelId;
        return bigEndianInt(Md5Utils.md5(fallbackSource.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Stable lower-case hexadecimal form of a key, used as the key of the save-data compound tag entries. Read it
     * back with {@code Integer.parseUnsignedInt(hex, 16)}.
     */
    public static String toHex(int key) {
        return String.format("%08x", key);
    }

    /**
     * Reads the first four bytes of a hexadecimal string, or {@code null} when the string is unusable: empty, too
     * short, odd length, or containing a character that is not a hexadecimal digit.
     */
    @Nullable
    private static byte[] firstFourBytesOfHex(@Nullable String hex) {
        if (hex == null || hex.length() < MIN_HASH_HEX_LENGTH || (hex.length() & 1) != 0) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static int bigEndianInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8)
            | (bytes[3] & 0xFF);
    }
}
