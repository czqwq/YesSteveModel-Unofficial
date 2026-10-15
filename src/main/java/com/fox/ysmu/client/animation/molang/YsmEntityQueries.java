package com.fox.ysmu.client.animation.molang;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttribute;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovementInput;

import com.fox.ysmu.client.animation.EntityQueryBindings;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * The A5 {@code ysm.*} names whose value belongs to the frame entity itself: {@code air_supply},
 * {@code attack_time}, {@code attack_damage}, {@code movement_speed}, {@code knockback_resistance},
 * {@code xxa}, {@code yya}, {@code zza}, {@code input_horizontal}, {@code ground_speed2} and {@code time_delta}.
 *
 * <p>Each value is upstream's, with the upstream line named at the method and the 1.7.10 substitute in the comment.
 * The frame entity comes from {@link MolangFrameContext}, which is what makes a sub-entity frame answer about itself
 * (upstream's typed {@code entityVar} / {@code livingEntityVar} / {@code playerVar} bindings do the same); an
 * out-of-frame call answers 0.
 *
 * <p><b>What is not here.</b> Upstream's {@code xxa}/{@code yya}/{@code zza} for a <em>remote</em> player come from its
 * own player-state protocol ({@code capability/PlayerStateTracker.java:64-66},
 * {@code ServerDrivenPlayerPropertiesTracker.java:83-85} - {@code moveXQ7}/{@code moveYQ7}/{@code moveZQ7}). 1.7.10
 * does not sync an entity's movement input at all, and this task explicitly does not add a network field for it, so a
 * remote player answers 0 and says so here rather than guessing.
 */
public final class YsmEntityQueries {

    private YsmEntityQueries() {}

    /**
     * {@code entityVar("air_supply", ctx -> ctx.entity().getAirSupply())}, upstream {@code YSMBinding.java:111}. The
     * 1.7.10 field is {@code Entity#getAir()} (ticks of breath left, 300 at full); it is declared on {@code Entity}, so
     * every frame entity answers it, exactly like upstream's {@code entityVar}.
     */
    public static double airSupply() {
        Entity entity = MolangFrameContext.getFrameEntity();
        return entity == null ? 0.0D : (double) entity.getAir();
    }

    /**
     * {@code livingEntityVar("attack_time", ctx -> ctx.entity().getAttackAnim(ctx.animationEvent().getPartialTick()))},
     * upstream {@code YSMBinding.java:149} - the swinging arm's progress, 0 to 1, interpolated by the partial tick.
     * 1.7.10's counterpart is {@code EntityLivingBase#getSwingProgress(partialTicks)}
     * ({@code EntityLivingBase.java:2178}), the same interpolated swing fraction the vanilla arm renderer uses; the
     * partial tick is the client's current render partial tick, the value the port's other camera/query readers use
     * ({@code CameraRollQuery#interpolatedRoll}).
     */
    public static double attackTime() {
        EntityLivingBase living = MolangFrameContext.getEntity();
        return living == null ? 0.0D : (double) living.getSwingProgress(partialTick());
    }

    /**
     * {@code livingEntityVar("is_player", ctx -> "player".equals(getEntityType(ctx)))}, upstream
     * {@code YSMBinding.java:130} over {@code getEntityType} at {@code :327-340} - 1 for a player, 0 for every other
     * living entity (and for a non-living frame, where upstream's typed binding answers the registered default).
     * 1.7.10's counterpart is the {@code EntityPlayer} check itself.
     *
     * <p>This one matters for a pack that writes "only do X when this is a player": before it was registered, the name
     * read a silent 0 and every such branch took its non-player side.
     */
    public static double isPlayer() {
        return MolangFrameContext.getEntity() instanceof EntityPlayer ? 1.0D : 0.0D;
    }

    /**
     * {@code livingEntityVar("entity_type", YSMBinding::getEntityType)}, upstream {@code YSMBinding.java:129} with the
     * body at {@code :327-340}: {@code "player"} for a player, otherwise the entity type's registry name. 1.7.10's
     * registry name is {@code EntityList#getEntityString}
     * ({@code EntityList.java:258}) - and, as with {@code ysm.hit_target_id}, it is <em>not</em> namespaced for a
     * vanilla mob ({@code "Pig"}, not {@code "minecraft:pig"}). Upstream's {@code "maid"} case has no 1.7.10
     * counterpart (there is no maid mod here), so the name falls through to the registry string, which is what a maid
     * entity would answer if it existed as an ordinary modded entity.
     *
     * <p>Returned as a {@link MolangStringPool} id, so a pack's {@code ysm.entity_type == 'Pig'} comparison lands on
     * the same id (the parser interns its literal). A non-living frame answers the empty string, upstream's
     * {@code livingEntityVar} default.
     */
    public static double entityType() {
        Entity entity = MolangFrameContext.getFrameEntity();
        if (!(entity instanceof EntityLivingBase)) {
            return MolangStringPool.EMPTY_ID;
        }
        if (entity instanceof EntityPlayer) {
            return MolangStringPool.intern("player");
        }
        String name = EntityList.getEntityString(entity);
        return name == null || name.isEmpty() ? MolangStringPool.EMPTY_ID : MolangStringPool.intern(name);
    }

    /** {@code playerVar("attack_damage", ctx -> ctx.entity().getAttributeValue(Attributes.ATTACK_DAMAGE))}, upstream {@code YSMBinding.java:158}. */
    public static double attackDamage() {
        return playerAttribute(SharedMonsterAttributes.attackDamage);
    }

    /** {@code playerVar("movement_speed", ...)}, upstream {@code YSMBinding.java:161}. */
    public static double movementSpeed() {
        return playerAttribute(SharedMonsterAttributes.movementSpeed);
    }

    /** {@code playerVar("knockback_resistance", ...)}, upstream {@code YSMBinding.java:162}. */
    public static double knockbackResistance() {
        return playerAttribute(SharedMonsterAttributes.knockbackResistance);
    }

    /**
     * {@code livingEntityVar("xxa", YSMBinding::getXxa)}, upstream {@code YSMBinding.java:135} with the body at
     * {@code :282-288}: the living entity's sideways movement input. 1.7.10's field is
     * {@code EntityLivingBase#moveStrafing}, which {@code EntityPlayerSP#onLivingUpdate:95} fills from
     * {@code movementInput.moveStrafe} for the local player. A remote player's copy is never written on this client
     * (the port has no protocol field for it, see the class comment), so a remote player answers 0.
     */
    public static double xxa() {
        EntityLivingBase living = MolangFrameContext.getEntity();
        return living == null || isRemotePlayer(living) ? 0.0D : (double) living.moveStrafing;
    }

    /**
     * {@code livingEntityVar("yya", YSMBinding::getYya)}, upstream {@code YSMBinding.java:136} with the body at
     * {@code :290-296}: the living entity's vertical movement input, the y component of the modern input vector
     * {@code Entity#moveRelative} consumes.
     *
     * <p>1.7.10 has no such field. The only vertical movement input the client has is the key state on
     * {@code EntityPlayerSP#movementInput} - jump held means "up", sneak held means "down", which is exactly what the
     * input does for a flying or swimming player, the case this component exists for - so the local player answers that
     * mapping and everybody else (a remote player, whose input is not synced, and a non-player, which has no input
     * field) answers 0. The mapping is recorded in the report as the one A5 value whose exact upstream meaning could
     * not be read from this workspace (there is no 1.20 source tree here for {@code LivingEntity.yya}).
     */
    public static double yya() {
        EntityLivingBase living = MolangFrameContext.getEntity();
        // 1.7.10 keeps the input object on the *client* player class (EntityPlayerSP#movementInput), which is also the
        // only kind of player whose input this client owns; everyone else answers 0 (see the class comment).
        if (!(living instanceof EntityPlayerSP player) || isRemotePlayer(player)) {
            return 0.0D;
        }
        MovementInput input = player.movementInput;
        if (input == null) {
            return 0.0D;
        }
        if (input.jump) {
            return 1.0D;
        }
        return input.sneak ? -1.0D : 0.0D;
    }

    /**
     * {@code livingEntityVar("zza", YSMBinding::getZza)}, upstream {@code YSMBinding.java:137} with the body at
     * {@code :298-304}: the forward movement input. 1.7.10's field is {@code EntityLivingBase#moveForward}, filled
     * from {@code movementInput.moveForward} for the local player
     * ({@code EntityPlayerSP#onLivingUpdate:96}); a remote player answers 0 for the same reason as {@link #xxa()}.
     */
    public static double zza() {
        EntityLivingBase living = MolangFrameContext.getEntity();
        return living == null || isRemotePlayer(living) ? 0.0D : (double) living.moveForward;
    }

    /**
     * {@code entityVar("input_horizontal", MoveInputVariable::getHorizontal)}, upstream {@code YSMBinding.java:97} with
     * the body at {@code .../variable/MoveInputVariable.java:31-51}: the sideways component of the frame entity's
     * movement direction relative to where it faces, as a sine - the sibling of {@code ysm.input_vertical}, which is
     * the cosine.
     *
     * <p>The port's {@code input_vertical} already exists as {@link EntityQueryBindings}'s derivation of the same
     * quantity from the frame's position delta and facing ({@code EntityQueryBindings#moveInputVertical:270-288}, which
     * documents its two 1.7.10 substitutions: the body yaw for a living entity and the interpolated
     * {@code rotationYaw} otherwise). The horizontal component is the same rule with {@code sin} instead of
     * {@code cos}, evaluated by the same method - not a second implementation of the angle arithmetic.
     */
    public static double inputHorizontal() {
        Entity entity = MolangFrameContext.getFrameEntity();
        return entity == null ? 0.0D : EntityQueryBindings.moveInputHorizontal(entity, partialTick());
    }

    /**
     * {@code entityVar("ground_speed2", YSMBinding::getGroundSpeed2)}, upstream {@code YSMBinding.java:94} with the
     * body at {@code :276-280}: {@code 20 * |horizontal position delta| / renderTickDelta}, i.e. blocks per second.
     *
     * <p>Its two inputs are the state tracker's position delta for this render frame and the ticks elapsed since the
     * previous one ({@code model/EntityStateTracker.java:57-75}), and their quotient is the per-tick speed - the same
     * number 1.7.10's per-tick delta ({@code posX - prevPosX}) already is, since an entity only moves on its own tick.
     * So the port reuses the definition it already has for the sibling name {@code query.ground_speed}
     * ({@link EntityQueryBindings}'s {@code groundSpeed}, which is this port's documented position-delta form of
     * upstream's velocity read). The two names therefore report the same number here; upstream's differ only by which
     * sampling they use, not by their unit.
     */
    public static double groundSpeed2() {
        Entity entity = MolangFrameContext.getFrameEntity();
        return entity == null ? 0.0D : EntityQueryBindings.groundSpeed(entity);
    }

    /**
     * {@code var("time_delta", ctx -> ctx.animatableEntity().getStateTracker().getRenderTickDelta() / 20)}, upstream
     * {@code YSMBinding.java:92}: the seconds elapsed since the previous rendered frame.
     *
     * <p>Upstream binds the same quantity a second time as {@code query.delta_time}
     * ({@code geckolib3/core/molang/builtin/QueryBinding.java:63} is the same expression, and the YSM
     * {@code QueryBinding.java:63} likewise), and the engine here already publishes that name for the animation being
     * processed - {@code AnimationController#processCurrentAnimation} writes the real delta for an
     * {@code anim_time_update} animation ({@code AnimationController.java:1259}) and one tick ({@code 1/20 s}) for
     * every other one ({@code :1284}). {@code ysm.time_delta} therefore reads that published value instead of running a
     * second frame clock: one quantity, two names, exactly as upstream has it - and the task deliberately leaves
     * {@code query.delta_time} itself untouched.
     */
    public static double timeDelta() {
        LazyVariable published = MolangParser.VARIABLES.get("query.delta_time");
        // Never published (no animation has been processed yet): the engine's own default, one tick.
        return published == null ? 1.0D / 20.0D : published.get();
    }

    /** The client's current render partial tick, or 0 outside a client frame. */
    private static float partialTick() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.timer == null ? 0.0F : mc.timer.renderPartialTicks;
    }

    /** Whether this is a player other than the local one. */
    private static boolean isRemotePlayer(EntityLivingBase living) {
        Minecraft mc = Minecraft.getMinecraft();
        return living instanceof EntityPlayer && mc != null && living != mc.thePlayer;
    }

    /** Upstream's {@code playerVar} attribute read, with "no such attribute on this entity" answering 0. */
    private static double playerAttribute(IAttribute attribute) {
        EntityPlayer player = MolangFrameContext.getPlayer();
        if (player == null) {
            return 0.0D;
        }
        IAttributeInstance instance = player.getEntityAttribute(attribute);
        return instance == null ? 0.0D : instance.getAttributeValue();
    }
}
