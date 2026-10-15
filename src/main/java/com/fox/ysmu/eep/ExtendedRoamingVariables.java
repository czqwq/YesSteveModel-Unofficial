package com.fox.ysmu.eep;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;

import com.fox.ysmu.model.roaming.ModelRoamingKey;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.ysmu;

/**
 * The server's per-player roaming-variable save data, keyed by player and then by
 * {@link ModelRoamingKey}.
 * <p>
 * A <em>roaming variable</em> is a named float that belongs to one model of one player, read by packs as
 * {@code v.roaming.<name>} and written by the model's {@code 模型设置} panel. Upstream keeps the same data in
 * {@code RoamingVariableStore} behind a player capability, serialized as a compound tag of
 * {@code <model hash> -> <variable name> -> float}; this class reproduces that shape for Forge 1.7.10, where the
 * equivalent of a capability is {@link IExtendedEntityProperties}.
 * <p>
 * The client keeps its own copy in {@code com.fox.ysmu.client.roaming.ClientRoamingStore}; this one is the authority.
 * Both apply the bounds from {@link ModelRoamingLimits}, so a client cannot grow a save file without bound.
 */
public class ExtendedRoamingVariables implements IExtendedEntityProperties {

    /** Unique identifier, also the key under which the data is stored in the player's NBT. */
    public final static String EXT_PROP_NAME = "ysmu_RoamingVariables";

    /**
     * Access ordered so the least recently used model can be evicted when a player reaches the model bound, which is
     * upstream's behaviour: settings for a model the player stopped using are the first to go.
     */
    private final Map<Integer, Map<String, Float>> byModel = new LinkedHashMap<>(16, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Map<String, Float>> eldest) {
            return size() > ModelRoamingLimits.MAX_MODELS_PER_PLAYER;
        }
    };

    /** The variables of one model, live and mutable so a caller can merge a change into it. */
    public Map<String, Float> values(int roamingKey) {
        Map<String, Float> values = byModel.get(roamingKey);
        if (values == null) {
            values = new ConcurrentHashMap<>();
            byModel.put(roamingKey, values);
        }
        return values;
    }

    /** Whether anything at all is stored for a model, without creating an entry for it. */
    public boolean hasValues(int roamingKey) {
        Map<String, Float> values = byModel.get(roamingKey);
        return values != null && !values.isEmpty();
    }

    /**
     * Stores one variable.
     *
     * @return whether it was stored; an unusable name, a non-finite value, or a model already at the variable bound
     *         leaves the store untouched
     */
    public boolean set(int roamingKey, @Nullable String name, double value) {
        if (!ModelRoamingLimits.isAcceptableName(name) || !ModelRoamingLimits.isAcceptableValue(value)) {
            return false;
        }
        Map<String, Float> values = values(roamingKey);
        if (values.size() >= ModelRoamingLimits.MAX_VARIABLES_PER_MODEL && !values.containsKey(name)) {
            return false;
        }
        values.put(name, (float) value);
        return true;
    }

    /** Stores every acceptable entry of {@code changes}, skipping the rest. */
    public void apply(int roamingKey, @Nullable Map<String, ? extends Number> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, ? extends Number> entry : changes.entrySet()) {
            Number value = entry.getValue();
            if (value != null) {
                set(roamingKey, entry.getKey(), value.doubleValue());
            }
        }
    }

    /** Replaces everything stored for one model, as a client's full state for it. */
    public void replace(int roamingKey, @Nullable Map<String, ? extends Number> values) {
        byModel.remove(roamingKey);
        apply(roamingKey, values);
    }

    /** The models this player currently has settings for. */
    public int[] retainedKeys() {
        int[] keys = new int[byModel.size()];
        int index = 0;
        for (Integer key : byModel.keySet()) {
            keys[index++] = key;
        }
        return keys;
    }

    /** Drops everything except the given models; a {@code null} or empty array drops everything. */
    public void trim(int[] retainedKeys) {
        if (retainedKeys == null || retainedKeys.length == 0) {
            byModel.clear();
            return;
        }
        java.util.Set<Integer> keep = new java.util.HashSet<>(retainedKeys.length * 2);
        for (int key : retainedKeys) {
            keep.add(key);
        }
        byModel.keySet()
            .removeIf(key -> !keep.contains(key));
    }

    // 3. 静态辅助方法

    /** Registers the save data on a player. */
    public static void register(EntityPlayer player) {
        player.registerExtendedProperties(EXT_PROP_NAME, new ExtendedRoamingVariables());
    }

    /**
     * Copies another store's contents into this one, for a respawn or dimension change that produced a new instance.
     * The namespaces are copied rather than shared, so the two players cannot write into each other's store.
     */
    public void copyFrom(ExtendedRoamingVariables source) {
        byModel.clear();
        for (Map.Entry<Integer, Map<String, Float>> entry : source.byModel.entrySet()) {
            byModel.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
        }
    }

    /** The save data of a player, or {@code null} when it was never registered. */
    @Nullable
    public static ExtendedRoamingVariables get(@Nullable EntityPlayer player) {
        return player == null ? null : (ExtendedRoamingVariables) player.getExtendedProperties(EXT_PROP_NAME);
    }

    // 4. 实现 IExtendedEntityProperties 接口的方法

    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagCompound store = new NBTTagCompound();
        for (Map.Entry<Integer, Map<String, Float>> model : byModel.entrySet()) {
            if (model.getValue()
                .isEmpty()) {
                continue;
            }
            NBTTagCompound variables = new NBTTagCompound();
            for (Map.Entry<String, Float> variable : model.getValue()
                .entrySet()) {
                variables.setFloat(variable.getKey(), variable.getValue());
            }
            store.setTag(ModelRoamingKey.toHex(model.getKey()), variables);
        }
        compound.setTag(EXT_PROP_NAME, store);
    }

    @Override
    public void loadNBTData(NBTTagCompound compound) {
        byModel.clear();
        if (!compound.hasKey(EXT_PROP_NAME)) {
            return;
        }
        NBTTagCompound store;
        try {
            store = compound.getCompoundTag(EXT_PROP_NAME);
        } catch (Exception e) {
            // A save written by a build that stored something else under this name must not brick the login.
            ysmu.LOG.warn("Ignoring unreadable YSM roaming-variable save data", e);
            return;
        }
        for (String keyText : store.func_150296_c()) {
            final int roamingKey;
            try {
                roamingKey = Integer.parseUnsignedInt(keyText, 16);
            } catch (NumberFormatException ignored) {
                // Not one of ours; skip it rather than losing the whole store over one bad entry.
                ysmu.LOG.debug("Skipping roaming-variable entry with an unreadable model key '{}'", keyText);
                continue;
            }
            NBTTagCompound variables;
            try {
                variables = store.getCompoundTag(keyText);
            } catch (Exception e) {
                continue;
            }
            for (String name : variables.func_150296_c()) {
                if (!ModelRoamingLimits.isAcceptableName(name)) {
                    continue;
                }
                double value = variables.getFloat(name);
                if (ModelRoamingLimits.isAcceptableValue(value)) {
                    set(roamingKey, name, value);
                }
            }
        }
    }

    @Override
    public void init(Entity entity, World world) {
        // Nothing to rebind: this store holds no reference to the player.
    }
}
