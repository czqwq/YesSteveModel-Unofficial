package com.fox.ysmu;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

public class Config {
    private static Configuration configuration;

    // General Config
    public static boolean DISCLAIMER_SHOW = true;
    public static boolean PRINT_ANIMATION_ROULETTE_MSG = true;
    public static boolean DISABLE_SELF_MODEL = false;
    public static boolean DISABLE_OTHER_MODEL = false;
    public static boolean DISABLE_SELF_HANDS = false;
    public static String DEFAULT_MODEL_ID = "default";
    public static String DEFAULT_MODEL_TEXTURE = "default.png";

    // Extra Player Screen Config
    public static boolean DISABLE_PLAYER_RENDER = false;
    public static int PLAYER_POS_X = 10;
    public static int PLAYER_POS_Y = 10;
    public static double PLAYER_SCALE = 40.0;
    public static double PLAYER_YAW_OFFSET = 5.0;

    // OpenYSM model sync config
    public static boolean ENABLE_OPEN_YSM_SYNC_PROTOCOL = true;
    public static int THREAD_COUNT = 4;
    public static int BANDWIDTH_LIMIT = 0;
    public static int PLAYER_SYNC_TIMEOUT = 60;
    public static boolean LOW_BANDWIDTH_USAGE = false;
    public static boolean ACCEPT_SOUND_FX = true;

    /**
     * Per-frame diagnostics for model sound effects: which model registered which sound, which controller fired
     * which keyframe, and which source name a sound became. Off by default - the lines are per-keyframe.
     */
    public static boolean DEBUG_SOUND = false;

    /**
     * The debug switches the model and animation paths gate their diagnostics on. All off by default, and every
     * site that uses one also rate-limits or deduplicates its output: without that, a single stuck projectile
     * prints a line per frame and the log becomes unreadable at exactly the moment it is needed.
     * <ul>
     * <li>{@link #DEBUG_MODEL_LOAD} - which model files were read and which sub-models they declared;</li>
     * <li>{@link #DEBUG_MODEL_RENDER} - geometry/texture lookup results on the render path;</li>
     * <li>{@link #DEBUG_ANIMATION} - which animations a frame selected and which timeline instructions ran;</li>
     * <li>{@link #DEBUG_CONTROLLER} - controller state-machine transitions and expression results.</li>
     * </ul>
     */
    public static boolean DEBUG_MODEL_LOAD = false;
    public static boolean DEBUG_MODEL_RENDER = false;
    public static boolean DEBUG_ANIMATION = false;
    public static boolean DEBUG_CONTROLLER = false;

    /** {@code ysm.particle} / {@code ysm.abs_particle} diagnostics. */
    public static boolean DEBUG_PARTICLE = false;

    /**
     * Debug/testing: an extra number of blocks subtracted from a particle's Y offset when it is spawned.
     * Corrects a model whose particles sit consistently high (some are off by about two blocks).
     */
    public static double PARTICLE_Y_ADJUST = 0.0;

    /** Debug/testing: force a particle's xyz offset to 0, i.e. spawn it exactly at the entity. */
    public static boolean PARTICLE_ZERO_OFFSET = false;

    /**
     * A high-version Minecraft game directory, used to play sound ids and read particle textures that 1.7.10 does
     * not ship (a pack may reference {@code minecraft:item.trident.throw}, which has no 1.7.10 equivalent). Empty
     * disables the whole lookup; {@code LocalAssetProvider} reads it.
     */
    public static String HIGH_VERSION_GAME_PATH = "";

    /** The asset-index version to read from that directory, e.g. {@code 32}. */
    public static String HIGH_VERSION_ASSET_VERSION = "32";

    /**
     * The version directory under {@code <gamePath>/versions/} whose client jar holds {@code textures/particle}.
     * Newer Minecraft versions keep those textures inside the version jar rather than in the assets index; leave
     * empty when not needed.
     */
    public static String HIGH_VERSION_JAR_VERSION = "26.2";

    /**
     * 在 Mod preInit 阶段调用，用于初始化配置文件并进行首次加载。
     * @param configFile a suggested configuration file from the FMLPreInitializationEvent.
     */
    public static void init(File configFile) {
        if (configuration == null) {
            configuration = new Configuration(configFile);
            sync(true); // true 表示执行加载操作
        }
    }

    /**
     * 在需要保存配置时（如关闭GUI）调用。
     */
    public static void save() {
        sync(false); // false 表示执行保存操作
    }

