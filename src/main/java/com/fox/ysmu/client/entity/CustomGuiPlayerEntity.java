package com.fox.ysmu.client.entity;

import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.mojang.authlib.GameProfile;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.audio.MolangSoundPlayback;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.roaming.ClientRoamingKeys;
import com.fox.ysmu.client.roaming.ClientRoamingStore;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;

import cpw.mods.fml.common.FMLCommonHandler;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.core.controller.AnimationController;

/**
 * Screen-owned preview, following upstream client/gui/CustomGuiPlayerEntity.java:21-27,54-88.
 * The query subject is a real, stationary fake player; the roaming owner is not that player.
 * Neither posing this entity nor evaluating its model changes the world's player or processor.
 * <p>
 * <b>Why {@link #getPlayer()} answers {@code null}.</b> Everything the engine asks this animatable is answered off
 * {@link #getQueryPlayer()}: the coded player state machine ({@code OpenYsmPlayerControllerRuntime#tryApply:73}), the
 * parser values the model is posed with ({@code CustomPlayerModel#setLivingAnimations:186-201}) and the MoLang scope
 * it accumulates ({@code MolangPhysicsRuntime.ScopeKey} keys by that player's UUID). A preview has no player of its
 * own, so it gets a stationary stand-in - and answering {@code null} from {@link #getPlayer()} is what routes its
 * controllers to the three GUI preview channels: {@code AnimationManager#playPreviewChannel:313-317} answers
 * {@code STOP} for anything that has a player, so a preview answering otherwise would run the player's state machine
 * against a stand-in that never walks (see {@code AnimationManager#predicateCap:233-240}). This is deliberate and
 * load-bearing; do not point it at a real player without re-checking both of those branches.
 */
public final class CustomGuiPlayerEntity extends CustomPlayerEntity {
    private EntityPlayer executionOwner;
    private EntityPlayer queryPlayer;
    private final CustomPlayerModel model = new CustomPlayerModel();
    private final GeoReplacedEntityRenderer<CustomGuiPlayerEntity> renderer =
        new GeoReplacedEntityRenderer<CustomGuiPlayerEntity>(model, this) {};

    static {
        // A class-level fetcher does not retain closed screens, unlike one capturing every preview instance.
        AnimationController.addModelFetcher(animatable -> animatable instanceof CustomGuiPlayerEntity preview
            ? preview.model : null);
    }

    public CustomGuiPlayerEntity() {
        getFactory().getOrCreateAnimationData(hashCode()).shouldPlayWhilePaused = true;
    }

    /**
     * Prepares this preview for a catalog tile or the settings panel's form preview: the query subject is a stationary
     * stand-in for the screen, not the player whose roaming values the tile reads.
     * <p>
     * A tile consequently answers {@link CustomPlayerEntity#getPlayer()} with {@code null} - it never sets the entity
     * field this class inherits - and that is exactly what routes its controllers to the three GUI preview channels:
     * {@code AnimationManager#playPreviewChannel:313-317} answers {@code STOP} for anything that has a player, so a
     * tile answering otherwise would run the player's state machine against a stand-in that never walks.
     */
    public void prepare(EntityPlayer owner, ResourceLocation baseModelId) {
        executionOwner = owner;
        if (queryPlayer == null || queryPlayer.worldObj != Minecraft.getMinecraft().theWorld) {
            queryPlayer = new net.minecraft.client.entity.EntityOtherPlayerMP(Minecraft.getMinecraft().theWorld,
                new GameProfile(UUID.randomUUID(), "ysm_form_preview"));
            queryPlayer.onGround = true;
        }
        setMainModel(ModelIdUtil.getMainId(baseModelId));
    }

    /**
     * Releases this preview: the event-bus ticker that would otherwise own its animation data indefinitely, its sound
     * ownership, its references to the players it was drawing for, the controller runtime state keyed by the
     * stand-in query player it created itself, and the stand-in's MoLang scope.
     * <p>
     * <b>The MoLang scope has to go too.</b> The engine keys a scope by {@code (entity UUID, model id)}
     * ({@code MolangPhysicsRuntime.ScopeKey}), this preview's entity is a freshly created stand-in with a random UUID, and
     * the scope is kept in {@code MolangPhysicsRuntime.STATES} after {@code end()} for the whole session. Nothing else
     * drops it - {@code ClientModelManager.clearRuntimeModelCaches} clears the whole table on a re-sync, not per screen -
     * so every opening of a previewing screen used to leave one empty scope behind until the next model reload.
     */
    public void close() {
        MolangSoundPlayback.releaseOwner(this);
        AnimationData data = getFactory().getOrCreateAnimationData(hashCode());
        if (data.ticker != null) {
            FMLCommonHandler.instance().bus().unregister(data.ticker);
            data.ticker = null;
        }
        executionOwner = null;
        if (queryPlayer != null) {
            OpenYsmPlayerControllerRuntime.clearPlayer(queryPlayer.getUniqueID());
            // There is exactly one model installed at a time for a preview, and its scope is keyed by the stand-in's UUID;
            // clearPlayer(scope) drops every model scope that UUID owns.
            MolangPhysicsRuntime.clearPlayer(queryPlayer.getUniqueID());
        }
        queryPlayer = null;
    }

