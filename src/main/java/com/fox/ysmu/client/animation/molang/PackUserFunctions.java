package com.fox.ysmu.client.animation.molang;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.ResourceLocation;

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

    private static final Map<ResourceLocation, Map<String, PackFunctionScript>> BY_MODEL = Maps.newConcurrentMap();
    private static final ThreadLocal<Map<String, PackFunctionScript>> CURRENT = new ThreadLocal<>();
    private static final Set<String> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private PackUserFunctions() {}

    /**
     * Parses and stores a model's script files. Must run on the client thread, and before the model is rendered.
     */
    public static void register(ResourceLocation modelId, Map<String, RawYsmModel.RawDataFile> files) {
        if (modelId == null || files == null || files.isEmpty()) {
            return;
        }
        Map<String, PackFunctionScript> scripts = Maps.newHashMap();
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
                if (hook > 0) {
                    // `name@hook.molang` is a hook script upstream; the pack may still call it as `fn.name(...)`.
                    scripts.putIfAbsent(name.substring(0, hook), script);
                }
            } catch (Exception | LinkageError e) {
                warnOnce(fileName, e.getClass()
                    .getSimpleName());
            }
        }
        if (!scripts.isEmpty()) {
            BY_MODEL.put(modelId, scripts);
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
            return;
        }
        CURRENT.set(BY_MODEL.get(ModelIdUtil.getParentModelId(mainModelId)));
    }

    public static void end() {
        CURRENT.remove();
    }

    public static void clear() {
        BY_MODEL.clear();
        CURRENT.remove();
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
