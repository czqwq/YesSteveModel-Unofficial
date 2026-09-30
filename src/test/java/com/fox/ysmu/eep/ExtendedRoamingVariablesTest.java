package com.fox.ysmu.eep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.roaming.ModelRoamingKey;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;

/**
 * The save data of the roaming-variable port (see {@code .agent/phase15-roaming-variables.md}, Milestone 4). It is
 * written into the player's save on every logout, so a store that refuses to load a save it wrote itself would cost a
 * player their settings, and one that grows without bound would grow the save file.
 */
class ExtendedRoamingVariablesTest {

    private static final int MODEL_A = 0x01234567;
    private static final int MODEL_B = 0x76543210;

    @Test
    void survivesASaveLoadRoundTrip() {
        ExtendedRoamingVariables saved = new ExtendedRoamingVariables();
        saved.set(MODEL_A, "v.roaming.player_size", 2.5d);
        saved.set(MODEL_A, "v.roaming.elytra", 1d);
        saved.set(MODEL_B, "v.roaming.cloth", 0d);

        NBTTagCompound tag = new NBTTagCompound();
        saved.saveNBTData(tag);
        assertTrue(tag.hasKey(ExtendedRoamingVariables.EXT_PROP_NAME));

        ExtendedRoamingVariables loaded = new ExtendedRoamingVariables();
        loaded.loadNBTData(tag);

        assertEquals(2.5f, loaded.values(MODEL_A).get("v.roaming.player_size"));
        assertEquals(1f, loaded.values(MODEL_A).get("v.roaming.elytra"));
        // A value of zero must survive too: it is a real setting (elytra off), not "absent".
        assertEquals(0f, loaded.values(MODEL_B).get("v.roaming.cloth"));
        assertTrue(loaded.hasValues(MODEL_B));
    }

    @Test
    void anUnreadableEntryDoesNotLoseTheRestOfTheSave() {
        NBTTagCompound store = new NBTTagCompound();
        NBTTagCompound good = new NBTTagCompound();
        good.setFloat("v.roaming.player_size", 3f);
        store.setTag(ModelRoamingKey.toHex(MODEL_A), good);
        // A key that is not one of our hexadecimal model keys, and one that is not even a compound.
        NBTTagCompound junk = new NBTTagCompound();
        junk.setFloat("v.roaming.x", 1f);
        store.setTag("not-a-model-key", junk);
        store.setTag(ModelRoamingKey.toHex(MODEL_B), new NBTTagCompound());

        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(ExtendedRoamingVariables.EXT_PROP_NAME, store);

        ExtendedRoamingVariables loaded = new ExtendedRoamingVariables();
        loaded.loadNBTData(tag);

        assertEquals(3f, loaded.values(MODEL_A).get("v.roaming.player_size"));
        assertFalse(loaded.hasValues(MODEL_B), "an empty namespace must not be remembered as a setting");
    }

    @Test
    void aMissingCompoundLoadsAsEmptyInsteadOfThrowing() {
        ExtendedRoamingVariables loaded = new ExtendedRoamingVariables();
        loaded.loadNBTData(new NBTTagCompound());

        // retainedKeys() first: values() is the mutating accessor and would create the namespace it is asked about.
        assertEquals(0, loaded.retainedKeys().length);
        assertTrue(loaded.values(MODEL_A).isEmpty());
    }

    @Test
    void rejectsNamesAndValuesTheStoresWouldRefuse() {
        ExtendedRoamingVariables store = new ExtendedRoamingVariables();

        assertFalse(store.set(MODEL_A, "player_size", 1d), "a bare name must be normalised before it gets here");
        assertFalse(store.set(MODEL_A, "v.roaming.nan", Double.NaN));
        assertFalse(store.set(MODEL_A, "v.roaming.inf", Double.POSITIVE_INFINITY));
        assertFalse(store.set(MODEL_A, null, 1d));
        assertTrue(store.values(MODEL_A).isEmpty());

        assertTrue(store.set(MODEL_A, "v.roaming.ok", 1d));

        // A full state replaces the namespace, so a variable the client no longer sends is gone.
        store.replace(MODEL_A, map("v.roaming.other", 4d));
        assertFalse(store.values(MODEL_A).containsKey("v.roaming.ok"));
        assertEquals(4f, store.values(MODEL_A).get("v.roaming.other"));
    }

    @Test
    void aModelCannotGrowPastTheVariableBound() {
        ExtendedRoamingVariables store = new ExtendedRoamingVariables();
        for (int i = 0; i < ModelRoamingLimits.MAX_VARIABLES_PER_MODEL + 10; i++) {
            store.set(MODEL_A, "v.roaming.v" + i, i);
        }
        assertEquals(
            ModelRoamingLimits.MAX_VARIABLES_PER_MODEL,
            store.values(MODEL_A).size());
        // Overwriting an existing name still works at the bound.
        assertTrue(store.set(MODEL_A, "v.roaming.v0", 123d));
        assertEquals(123f, store.values(MODEL_A).get("v.roaming.v0"));
    }

    @Test
    void trimDropsTheModelsThePlayerNoLongerUses() {
        ExtendedRoamingVariables store = new ExtendedRoamingVariables();
        store.set(MODEL_A, "v.roaming.a", 1d);
        store.set(MODEL_B, "v.roaming.b", 2d);

        store.trim(new int[] { MODEL_B });

        assertFalse(store.hasValues(MODEL_A));
        assertTrue(store.hasValues(MODEL_B));
        assertEquals(1, store.retainedKeys().length);

        store.trim(new int[0]);
        assertTrue(store.values(MODEL_B).isEmpty());
    }

    private static Map<String, Double> map(Object... pairs) {
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return out;
    }
}
