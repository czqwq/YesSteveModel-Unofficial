package com.fox.ysmu.client.animation.molang;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.UserFunctionArguments;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The OpenYSM pack functions of the model being rendered: {@code functions/*.molang} bodies, callable from animations
 * and controllers as {@code fn.<name>(...)}.
 * <p>
 * The bodies travel with the model in the 17-channel payload ({@code RawYsmModel.functionFiles}, written by
 * {@code YSMBinarySerializer.writeFunctionFiles}) and are handed here when the model is registered. A call site only
 * carries the name, so resolution happens at evaluation time against the frame's model: two packs may define
 * {@code fn.halo_battery_indicator} with different bodies, and the one that belongs to the model being rendered wins.
 * <p>
 * Not ported: OpenYSM also binds some of these scripts to hooks by file name ({@code name@player_ctrl_main.molang})
 * and gives them a {@code ctrl.*} script API. This class only answers {@code fn.} calls; a script that calls an
 * unported function reports once and evaluates to 0. The legacy sync channel carries no function files at all, so on
 * that channel every {@code fn.} call answers 0.
 */
@SideOnly(Side.CLIENT)
public final class PackUserFunctions {

    private static final String PREFIX = "fn.";
    private static final String EXTENSION = ".molang";
    /** The suffix this port's own controller names carry; see {@link #scriptForController}. */
    private static final String CONTROLLER_SUFFIX = "_controller";

    private static final Map<ResourceLocation, Map<String, PackFunctionScript>> BY_MODEL = Maps.newConcurrentMap();
    /**
     * The same scripts, keyed by what follows the {@code @} in their file name - {@code player_ctrl_main},
     * {@code player_init}, {@code player_update}, {@code sync}, ... That is the hook an OpenYSM pack binds a script to
     * ({@code functions/<anything>@<hook>.molang}); upstream resolves a controller hook by exactly that key
     * ({@code CodedAnimationController.java:129-133}) and keeps a <b>list</b> per key for the event hooks
     * ({@code CommonAsset#eventHandlers} is a {@code Map<String, List<IValue>>}).
     * <p>
     * The list matters: wine_fox/18_wedding ships <em>two</em> files bound to {@code player_init}
     * ({@code 碰墙抬手初始化@player_init.molang} and {@code 随机眨眼初始化@player_init.molang}), so a single-slot map
     * would silently drop one of them.
     */
    private static final Map<ResourceLocation, Map<String, List<PackFunctionScript>>> HOOKS_BY_MODEL =
        Maps.newConcurrentMap();
    private static final ThreadLocal<Map<String, PackFunctionScript>> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, List<PackFunctionScript>>> CURRENT_HOOKS = new ThreadLocal<>();
    private static final Set<String> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /**
     * A-16: which (player, model) pairs have already run their {@code player_init} scripts. Upstream fires them once
     * after a model loads and clears the flag when it unloads ({@code CustomHumanoidEntity.java:195,208-214}).
     * {@link #clear()} is what makes a reload re-run them.
     */
    private static final Map<String, String> INITIALIZED = Maps.newConcurrentMap();
    /** A-16: the last animation tick seen per player, for the {@code args[0]} of {@code player_update}. */
    private static final Map<String, Double> LAST_TICK = Maps.newConcurrentMap();

    private PackUserFunctions() {}

    /**
     * Parses and stores a model's script files. Must run on the client thread, and before the model is rendered.
     */
    public static void register(ResourceLocation modelId, Map<String, RawYsmModel.RawDataFile> files) {
        if (modelId == null || files == null || files.isEmpty()) {
            return;
        }
        Map<String, PackFunctionScript> scripts = Maps.newHashMap();
        Map<String, List<PackFunctionScript>> hooks = Maps.newHashMap();
        for (Map.Entry<String, RawYsmModel.RawDataFile> entry : files.entrySet()) {
            String fileName = entry.getKey();
            RawYsmModel.RawDataFile file = entry.getValue();
            if (fileName == null || file == null || file.data == null) {
                continue;
            }
            try {
                PackFunctionScript script = PackFunctionScript.parse(new String(file.data, StandardCharsets.UTF_8));
                if (script == null) {
                    continue;
                }
                String name = stripExtension(fileName);
                // An assignment to a name the parser does not know becomes a local of that one statement, and this
                // interpreter parses each statement separately (block ternaries cannot be one expression), so the
                // script's own t.*/v.* names have to exist before it runs.
                script.registerVariables(GeckoLibCache.getInstance().parser);
                scripts.put(name, script);
                int hook = name.indexOf('@');
                if (hook > 0 && hook + 1 < name.length()) {
                    // `name@hook.molang` is a hook script upstream; the pack may still call it as `fn.name(...)`, and
                    // the part after `@` is what binds it to a controller.
                    scripts.putIfAbsent(name.substring(0, hook), script);
                    hooks.computeIfAbsent(name.substring(hook + 1), key -> new ArrayList<>())
                        .add(script);
                }
            } catch (Exception | LinkageError e) {
                warnOnce(fileName, e.getClass()
                    .getSimpleName());
            }
        }
        if (!scripts.isEmpty()) {
            BY_MODEL.put(modelId, scripts);
            if (!hooks.isEmpty()) {
                HOOKS_BY_MODEL.put(modelId, hooks);
            }
            ysmu.LOG.info("YSM client registered {} OpenYSM pack function(s) for {}", scripts.size(), modelId);
        }
    }

    /**
     * Installs the parser hook that turns an otherwise unknown {@code fn.<name>(...)} into a call into this registry.
     */
    public static void installResolver(MolangParser parser) {
        parser.setFunctionResolver((name, arguments) -> {
            if (name == null || !name.startsWith(PREFIX)) {
                return null;
            }
            return new Call(name.substring(PREFIX.length()), arguments.toArray(new IValue[0]));
        });
    }

    /**
     * Publishes the frame's model, so {@code fn.} calls resolve against the right pack. {@code null} clears it (no
     * model applied, or a preview).
     */
    public static void begin(ResourceLocation mainModelId) {
        if (mainModelId == null) {
            CURRENT.remove();
            CURRENT_HOOKS.remove();
            return;
        }
        ResourceLocation parent = ModelIdUtil.getParentModelId(mainModelId);
        CURRENT.set(BY_MODEL.get(parent));
        CURRENT_HOOKS.set(HOOKS_BY_MODEL.get(parent));
    }

    public static void end() {
        CURRENT.remove();
        CURRENT_HOOKS.remove();
    }

    public static void clear() {
        BY_MODEL.clear();
        HOOKS_BY_MODEL.clear();
        CURRENT.remove();
        CURRENT_HOOKS.remove();
        INITIALIZED.clear();
    }

    /**
     * The script a model bound to one controller, or {@code null} when it binds none.
     * <p>
     * Upstream resolves the hook with {@code controllerName.replace(".", "_ctrl_")}
     * ({@code CodedAnimationController.java:129-133}), which matches its own names ({@code player.pre_main} ->
     * {@code player_ctrl_pre_main}). This port's controllers are named {@code main_controller},
     * {@code parallel_5_controller}, ... so the {@code player_ctrl_<name>} spelling is tried as well; both keys are
     * built here and both are what packs ship.
     */
    @Nullable
    public static PackFunctionScript scriptForController(String controllerName) {
        Map<String, List<PackFunctionScript>> hooks = CURRENT_HOOKS.get();
        if (hooks == null || hooks.isEmpty() || controllerName == null || controllerName.isEmpty()) {
            return null;
        }
        List<PackFunctionScript> byUpstreamName = hooks.get(controllerName.replace(".", "_ctrl_"));
        if (byUpstreamName != null && !byUpstreamName.isEmpty()) {
            return byUpstreamName.get(0);
        }
        String name = controllerName.endsWith(CONTROLLER_SUFFIX)
            ? controllerName.substring(0, controllerName.length() - CONTROLLER_SUFFIX.length())
            : controllerName;
        List<PackFunctionScript> byPortName = hooks.get("player_ctrl_" + name);
        return byPortName == null || byPortName.isEmpty() ? null : byPortName.get(0);
    }

    /**
     * A-16: runs every script a model bound to one event hook, in the order the model declares them.
     * <p>
     * Upstream has three of these beyond the controller hooks ({@code MolangEventWrapper.java:12-14}):
     * {@code player_init} - once after a model loads ({@code CustomHumanoidEntity.java:195,208-214}) -
     * {@code player_update} - every frame, handed {@code args[0] = "did this frame actually tick"} -
     * and {@code sync}, fired from {@code CustomPlayerEntity#molangSync}. This port bound the controller hook only, so
     * the shipped {@code @player_init} / {@code @player_update} scripts (wine_fox/18_wedding has four of them) had
     * never run at all.
     *
     * @param hook      the part after the {@code @} in the script's file name
     * @param modelId   the model whose scripts to run; resolved to its parent the same way {@link #begin} does
     * @param arguments the values the script reads as {@code args[0..]}
     */
    public static void fireHook(String hook, ResourceLocation modelId, double... arguments) {
        if (modelId == null) {
            return;
        }
        Map<String, List<PackFunctionScript>> hooks = HOOKS_BY_MODEL.get(ModelIdUtil.getParentModelId(modelId));
        if (hooks == null || hooks.isEmpty()) {
            return;
        }
        List<PackFunctionScript> scripts = hooks.get(hook);
        if (scripts == null || scripts.isEmpty()) {
            return;
        }
        IValue[] values = new IValue[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            values[i] = new Constant(arguments[i]);
        }
        UserFunctionArguments.push(values);
        try {
            for (PackFunctionScript script : scripts) {
                script.evaluate(GeckoLibCache.getInstance().parser);
            }
        } finally {
            UserFunctionArguments.pop();
        }
    }

    /**
     * A-16: whether {@code player}'s {@code player_init} scripts still have to run for {@code modelId}, consuming the
     * answer. Keyed by both, because the same player switches models and the same model is used by many players.
     */
    public static boolean consumeInitPending(String playerKey, ResourceLocation modelId) {
        if (playerKey == null || modelId == null) {
            return false;
        }
        return INITIALIZED.put(playerKey + '|' + modelId, "") == null;
    }

    /**
     * A-16: whether this frame advanced the clock for this player - the value upstream hands {@code player_update} as
     * {@code args[0]} ({@code CustomHumanoidEntity.java:215-218}). A frame that renders the same instant twice (this
     * port's world pass plus its HUD pass) answers {@code false} the second time, which is exactly what that argument
     * exists for.
     */
    public static boolean frameTicked(String playerKey, double animationTick) {
        if (playerKey == null) {
            return false;
        }
        Double previous = LAST_TICK.put(playerKey, animationTick);
        return previous == null || previous != animationTick;
    }

    public static void clearTicks() {
        LAST_TICK.clear();
    }

    private static PackFunctionScript body(String name) {
        Map<String, PackFunctionScript> table = CURRENT.get();
        return table == null ? null : table.get(name);
    }

    private static String stripExtension(String fileName) {
        return fileName.endsWith(EXTENSION) ? fileName.substring(0, fileName.length() - EXTENSION.length())
            : fileName;
    }

    private static void warnOnce(String key, String reason) {
        if (!WARNED.add(key)) {
            return;
        }
        ysmu.LOG.warn(
            "OpenYSM pack function '{}' cannot be evaluated in the 1.7.10 runtime: {}."
                + " It returns 0; further occurrences are not logged.",
            key,
            reason);
    }

    /** A {@code fn.<name>(...)} call site: resolves the body when it is evaluated, not when it is parsed. */
    private static final class Call implements IValue {

        private final String name;
        private final IValue[] arguments;

        private Call(String name, IValue[] arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        @Override
        public double get() {
            PackFunctionScript script = body(this.name);
            if (script == null) {
                warnOnce(PREFIX + this.name, "no pack function with this name was loaded for the rendered model");
                return 0;
            }
            UserFunctionArguments.push(this.arguments);
            try {
                return script.evaluate(GeckoLibCache.getInstance().parser);
            } finally {
                UserFunctionArguments.pop();
            }
        }
    }
}
