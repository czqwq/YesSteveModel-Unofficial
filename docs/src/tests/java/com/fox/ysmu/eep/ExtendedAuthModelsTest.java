package com.fox.ysmu.eep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

/**
 * The save data of the model-authorization port: the 1.7.10 counterpart of upstream's {@code AuthModelsCapability}
 * ({@code tmp/YesSteveModel-dev-1.20/.../capability/AuthModelsCapability.java:11-59}).
 * <p>
 * It is written into the player's save, so a table that refuses to load what it wrote would silently un-grant every
 * player on restart, and one that drops the rest of the table over one bad entry would do the same. Both are pinned
 * here.
 */
class ExtendedAuthModelsTest {

    private static final ResourceLocation MODEL_A = new ResourceLocation("ysmu", "model_a");
    private static final ResourceLocation MODEL_B = new ResourceLocation("ysmu", "wine_fox/01_taisho_maid");

    @Test
    void grantsAreAddedRemovedAndClearedLikeUpstreamsCapability() {
        ExtendedAuthModels auth = new ExtendedAuthModels();

        assertFalse(auth.containModel(MODEL_A), "a fresh table grants nothing");
        auth.addModel(MODEL_A);
        auth.addModel(MODEL_B);
        assertTrue(auth.containModel(MODEL_A));
        assertTrue(auth.containModel(MODEL_B));

        auth.removeModel(MODEL_A);
        assertFalse(auth.containModel(MODEL_A));
        assertTrue(auth.containModel(MODEL_B), "removing one model keeps the others");

        auth.clear();
        assertFalse(auth.containModel(MODEL_B));
        assertEquals(0, auth.getAuthModels().size());
    }

    @Test
    void setAuthModelsTakesACopyRatherThanTheCallersSet() {
        ExtendedAuthModels auth = new ExtendedAuthModels();
        java.util.Set<ResourceLocation> source = new java.util.LinkedHashSet<>();
        source.add(MODEL_A);

        auth.setAuthModels(source);
        source.add(MODEL_B);

        assertEquals(1, auth.getAuthModels().size(), "the table must not alias the caller's mutable set");
        assertTrue(auth.containModel(MODEL_A));
    }

    @Test
    void survivesASaveLoadRoundTrip() {
        // What /ysm auth write and a relog must both see. The key identifies the model as the packet carries it, so the
        // id has to survive verbatim - the encoder writes ResourceLocation#toString and the decoder parses it back.
        ExtendedAuthModels saved = new ExtendedAuthModels();
        saved.addModel(MODEL_A);
        saved.addModel(MODEL_B);

        NBTTagCompound tag = new NBTTagCompound();
        saved.saveNBTData(tag);
        assertTrue(tag.hasKey(ExtendedAuthModels.EXT_PROP_NAME));

        ExtendedAuthModels loaded = new ExtendedAuthModels();
        loaded.loadNBTData(tag);

        assertEquals(2, loaded.getAuthModels().size());
        assertTrue(loaded.containModel(MODEL_A));
        assertTrue(loaded.containModel(MODEL_B), "a grouped model id (wine_fox/01_taisho_maid) must round-trip too");
    }

    @Test
    void anEntryThatIsNotAStringDoesNotLoseTheRestOfTheTable() {
        // A hand-edited or older save can hold a non-string element. The reader must absorb it per entry instead of
        // throwing out of the player's NBT read - and 1.7.10's reader does not throw at all:
        // NBTTagList.getStringTagAt (NBTTagList.java:194-206) falls back to NBTBase.toString() for any tag whose id
        // is not 8, so a non-string element becomes junk *text* rather than a ClassCastException. (1.7.10's
        // ResourceLocation(String) then validates nothing - ResourceLocation.java:27-45 - so the junk is stored
        // verbatim and simply never matches a real grant.)
        //
        // The malformed list has to be built by hand: NBTTagList.appendTag (NBTTagList.java:91-101) only records the
        // FIRST tag's type and *drops* every later tag whose type differs (logging "Adding mismatching tag types to
        // tag list"), so a non-string element cannot be put behind a string one through the public API. The
        // elements live in the private tagList, so a mixed list can only be built by writing that field.
        NBTTagList list = new NBTTagList();
        setTagType(list, (byte) 8);
        appendRaw(list, new NBTTagCompound());
        appendRaw(list, new NBTTagString(MODEL_A.toString()));
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(ExtendedAuthModels.EXT_PROP_NAME, list);

        ExtendedAuthModels loaded = new ExtendedAuthModels();
        loaded.loadNBTData(tag);

        // The non-string element survives the read as a junk id (getStringTagAt falls back to toString(),
        // NBTTagList.java:194-206, and 1.7.10's ResourceLocation validates nothing); it must not cost the table its
        // real entry.
        assertEquals(2, loaded.getAuthModels().size());
        assertTrue(loaded.containModel(MODEL_A), "one unreadable element must not cost the rest of the table");
    }

    /** Writes {@code NBTTagList.tagType}, the serialisation hint a save file carries. */
    private static void setTagType(NBTTagList list, byte type) {
        try {
            java.lang.reflect.Field field = NBTTagList.class.getDeclaredField("tagType");
            field.setAccessible(true);
            field.setByte(list, type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("NBTTagList.tagType is no longer reachable; rebuild this fixture", e);
        }
    }

    /** Appends to the private element list, bypassing the type check {@code appendTag} enforces. */
    @SuppressWarnings("unchecked")
    private static void appendRaw(NBTTagList list, net.minecraft.nbt.NBTBase element) {
        try {
            java.lang.reflect.Field field = NBTTagList.class.getDeclaredField("tagList");
            field.setAccessible(true);
            ((java.util.List<net.minecraft.nbt.NBTBase>) field.get(list)).add(element);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("NBTTagList.tagList is no longer reachable; rebuild this fixture", e);
        }
    }

    @Test
    void anAbsentKeyLeavesTheInMemoryTableAlone() {
        ExtendedAuthModels loaded = new ExtendedAuthModels();
        loaded.addModel(MODEL_A);

        loaded.loadNBTData(new NBTTagCompound());

        assertTrue(loaded.containModel(MODEL_A), "an absent key leaves the in-memory table alone");
    }

    @Test
    void copyFromIsAnIndependentCopy() {
        // PlayerEvent.Clone copies the properties (event/CommonEventHandler.copyPlayerProperties); sharing the set
        // would let the new player object mutate the old one's table.
        ExtendedAuthModels source = new ExtendedAuthModels();
        source.addModel(MODEL_A);

        ExtendedAuthModels copy = new ExtendedAuthModels();
        copy.copyFrom(source);
        source.addModel(MODEL_B);

        assertTrue(copy.containModel(MODEL_A));
        assertFalse(copy.containModel(MODEL_B));
    }

    @Test
    void aGrantIsKeyedByTheFullIdTheClientSends() {
        // The packet carries ysmu:<model id>; a bare path is a different ResourceLocation and must not match, or
        // /ysm auth would authorize something the selection point never looks up.
        ExtendedAuthModels auth = new ExtendedAuthModels();
        auth.addModel(new ResourceLocation("model_a"));

        assertFalse(auth.containModel(MODEL_A));
    }
}
