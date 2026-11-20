package com.fox.ysmu.model.roaming;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

/**
 * The bounds every roaming-variable store applies, shared by the client store, the server-side save data and the
 * packet decoders so the same rule set cannot drift into two copies (see
 * {@code .agent/phase15-roaming-variables.md}).
 * <p>
 * A <em>roaming variable</em> is a named float that belongs to one model of one player, read by packs as
 * {@code v.roaming.<name>}. Upstream keys its namespace by the name <em>after</em> the {@code v.roaming.} prefix and
 * bounds that part to 32 bytes; YSMU's engine scope is keyed by the full MoLang name, so both spellings exist here:
 * {@link #isAcceptableName(String)} for the full name the client store and engine use, and
 * {@link #isAcceptableSuffix(String)} for the suffix the wire and the server's save data use (upstream's spelling).
 *
 * <h2>Where each spelling lives (phase78)</h2>
 * <ul>
 * <li>Wire ({@code C2SRoamingChanges}/{@code S2CRoamingState}) and server NBT ({@code ExtendedRoamingVariables}):
 * <b>suffix</b>, exactly upstream's {@code RoamingState.variables[].name} (suffix, ≤32 bytes;
 * {@code network/protocol/ProtocolLimits.java:12-13}).</li>
 * <li>Client store / engine ({@code ClientRoamingStore}, {@code RemoteAnimationVariables},
 * {@code MolangPhysicsRuntime}): <b>full</b> {@code v.roaming.x}, because the engine's scope is a
 * {@code Map<String, Double>} keyed by the Molang identifier.</li>
 * </ul>
 * No store accepts both spellings: a name that the other side forgot to convert is dropped rather than silently
 * duplicated into two namespaces.
 */
public final class ModelRoamingLimits {

    /** The namespace every roaming variable lives in. */
    public static final String PREFIX = "v.roaming.";

    /** Upstream's bound on the name after the prefix ({@code LocalRoamingStruct.MAX_NAME_LENGTH}, in bytes). */
    public static final int MAX_SUFFIX_LENGTH = 32;

    /** Upstream's bound expressed as UTF-8 bytes ({@code ProtocolLimits.MAX_ROAMING_VARIABLE_NAME_BYTES}). */
    public static final int MAX_SUFFIX_BYTES = 32;

    /** The bound YSMU applies to the full variable name. */
    public static final int MAX_NAME_LENGTH = PREFIX.length() + MAX_SUFFIX_LENGTH;

    /** Upstream's bound on the variable count of one model ({@code LocalRoamingStruct.MAX_SIZE}). */
    public static final int MAX_VARIABLES_PER_MODEL = 64;

    /** Upstream's bound on the number of models one player may keep settings for. */
    public static final int MAX_MODELS_PER_PLAYER = 64;

    private ModelRoamingLimits() {}

    /**
     * Whether a name is one every full-name store accepts: non-empty, within the length bound, and a full MoLang name
     * rather than a bare suffix. Names are normalised before they reach a store
     * ({@code RemoteAnimationVariables.normalize}), so a bare name here means the caller forgot to normalise.
     */
    public static boolean isAcceptableName(@Nullable String name) {
        return name != null && !name.isEmpty() && name.length() <= MAX_NAME_LENGTH && name.startsWith("v.");
    }

    /**
     * Whether a suffix is one the wire and the server save data accept, mirroring upstream's roaming-name rule
     * ({@code network/forge/PlayerStateHandler.java:271-286}; bounds in {@code ProtocolLimits.java:12-13}): non-empty,
     * at most 32 UTF-8 bytes, and no ISO control characters.
     * <p>
     * The count bound ({@link #MAX_VARIABLES_PER_MODEL}) and the finite-value rule are checked by the callers next to
     * this predicate, because a suffix cannot answer them on its own. {@code null} is rejected here for the same
     * reason {@link #isAcceptableName} rejects it: a missing name is a caller bug, not an empty variable.
     */
    public static boolean isAcceptableSuffix(@Nullable String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return false;
        }
        if (suffix.getBytes(StandardCharsets.UTF_8).length > MAX_SUFFIX_BYTES) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (Character.isISOControl(suffix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The full MoLang name of a suffix; the caller is expected to have accepted it with {@link #isAcceptableSuffix}. */
    public static String fullName(String suffix) {
        return PREFIX + suffix;
    }

    /**
     * The suffix of a full MoLang roaming name, or {@code null} when the name is outside the {@code v.roaming.}
     * namespace. The result still has to be checked with {@link #isAcceptableSuffix} when it comes from untrusted
     * data; this method only splits the namespace.
     */
    @Nullable
    public static String suffixOf(@Nullable String fullName) {
        if (fullName == null || !fullName.startsWith(PREFIX) || fullName.length() == PREFIX.length()) {
            return null;
        }
        return fullName.substring(PREFIX.length());
    }

    /** Whether a value may be stored: finite, so a broken expression cannot reach a save file. */
    public static boolean isAcceptableValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
