package com.fox.ysmu.client.animation.molang;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * The A5 {@code ysm.*} names whose value comes from the frame entity's world (or from the local client's crosshair),
 * rather than from the entity itself: {@code weather}, {@code dimension_name}, {@code block_light},
 * {@code sky_light}, {@code is_open_air}, {@code eye_in_water}, {@code ladder_facing}, {@code hit_target_id} and
 * {@code hit_target_type}.
 *
 * <p>Every value is upstream's, evaluated against the port's own source for it; the upstream line and the 1.7.10
 * substitute are named at each method. The frame's entity comes from {@link MolangFrameContext}, so a sub-entity frame
 * (an arrow, a vehicle) answers about itself - which is what upstream's typed {@code entityVar} / {@code livingEntityVar}
 * bindings do - and an out-of-frame call answers 0 rather than the previous entity's value.
 *
 * <p><b>Strings.</b> Upstream returns a string for {@code dimension_name} and {@code hit_target_id}; this runtime
 * compares strings as {@link MolangStringPool} ids (see {@code ysm.relative_block_name}), and the parser interns the
 * literal on the other side of a comparison ({@code core/molang/MolangParser.java:963}), so both sides land on the same
 * id. {@code MolangStringPool.EMPTY_ID} is upstream's {@code StringUtils.EMPTY} / {@code ""}.
 */
public final class YsmWorldQueries {

    private YsmWorldQueries() {}

    /**
     * {@code var("weather", ctx -> getWeather(ctx.level()))}, upstream {@code YSMBinding.java:89} with the body at
     * {@code :362-369}: thunder is 2, rain is 1, anything else 0 - thunder is tested first because a thunderstorm also
     * reports rain.
     */
    public static double weather() {
        World world = world();
        if (world == null) {
            return 0.0D;
        }
        if (world.isThundering()) {
            return 2.0D;
        }
        return world.isRaining() ? 1.0D : 0.0D;
    }

    /**
     * {@code var("dimension_name", ctx -> ctx.level().dimension().location().toString())}, upstream
     * {@code YSMBinding.java:90} - a namespaced dimension id.
     *
     * <p>1.7.10 has no such identifier: a dimension is an int, and the only names it carries are
     * {@code WorldProvider#getDimensionName()}'s translated display string and {@code DimensionManager}'s provider
     * <em>class</em>. The three ids that the modern names denote are therefore answered by name, and a modded dimension
     * answers the empty string rather than an invented id - recorded in this task's report as an open item instead of
     * being guessed.
     */
    public static double dimensionName() {
        World world = world();
        if (world == null || world.provider == null) {
            return MolangStringPool.EMPTY_ID;
        }
        int dimensionId = world.provider.dimensionId;
        if (dimensionId == 0) {
            return MolangStringPool.intern("minecraft:overworld");
        }
        if (dimensionId == -1) {
            return MolangStringPool.intern("minecraft:the_nether");
        }
        if (dimensionId == 1) {
            return MolangStringPool.intern("minecraft:the_end");
        }
        // A modded dimension: 1.7.10 names it only by an int and a provider class, so nothing is invented here.
        return MolangStringPool.EMPTY_ID;
    }

    /**
     * {@code entityVar("block_light", ctx -> ctx.level().getBrightness(LightLayer.BLOCK, ctx.entity().blockPosition()))},
     * upstream {@code YSMBinding.java:101}. 1.7.10's counterpart is
     * {@code World#getSavedLightValue(EnumSkyBlock.Block, x, y, z)}, documented at
     * {@code World.java:1052-1056} as the saved value <em>without</em> the time of day - the same raw 0-15 the modern
     * {@code LightLayer.BLOCK} answers.
     */
    public static double blockLight() {
        return savedLight(EnumSkyBlock.Block);
    }

    /**
     * {@code entityVar("sky_light", ctx -> ctx.level().getBrightness(LightLayer.SKY, ctx.entity().blockPosition()))},
     * upstream {@code YSMBinding.java:102}; the sky half of the same 1.7.10 accessor.
     */
    public static double skyLight() {
        return savedLight(EnumSkyBlock.Sky);
    }

