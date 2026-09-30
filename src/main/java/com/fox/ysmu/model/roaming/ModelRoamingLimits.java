package com.fox.ysmu.model.roaming;

import javax.annotation.Nullable;

/**
 * The bounds every roaming-variable store applies, shared by the client store, the server-side save data and the
 * packet decoders so the same rule set cannot drift into two copies (see
 * {@code .agent/phase15-roaming-variables.md}).
 * <p>
 * A <em>roaming variable</em> is a named float that belongs to one model of one player, read by packs as
 * {@code v.roaming.<name>}. Upstream keys its namespace by the name <em>after</em> the {@code v.roaming.} prefix and
 * bounds that part to 32 characters; YSMU carries the full MoLang name, so the bound here is the prefix plus
 * upstream's 32.
 */
public final class ModelRoamingLimits {

    /** The namespace every roaming variable lives in. */
    public static final String PREFIX = "v.roaming.";

    /** Upstream's bound on the name after the prefix ({@code LocalRoamingStruct.MAX_NAME_LENGTH}). */
    public static final int MAX_SUFFIX_LENGTH = 32;

    /** The bound YSMU applies to the full variable name. */
    public static final int MAX_NAME_LENGTH = PREFIX.length() + MAX_SUFFIX_LENGTH;

    /** Upstream's bound on the variable count of one model ({@code LocalRoamingStruct.MAX_SIZE}). */
    public static final int MAX_VARIABLES_PER_MODEL = 64;

    /** Upstream's bound on the number of models one player may keep settings for. */
    public static final int MAX_MODELS_PER_PLAYER = 64;

    private ModelRoamingLimits() {}

    /**
     * Whether a name is one every store accepts: non-empty, within the length bound, and a full MoLang name rather
     * than a bare suffix. Names are normalised before they reach a store
     * ({@code RemoteAnimationVariables.normalize}), so a bare name here means the caller forgot to normalise.
     */
    public static boolean isAcceptableName(@Nullable String name) {
        return name != null && !name.isEmpty() && name.length() <= MAX_NAME_LENGTH && name.startsWith("v.");
    }

    /** Whether a value may be stored: finite, so a broken expression cannot reach a save file. */
    public static boolean isAcceptableValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
