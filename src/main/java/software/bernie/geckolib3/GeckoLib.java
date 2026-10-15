package software.bernie.geckolib3;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Engine-side logging and debug switches, carried inside YSMU after the GeckoLib 3 engine was vendored.
 * <p>
 * This is no longer a mod entry point: the engine is a one-time source snapshot under this repository's
 * {@code software.bernie}, {@code com.eliotlash} and {@code net.geckominecraft} trees and ships inside the YSMU
 * jar, so there is no {@code @Mod} here and no {@code geckolib} mod id to depend on. What remains is the engine's
 * logger and the {@link #geoStatsEnabled} switch, which the host reads and may flip; see {@code PROVENANCE.md}.
 */
public class GeckoLib {

    public static final String MOD_ID = "geckolib";
    public static final String MOD_NAME = "GeckoLib";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    /**
     * Turns on the geometry-submission counters in {@link software.bernie.geckolib3.util.GeoStats}. Off by default;
     * a host mod may set it from its own debug switches (the dependency direction is host to engine, never the
     * reverse). While off every note call is one static read and a return.
     */
    public static volatile boolean geoStatsEnabled = false;
}
