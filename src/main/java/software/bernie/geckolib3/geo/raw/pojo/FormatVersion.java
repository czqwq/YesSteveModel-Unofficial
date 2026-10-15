package software.bernie.geckolib3.geo.raw.pojo;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Bedrock geometry format version.
 * <p>
 * The upstream enum only accepted {@code 1.12.0} and {@code 1.14.0}. Community model packs are long-lived assets and
 * were authored against earlier Blockbench exports - the 1.12.2 branch alone ships 150 entity models that declare
 * {@code 1.8.0} or {@code 1.10.0}, while 1.20 packs declare {@code 1.12.0}. Rejecting the older strings made the
 * majority of installable model packs fail to deserialize, and because that failure happens inside
 * {@code Converter.fromJsonString} it surfaced as a whole model silently disappearing rather than as an error
 * anybody sees.
 * <p>
 * These versions are accepted rather than remapped to {@link #VERSION_1_12_0}: the geometry builder in this port
 * never branches on the version (nothing outside {@code RawGeoModel} reads it), so the declared value is kept and a
 * caller that wants to restrict which layouts it builds can say so itself.
 * <p>
 * Newer Minecraft-release strings are accepted for the same reason. Blockbench keeps stamping the running game
 * version into {@code format_version} while the geometry schema stays the one this builder already reads, so a pack
 * exported on a recent version (for example Wine Fox &amp; Friends, whose {@code 08_sta}, {@code 16_tactics},
 * {@code 18_wedding}, {@code 20_survivor} and {@code 21_saint} player models declare {@code 1.21.0}, and whose
 * {@code 01_taisho_maid} vehicle model {@code foxcar.json} does too) used to lose six of its twenty-two models to a
 * deserialize exception. Nothing below branches on the value either.
 */
public enum FormatVersion {

    VERSION_1_8_0,
    VERSION_1_10_0,
    VERSION_1_12_0,
    VERSION_1_14_0,
    VERSION_1_16_0,
    VERSION_1_19_0,
    VERSION_1_20_0,
    VERSION_1_21_0;

    @JsonValue
    public String toValue() {
        switch (this) {
            case VERSION_1_8_0:
                return "1.8.0";
            case VERSION_1_10_0:
                return "1.10.0";
            case VERSION_1_12_0:
                return "1.12.0";
            case VERSION_1_14_0:
                return "1.14.0";
            case VERSION_1_16_0:
                return "1.16.0";
            case VERSION_1_19_0:
                return "1.19.0";
            case VERSION_1_20_0:
                return "1.20.0";
            case VERSION_1_21_0:
                return "1.21.0";
        }
        return null;
    }

    /**
     * True for every version this port deserializes and can hand to its geometry builder.
     * <p>
     * Kept as a predicate rather than making callers compare against a single constant: the ports differ only in the
     * version string they declare, and an {@code == VERSION_1_12_0} test silently skips every other version.
     */
    public boolean isSupportedLayout() {
        return true;
    }

    @JsonCreator
    public static FormatVersion forValue(String value) throws IOException {
        if (value == null) {
            throw new IOException("Cannot deserialize FormatVersion: null");
        }
        String normalized = value.trim();
        if (normalized.equals("1.8.0")) return VERSION_1_8_0;
        if (normalized.equals("1.10.0")) return VERSION_1_10_0;
        if (normalized.equals("1.12.0")) return VERSION_1_12_0;
        if (normalized.equals("1.14.0")) return VERSION_1_14_0;
        if (normalized.equals("1.16.0")) return VERSION_1_16_0;
        if (normalized.equals("1.19.0")) return VERSION_1_19_0;
        if (normalized.equals("1.20.0")) return VERSION_1_20_0;
        if (normalized.equals("1.21.0")) return VERSION_1_21_0;
        throw new IOException("Cannot deserialize FormatVersion: " + value);
    }
}