    /**
     * 根据参数决定加载或保存。
     * @param load 如果为 true，则从配置文件加载到静态变量；如果为 false，则从静态变量保存到配置文件。
     */
    private static void sync(boolean load) {
        if (load) {
            configuration.load();
        }

        // General config values
        DISCLAIMER_SHOW = syncBoolean("DisclaimerShow", Configuration.CATEGORY_GENERAL, DISCLAIMER_SHOW, "Whether to display disclaimer GUI", load);
        PRINT_ANIMATION_ROULETTE_MSG = syncBoolean("PrintAnimationRouletteMsg", Configuration.CATEGORY_GENERAL, PRINT_ANIMATION_ROULETTE_MSG, "Whether to print animation roulette play message", load);
        DISABLE_SELF_MODEL = syncBoolean("DisableSelfModel", Configuration.CATEGORY_GENERAL, DISABLE_SELF_MODEL, "Prevents rendering of self player's model", load);
        DISABLE_OTHER_MODEL = syncBoolean("DisableOtherModel", Configuration.CATEGORY_GENERAL, DISABLE_OTHER_MODEL, "Prevents rendering of other player's model", load);
        DISABLE_SELF_HANDS = syncBoolean("DisableSelfHands", Configuration.CATEGORY_GENERAL, DISABLE_SELF_HANDS, "Prevents rendering of self player's hand", load);
        DEFAULT_MODEL_ID = syncString("DefaultModelId", Configuration.CATEGORY_GENERAL, DEFAULT_MODEL_ID, "The default model ID when a player first enters the game", load);
        DEFAULT_MODEL_TEXTURE = syncString("DefaultModelTexture", Configuration.CATEGORY_GENERAL, DEFAULT_MODEL_TEXTURE, "The default model texture when a player first enters the game", load);

        // Extra player render config values
        DISABLE_PLAYER_RENDER = syncBoolean("DisablePlayerRender", "extra_player_render", DISABLE_PLAYER_RENDER, "Whether to display player", load);
        PLAYER_POS_X = syncInt("PlayerPosX", "extra_player_render", PLAYER_POS_X, "Player position x in screen", 0, Integer.MAX_VALUE, load);
        PLAYER_POS_Y = syncInt("PlayerPosY", "extra_player_render", PLAYER_POS_Y, "Player position y in screen", 0, Integer.MAX_VALUE, load);
        PLAYER_SCALE = syncDouble("PlayerScale", "extra_player_render", PLAYER_SCALE, "Player scale in screen", 8.0, 360.0, load);
        PLAYER_YAW_OFFSET = syncDouble("PlayerYawOffset", "extra_player_render", PLAYER_YAW_OFFSET, "Player yaw offset in screen", load);

        // OpenYSM model sync config values
        ENABLE_OPEN_YSM_SYNC_PROTOCOL = syncBoolean("EnableOpenYsmSyncProtocol", "openysm_sync", ENABLE_OPEN_YSM_SYNC_PROTOCOL, "Whether to use the appended OpenYSM hash/cache/chunk sync path before legacy fallback", load);
        THREAD_COUNT = syncInt("ThreadCount", "openysm_sync", THREAD_COUNT, "Target worker count for the shared model sync thread pool (read by util/ThreadTools, core/max worker count; clamped to 1..32)", 1, 32, load);
        BANDWIDTH_LIMIT = syncInt("BandwidthLimit", "openysm_sync", BANDWIDTH_LIMIT, "OpenYSM model sync bandwidth limit in bytes per second. 0 means unlimited", 0, Integer.MAX_VALUE, load);
        PLAYER_SYNC_TIMEOUT = syncInt("PlayerSyncTimeout", "openysm_sync", PLAYER_SYNC_TIMEOUT, "OpenYSM model sync timeout in seconds", 5, Integer.MAX_VALUE, load);
        LOW_BANDWIDTH_USAGE = syncBoolean("LowBandwidthUsage", "openysm_sync", LOW_BANDWIDTH_USAGE, "Whether OpenYSM sync should use smaller chunks and conservative throttling", load);
        ACCEPT_SOUND_FX = syncBoolean("AcceptSoundFX", "openysm_sync", ACCEPT_SOUND_FX, "Whether OpenYSM sync should accept model sound effect resources", load);

        // Model sound diagnostics
        DEBUG_SOUND = syncBoolean("DebugSound", "debug", DEBUG_SOUND, "Enable model sound cache/playback debug logging ([YSMU-SOUND])", load);

        // Model / animation diagnostics (all off by default; each site rate-limits or deduplicates)
        DEBUG_MODEL_LOAD = syncBoolean("DebugModelLoad", "debug", DEBUG_MODEL_LOAD, "Log model file loading and sub-model discovery", load);
        DEBUG_MODEL_RENDER = syncBoolean("DebugModelRender", "debug", DEBUG_MODEL_RENDER, "Log geometry/texture lookup results on the render path", load);
        DEBUG_ANIMATION = syncBoolean("DebugAnimation", "debug", DEBUG_ANIMATION, "Log animation selection and timeline dispatch", load);
        DEBUG_CONTROLLER = syncBoolean("DebugController", "debug", DEBUG_CONTROLLER, "Log controller state transitions and expression results", load);
        DEBUG_PARTICLE = syncBoolean("DebugParticle", "debug", DEBUG_PARTICLE, "Enable particle()/abs_particle() debug logging ([YSMU-PARTICLE])", load);
        PARTICLE_Y_ADJUST = syncDouble("ParticleYAdjust", "debug", PARTICLE_Y_ADJUST, "Extra Y offset subtracted from particle()/abs_particle() spawn position, in blocks (debug/testing; default 0)", -10.0, 10.0, load);
        PARTICLE_ZERO_OFFSET = syncBoolean("ParticleZeroOffset", "debug", PARTICLE_ZERO_OFFSET, "Force particle()/abs_particle() xyz offsets to 0 (spawn at entity position; debug/testing)", load);

        // The engine's geometry-submission counters (`GeoStats`: cubes/vertices/flushes/animation ticks) are gated on
        // an engine-owned switch, because the engine must not read this mod's config - the dependency direction is
        // host to engine (Geckolib's AGENTS.md, "Host Contract"). The host is therefore the one that translates its
        // own debug switches into that flag, which is what the reference branch did by hard-coding the read.
        software.bernie.geckolib3.GeckoLib.geoStatsEnabled = DEBUG_MODEL_LOAD && DEBUG_MODEL_RENDER;

        // High-version local assets: sounds and particle textures 1.7.10 does not ship.
        HIGH_VERSION_GAME_PATH = syncString("HighVersionGamePath", "local_assets", HIGH_VERSION_GAME_PATH, "Path to a high-version Minecraft game directory (e.g. C:/Users/x/AppData/Roaming/.minecraft). YSMU reads sounds.json and OGG files from here to play high-version sounds that Et-Futurum doesn't cover.", load);
        HIGH_VERSION_ASSET_VERSION = syncString("HighVersionAssetVersion", "local_assets", HIGH_VERSION_ASSET_VERSION, "Asset version to use (e.g. '32'). Must match the version subfolder under assets/indexes/ in the game directory.", load);
        HIGH_VERSION_JAR_VERSION = syncString("HighVersionJarVersion", "local_assets", HIGH_VERSION_JAR_VERSION, "Version directory under <gamePath>/versions/ whose client jar holds textures/particles (e.g. '26.2'). Newer Minecraft versions keep textures inside the version jar; leave empty if not needed.", load);

        // 检查配置是否已更改，如果已更改，则保存
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    private static boolean syncBoolean(String name, String category, boolean currentValue, String comment, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment);
        if (load) {
            return prop.getBoolean(currentValue);
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }

    private static String syncString(String name, String category, String currentValue, String comment, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment);
        if (load) {
            return prop.getString();
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }

    private static int syncInt(String name, String category, int currentValue, String comment, int min, int max, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment, min, max);
        if (load) {
            // CU-09: Forge 1.7.10 的 Property.getInt(int)/getDouble(double) 不做 clamp（min/max 只写进注释，
            // 见 Property.java:691-701/792-820），手改配置文件即可得到越界值；这里自行钳制。
            return clamp(prop.getInt(currentValue), min, max);
        } else {
            prop.set(clamp(currentValue, min, max));
            return clamp(currentValue, min, max);
        }
    }

    private static double syncDouble(String name, String category, double currentValue, String comment, double min, double max, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment, min, max);
        if (load) {
            // CU-09: 同上，越界的 PlayerScale（例如手改成 1000）必须被钳制回 8..360。
            return clamp(prop.getDouble(currentValue), min, max);
        } else {
            prop.set(clamp(currentValue, min, max));
            return clamp(currentValue, min, max);
        }
    }

    private static double syncDouble(String name, String category, double currentValue, String comment, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment);
        if (load) {
            return prop.getDouble(currentValue);
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }

    /** CU-09: 通用钳制，避免配置/GUI 写入越界值。 */
    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    /** CU-09: 通用钳制，避免配置/GUI 写入越界值。 */
    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
