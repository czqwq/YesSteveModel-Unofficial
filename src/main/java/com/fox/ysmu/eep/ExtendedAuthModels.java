package com.fox.ysmu.eep;

import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;

import com.google.common.collect.Sets;

import cpw.mods.fml.common.FMLLog;

/**
 * The 1.7.10 counterpart of upstream's {@code AuthModelsCapability}
 * ({@code tmp/YesSteveModel-dev-1.20/.../capability/AuthModelsCapability.java:11-59}): the set of models one player is
 * authorized to select.
 * <p>
 * <b>Contract, ported from upstream.</b>
 * <ul>
 * <li><b>Granularity: per player, per model.</b> One set of model ids per player; there is no "all players" entry -
 * upstream's {@code /ysm auth <targets> ...} loops over resolved players and mutates each one's own capability
 * ({@code command/sub/AuthCommand.java:53-68}).</li>
 * <li><b>Persistent, on the player.</b> Upstream serializes the set into the player's NBT
 * ({@code AuthModelsCapability.java:42-59}), so a grant survives logout and restart; here that is the EEP's own
 * {@link #saveNBTData}/{@link #loadNBTData} pair, exactly like {@link ExtendedStarModels}.</li>
 * <li><b>Invalidation is explicit only.</b> Entries are removed by {@code auth remove}/{@code clear} or replaced
 * wholesale by {@code auth all}; nothing expires. (Upstream's parse failure path drops entries whose text is not a
 * model id - {@code AuthModelsCapability.java:50-58} - which is the same defensive read kept below.)</li>
 * <li><b>Identity is the model id.</b> Upstream keys by {@code Hash256} - the model's content identity
 * ({@code model/domain/Hash256.java} usage in {@code AuthCommand.java:52}). The port's model identity is the
 * {@code ResourceLocation} the client sends on selection ({@code client/ClientModelManager.java:1202-1204} builds
 * {@code ysmu:<internal id>} from the synced {@code ModelData.getModelId()}, and the server registers exactly that
 * internal id as the {@code CACHE_NAME_INFO} key, {@code model/format/OpenYsmFormat.java:69-89}). So a grant here is
 * name-stable: editing a model's contents does not lose its grant, while upstream's hash-keyed grant would. Recorded
 * as a port divergence, not a defect - the port has no content-addressed model id on the selection path.</li>
 * </ul>
 * <p>
 * This is a <b>different mechanism</b> from {@code api/ModelGuiApi}'s five-minute (viewer, target) grant, which only
 * authorizes opening a selection screen for an entity. Do not merge the two.
 */
public class ExtendedAuthModels implements IExtendedEntityProperties {

    // 1. 唯一的标识符
    public final static String EXT_PROP_NAME = "ysmu_AuthModels";

    // 2. 将原 AuthModelsCapability 的字段和方法直接移到这里
    private Set<ResourceLocation> authModels = Sets.newHashSet();

    public void addModel(ResourceLocation modelId) {
        authModels.add(modelId);
    }

    public void copyFrom(ExtendedAuthModels source) {
        // 创建一个新的 Set 副本，而不是直接引用：与 ExtendedStarModels.copyFrom 同款,
        // 否则旧玩家对象与新玩家对象会共享同一个可写表。
        this.authModels = Sets.newHashSet(source.authModels);
    }

    public void removeModel(ResourceLocation modelId) {
        authModels.remove(modelId);
    }

    public boolean containModel(ResourceLocation modelId) {
        return authModels.contains(modelId);
    }

    public Set<ResourceLocation> getAuthModels() {
        return authModels;
    }

    public void setAuthModels(Set<ResourceLocation> authModels) {
        this.authModels = Sets.newHashSet(authModels);
    }

    public void clear() {
        authModels.clear();
    }

    // 3. 静态辅助方法
    /**
     * 将 EEP 注册到玩家身上。
     * <p>
     * <b>Registration point:</b> like the other three YSMU EEPs this has to run from
     * {@code EntityEvent.EntityConstructing} ({@code event/CommonEventHandler.java:122-127}) - i.e. <em>before</em>
     * the player compound is read - because Forge only calls {@link #loadNBTData} for properties already registered
     * when the entity is constructed ({@code EntityPlayer.readEntityFromNBT} iterates the registered map). A table
     * attached lazily on first use would therefore never see the grants it saved last session.
     */
    public static void register(EntityPlayer player) {
        player.registerExtendedProperties(EXT_PROP_NAME, new ExtendedAuthModels());
    }

    /**
     * 从玩家身上获取 EEP 实例，没有注册时返回 {@code null}。
     * <p>
     * Callers must treat {@code null} as "this build did not attach the table", never as "the player has no
     * grants": the distinction is what keeps a missing registration from locking every player out of every model.
     * See {@code api/ModelAuthorization#canSelect}.
     */
    public static ExtendedAuthModels get(EntityPlayer player) {
        return (ExtendedAuthModels) player.getExtendedProperties(EXT_PROP_NAME);
    }

    // 4. 实现 IExtendedEntityProperties 接口的方法

    /**
     * Writes the granted model ids as one string list, the same shape upstream writes
     * ({@code AuthModelsCapability.java:42-48}).
     */
    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagList listTag = new NBTTagList();
        for (ResourceLocation modelId : authModels) {
            listTag.appendTag(new NBTTagString(modelId.toString()));
        }
        compound.setTag(EXT_PROP_NAME, listTag);
    }

    /**
     * Reads the list back. The try/catch is defensive: it exists so that an unreadable entry can never be the thing
     * that costs the player a login, the same place upstream's read is defensive ({@code
     * AuthModelsCapability.java:50-58}, which drops entries its {@code Hash256.parse} rejects because an older
     * path-based format is intentionally not migrated) and the same shape {@link ExtendedStarModels} uses.
     * <p>
     * What 1.7.10 actually does with a malformed entry, measured against the vanilla tag API rather than assumed:
     * <ul>
     * <li>a <b>mixed</b> list cannot even be built in memory - {@code NBTTagList.appendTag} records only the first
     * tag's type and drops every later tag that differs ({@code NBTTagList.java:91-101}), so this writer always
     * produces a pure string list and a mixed one can only arrive from a hand-edited or foreign save;</li>
     * <li>{@code getStringTagAt} does <b>not</b> throw for a non-string element: it returns {@code toString()} of the
     * element ({@code NBTTagList.java:195-205}), so such an entry becomes a junk id instead of an exception;</li>
     * <li>{@code ResourceLocation(String)} performs no validation at all
     * ({@code build/rfg/minecraft-src/java/net/minecraft/util/ResourceLocation.java:27-45} splits on {@code :} and
     * lower-cases the domain), so a junk id is stored verbatim and simply never matches a grant the client sends.</li>
     * </ul>
     * A junk entry is therefore harmless here, and the catch is kept rather than deleted because it must not depend on
     * that staying true.
     */
    @Override
    public void loadNBTData(NBTTagCompound compound) {
        if (compound.hasKey(EXT_PROP_NAME)) {
            NBTTagList listTag = (NBTTagList) compound.getTag(EXT_PROP_NAME);
            this.authModels.clear();
            for (int i = 0; i < listTag.tagCount(); i++) {
                try {
                    authModels.add(new ResourceLocation(listTag.getStringTagAt(i)));
                } catch (Exception e) {
                    FMLLog.warning("[ysmu] Skipping an unreadable Authorized Model entry at index " + i);
                }
            }
        }
    }

    @Override
    public void init(Entity entity, World world) {
        // 初始化时调用，此处无需操作
    }
}