    /**
     * {@code entityVar("is_open_air", ctx -> isOpenAir(ctx.entity()))}, upstream {@code YSMBinding.java:108} with the
     * body at {@code :428-437}: true when the entity's block can see the sky and the motion-blocking heightmap is not
     * above it.
     *
     * <p>1.7.10's {@code World#canBlockSeeTheSky} is the same question ({@code World.java:835-838} decides it from the
     * stored sky light of the block). The heightmap half uses {@code World#getHeightValue} - the 1.7.10
     * {@code WORLD_SURFACE} map, i.e. "first air above the highest non-air block", the same meaning as the modern
     * {@code MOTION_BLOCKING} height {@code getHeightmapPos} returns; the two differ only for blocks that do not block
     * motion (tall grass), which is the one approximation in this value and is recorded as such.
     */
    public static double isOpenAir() {
        Entity entity = MolangFrameContext.getFrameEntity();
        World world = world();
        if (entity == null || world == null) {
            return 0.0D;
        }
        int x = MathHelper.floor_double(entity.posX);
        int y = MathHelper.floor_double(entity.posY);
        int z = MathHelper.floor_double(entity.posZ);
        if (!world.blockExists(x, y, z) || !world.canBlockSeeTheSky(x, y, z)) {
            return 0.0D;
        }
        return world.getHeightValue(x, z) <= y ? 1.0D : 0.0D;
    }

    /**
     * {@code entityVar("eye_in_water", ctx -> ctx.entity().isUnderWater())}, upstream {@code YSMBinding.java:109}.
     * 1.7.10 has no {@code isUnderWater}; the modern accessor is
     * {@code isInWater() && isEyeInFluid(WATER)}, so the eye's own block is tested for water here - the same two facts,
     * read from the block at {@code posY + getEyeHeight()}.
     */
    public static double eyeInWater() {
        Entity entity = MolangFrameContext.getFrameEntity();
        World world = world();
        if (entity == null || world == null || !entity.isInWater()) {
            return 0.0D;
        }
        int x = MathHelper.floor_double(entity.posX);
        int y = MathHelper.floor_double(entity.posY + (double) entity.getEyeHeight());
        int z = MathHelper.floor_double(entity.posZ);
        if (!world.blockExists(x, y, z)) {
            return 0.0D;
        }
        return world.getBlock(x, y, z)
            .getMaterial() == Material.water ? 1.0D : 0.0D;
    }

    /**
     * {@code livingEntityVar("ladder_facing", new LadderFacingVariable())}, upstream {@code YSMBinding.java:126} with
     * the body at {@code .../variable/LadderFacingVariable.java:14-26}: the climbable block's horizontal facing as
     * {@code Direction#get2DDataValue()} - "0-3, south-west-north-east" in that class's own words - and 0 when there is
     * no climbable or the block has no facing property.
     *
     * <p>1.7.10 stores the ladder's facing in the block metadata. {@code BlockLadder#func_149797_b} places the ladder's
     * collision box at the {@code +Z} edge for metadata 2, the {@code -Z} edge for 3, the {@code +X} edge for 4 and the
     * {@code -X} edge for 5, and the modern {@code NORTH_AABB}/{@code SOUTH_AABB}/{@code WEST_AABB}/{@code EAST_AABB}
     * are the same four boxes - so metadata 2/3/4/5 are {@code NORTH}/{@code SOUTH}/{@code WEST}/{@code EAST}, whose
     * 2D data values are 2/0/1/3. The block is read at the position {@code EntityLivingBase#isOnLadder} itself reads
     * ({@code EntityLivingBase.java:1098-1105}: {@code posX}, {@code boundingBox.minY}, {@code posZ}), so "on a
     * ladder" and "which ladder" cannot disagree.
     */
    public static double ladderFacing() {
        EntityLivingBase living = MolangFrameContext.getEntity();
        World world = world();
        if (living == null || world == null || !living.isOnLadder()) {
            return 0.0D;
        }
        int x = MathHelper.floor_double(living.posX);
        int y = MathHelper.floor_double(living.boundingBox.minY);
        int z = MathHelper.floor_double(living.posZ);
        if (!world.blockExists(x, y, z)) {
            return 0.0D;
        }
        Block block = world.getBlock(x, y, z);
        if (!(block instanceof BlockLadder)) {
            // Another climbable (vines, a modded one) has no horizontal facing upstream either, and answers 0 there.
            return 0.0D;
        }
        int metadata = world.getBlockMetadata(x, y, z);
        if (metadata == 2) {
            return 2.0D; // NORTH
        }
        if (metadata == 3) {
            return 0.0D; // SOUTH
        }
        if (metadata == 4) {
            return 1.0D; // WEST
        }
        if (metadata == 5) {
            return 3.0D; // EAST
        }
        return 0.0D;
    }

