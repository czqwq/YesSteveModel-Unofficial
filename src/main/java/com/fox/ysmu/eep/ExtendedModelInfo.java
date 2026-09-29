package com.fox.ysmu.eep; // 建议放在 eep 包下

import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SyncModelInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;

import com.fox.ysmu.Config;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

public class ExtendedModelInfo implements IExtendedEntityProperties {

    // 1. 唯一的标识符
    public final static String EXT_PROP_NAME = "ysmu_ModelInfo";

    // 用于网络同步和服务器端逻辑。
    // CU-12: End 门重生时 Forge 会直接复用同一个 EEP 实例（EntityPlayer.java:2274-2277），
    // 因此该引用必须在 init(Entity, World) 里重新绑定，否则会悬空指向旧玩家对象。
    // 注意：全类没有其它地方读取 player，保留它是为了保持 EEP 的既有形状。
    private EntityPlayer player;

    // 2. 将原 ModelInfoCapability 的字段和方法直接移到这里
    private ResourceLocation modelId = new ResourceLocation(
        ysmu.MODID,
        ModelIdUtil.getInternalModelId(Config.DEFAULT_MODEL_ID));
    private ResourceLocation selectTexture = ModelIdUtil.getSubModelId(modelId, Config.DEFAULT_MODEL_TEXTURE);
    private String animation = "idle";
    private boolean playAnimation = false;
    private boolean dirty; // dirty 标志可以保留，用于客户端渲染逻辑判断是否需要更新

    public ExtendedModelInfo(EntityPlayer player) {
        this.player = player;
    }

    public void setModelAndTexture(ResourceLocation modelId, ResourceLocation selectTexture) {
        // N-1（EEP 半边）：拒绝 null，而不是把它存进 EEP。调用方 network/message/SetModelAndTexture.java:65-66
        // 会把空串映射成 null 再传进来；存 null 会让 saveNBTData 的 toString() 在下次存档/登出时 NPE
        // （该异常只能在本文件关闭），也会让渲染与贴图查找拿到 null。语义：整次调用被忽略，
        // 保留上一次的有效选择（而不是把玩家的模型/贴图置空）。
        if (modelId == null || selectTexture == null) {
            ysmu.LOG.warn(
                "Ignoring YSM model selection with a null id (model={}, texture={}); keeping the previous selection",
                modelId,
                selectTexture);
            return;
        }
        this.modelId = modelId;
        this.selectTexture = selectTexture;
        markDirty();
    }

    public void copyFrom(ExtendedModelInfo source) {
        // N-1：防御性拷贝 —— 即使 source 来自旧实例/异常路径，也不把 null 带进本实例。
        if (source.modelId != null) {
            this.modelId = source.modelId;
        }
        if (source.selectTexture != null) {
            this.selectTexture = source.selectTexture;
        }
        this.animation = source.animation;
        this.playAnimation = source.playAnimation;
        markDirty();
    }

    public ResourceLocation getModelId() {
        return modelId;
    }

    public ResourceLocation getSelectTexture() {
        return selectTexture;
    }

    public void setSelectTexture(ResourceLocation selectTexture) {
        // N-1：同 setModelAndTexture，拒绝 null 并保留上一次的有效贴图。
        if (selectTexture == null) {
            ysmu.LOG.warn("Ignoring YSM texture selection with a null id; keeping the previous texture");
            return;
        }
        this.selectTexture = selectTexture;
        markDirty();
    }

    public void playAnimation(String animation) {
        this.animation = animation;
        this.playAnimation = true;
        markDirty();
    }

    public void stopAnimation() {
        this.playAnimation = false;
        markDirty();
    }

    public String getAnimation() {
        return animation;
    }

    public boolean isPlayAnimation() {
        return playAnimation;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    // 3. 静态辅助方法
    /**
     * 将 EEP 注册到玩家身上
     */
    public static void register(EntityPlayer player) {
        player.registerExtendedProperties(EXT_PROP_NAME, new ExtendedModelInfo(player));
    }

    /**
     * 从玩家身上获取 EEP 实例
     */
    public static ExtendedModelInfo get(EntityPlayer player) {
        return (ExtendedModelInfo) player.getExtendedProperties(EXT_PROP_NAME);
    }

    // 4. 实现 IExtendedEntityProperties 接口的方法

    /**
     * 将数据保存到 NBT
     * 这个方法会把所有字段打包到一个独立的 NBTTagCompound 中，避免命名冲突
     */
    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagCompound properties = new NBTTagCompound();
        // N-1：即使将来有绕过两个 setter 的路径让字段为 null（旧实例、反射、未来的新调用方），
        // 存档也不能 NPE —— 空串会被 loadNBTData 读成空 ResourceLocation，而不是抛异常。
        properties.setString("model_id", this.modelId == null ? "" : this.modelId.toString());
        properties.setString("select_texture", this.selectTexture == null ? "" : this.selectTexture.toString());
        properties.setString("animation", this.animation);
        properties.setBoolean("play_animation", this.playAnimation);

        compound.setTag(EXT_PROP_NAME, properties);
    }

    /**
     * 从 NBT 读取数据
     */
    @Override
    public void loadNBTData(NBTTagCompound compound) {
        if (compound.hasKey(EXT_PROP_NAME)) {
            NBTTagCompound properties = compound.getCompoundTag(EXT_PROP_NAME);
            this.modelId = new ResourceLocation(properties.getString("model_id"));
            this.selectTexture = new ResourceLocation(properties.getString("select_texture"));
            this.animation = properties.getString("animation");
            this.playAnimation = properties.getBoolean("play_animation");
        }
    }

    @Override
    public void init(Entity entity, World world) {
        // CU-12: 重新绑定玩家引用。End 门重生时 Forge 直接复用同一实例
        // （EntityPlayer.java:2274-2277 的 this.extendedProperties = p_71049_1_.extendedProperties 后逐个 init），
        // 若不在此更新，#player 会一直指向旧玩家对象。
        if (entity instanceof EntityPlayer) {
            this.player = (EntityPlayer) entity;
        }
    }
}
