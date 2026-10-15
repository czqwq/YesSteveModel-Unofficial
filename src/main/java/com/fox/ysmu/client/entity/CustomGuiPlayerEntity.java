package com.fox.ysmu.client.entity;

import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.mojang.authlib.GameProfile;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.roaming.ClientRoamingKeys;
import com.fox.ysmu.client.roaming.ClientRoamingStore;
import com.fox.ysmu.util.ModelIdUtil;

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
     * ownership, its references to the players it was drawing for, and the controller runtime state keyed by the
     * stand-in query player it created itself.
     */
    public void close() {
        com.fox.ysmu.client.audio.MolangSoundPlayback.releaseOwner(this);
        software.bernie.geckolib3.core.manager.AnimationData data = getFactory()
            .getOrCreateAnimationData(hashCode());
        if (data.ticker != null) {
            cpw.mods.fml.common.FMLCommonHandler.instance().bus().unregister(data.ticker);
            data.ticker = null;
        }
        executionOwner = null;
        if (queryPlayer != null) com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime
            .clearPlayer(queryPlayer.getUniqueID());
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

    @Override
    public Map<String, Double> getMolangVariables() {
        return executionOwner == null ? null : ClientRoamingStore.valuesFor(executionOwner.getUniqueID(),
            ClientRoamingKeys.keyFor(ModelIdUtil.getModelIdFromMainId(getMolangModelId())));
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