    /**
     * {@code localPlayerVar("hit_target_id", YSMBinding::getHitId)}, upstream {@code YSMBinding.java:177} with the body
     * at {@code :209-239}: the registry name of the block or entity the crosshair is on, else the empty string.
     *
     * <p>The typing is part of the value: upstream binds this against {@code LocalPlayer}, so a frame belonging to
     * anybody else (another player, a projectile) answers the empty string rather than the local client's crosshair.
     * 1.7.10's crosshair result is {@code Minecraft#objectMouseOver}; a block is named by
     * {@code Block.blockRegistry} (which is namespaced in 1.7.10, {@code minecraft:ladder}), while a non-player entity
     * is named by {@code EntityList#getEntityString} - 1.7.10's own entity registry name, which for a vanilla mob is
     * the bare {@code "Pig"} form rather than {@code minecraft:pig}. No namespace is invented here to paper over that:
     * it is recorded in the report as a difference the owner may want to settle.
     */
    public static double hitTargetId() {
        MovingObjectPosition hit = localPlayerHit();
        if (hit == null) {
            return MolangStringPool.EMPTY_ID;
        }
        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            World world = world();
            if (world == null || !world.blockExists(hit.blockX, hit.blockY, hit.blockZ)) {
                return MolangStringPool.EMPTY_ID;
            }
            Object key = Block.blockRegistry.getNameForObject(world.getBlock(hit.blockX, hit.blockY, hit.blockZ));
            return key == null ? MolangStringPool.EMPTY_ID : MolangStringPool.intern(key.toString());
        }
        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && hit.entityHit != null) {
            String name = EntityList.getEntityString(hit.entityHit);
            return name == null || name.isEmpty() ? MolangStringPool.EMPTY_ID : MolangStringPool.intern(name);
        }
        return MolangStringPool.EMPTY_ID;
    }

    /**
     * {@code localPlayerVar("hit_target_type", YSMBinding::getHitType)}, upstream {@code YSMBinding.java:178} with the
     * body at {@code :241-251}: {@code "block"}, {@code "entity"} or the empty string for a miss. Same local-player
     * typing as {@link #hitTargetId}.
     */
    public static double hitTargetType() {
        MovingObjectPosition hit = localPlayerHit();
        if (hit == null) {
            return MolangStringPool.EMPTY_ID;
        }
        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return MolangStringPool.intern("block");
        }
        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            return MolangStringPool.intern("entity");
        }
        return MolangStringPool.EMPTY_ID;
    }

    /** The local client's crosshair result, or {@code null} when this frame is not the local player's own. */
    @Nullable
    private static MovingObjectPosition localPlayerHit() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return null;
        }
        EntityPlayer framePlayer = MolangFrameContext.getPlayer();
        if (framePlayer == null || framePlayer != mc.thePlayer) {
            return null;
        }
        return mc.objectMouseOver;
    }

    private static double savedLight(EnumSkyBlock layer) {
        Entity entity = MolangFrameContext.getFrameEntity();
        World world = world();
        if (entity == null || world == null) {
            return 0.0D;
        }
        int x = MathHelper.floor_double(entity.posX);
        int y = MathHelper.floor_double(entity.posY);
        int z = MathHelper.floor_double(entity.posZ);
        if (!world.blockExists(x, y, z)) {
            return 0.0D;
        }
        return (double) world.getSavedLightValue(layer, x, y, z);
    }

    @Nullable
    private static World world() {
        Entity entity = MolangFrameContext.getFrameEntity();
        return entity == null ? null : entity.worldObj;
    }
}