    @Override
    public EntityPlayer getPlayer() { return null; }

    public EntityPlayer getExecutionOwner() { return executionOwner; }
    @Override
    public EntityPlayer getQueryPlayer() { return queryPlayer; }
    public GeoReplacedEntityRenderer<CustomGuiPlayerEntity> getPreviewRenderer() { return renderer; }

    @Override
    public EntityLivingBase getMolangEntity() { return queryPlayer; }

    /**
     * What this preview should read for {@code v.*}/{@code v.roaming.*}.
     * <p>
     * 上游的设置面板直接读本地玩家自己的运行时（{@code AnimationRouletteScreen.java:230-258}），没有"预览"这一层；
     * 移植的设置屏仍画 {@code CustomGuiPlayerEntity} 预览（1.7.10 固定管线的移植特有 UI），但预览必须显示**真实**设置值，
     * 所以当它画的就是 owner 当前穿戴的模型时，直接返回 owner 真实作用域的只读快照
     * （{@code MolangPhysicsRuntime.snapshotVariables}）；普通 {@code v.*} 与 {@code v.roaming.*} 都在里面。
     * <p>
     * 画的是别的模型时（模型选择屏的目录格子、NPC/贴图预览），owner 的当前模型作用域不是这一格的上下文，退回原有的
     * {@link ClientRoamingStore#valuesFor} 查询。两者都为空时返回 {@code null} —— 这是本接口既有的"没有宿主变量"约定，
     * {@code MolangPhysicsRuntime.applyScopeVariables} 对 null 直接跳过。
     */
    @Override
    public Map<String, Double> getMolangVariables() {
        EntityPlayer owner = executionOwner;
        if (owner == null) {
            return null;
        }
        ResourceLocation mainId = getMolangModelId();
        ResourceLocation previewed = ModelIdUtil.getModelIdFromMainId(mainId);
        ExtendedModelInfo info = ExtendedModelInfo.get(owner);
        if (info != null && previewed.equals(info.getModelId())) {
            Map<String, Double> snapshot = MolangPhysicsRuntime.snapshotVariables(owner, mainId);
            if (!snapshot.isEmpty()) {
                return snapshot;
            }
        }
        Map<String, Double> stored = ClientRoamingStore.valuesFor(owner.getUniqueID(),
            ClientRoamingKeys.keyFor(previewed));
        return stored.isEmpty() ? null : stored;
    }

    @Override
    public boolean firesHumanoidHooks() { return false; }

    /**
     * A preview presents one stable pose for the whole frame, so it declares itself immutable.
     * <p>
     * Upstream, verbatim: {@code client/gui/CustomGuiPlayerEntity.java:43-46} returns {@code true} and thereby
     * bypasses {@code AnimatableEntity:456-461}, whose default answers {@code false} for the inventory, first-person
     * and paper-doll passes. With this set, the engine refuses to advance the animation a second time inside one frame
     * and reuses the pose that frame already produced ({@code AnimatableEntity:341-389} and {@code :265-277}) - which
     * is what stops a pack's {@code preview} clip and its own {@code pre_parallelN} state machine from writing
     * opposite scales onto the same bone within one frame, the flicker this exists to remove.
     * <p>
     * <b>Upstream's neighbour is deliberately not copied.</b> {@code client/entity/CustomPlayerEntity.java:43-51}
     * wraps the same answer in an Iris-shadow exception for the local player in first person. This port models neither
     * {@code irisShadow} nor {@code level} ({@code software.bernie.geckolib3.geo.RenderContext}), so there is nothing
     * for that exception to test, and inventing a 1.7.10 stand-in would be a new predicate rather than a port.
     */
    @Override
    public boolean determineImmutableContext(software.bernie.geckolib3.geo.RenderContext context) {
        return true;
    }
}
