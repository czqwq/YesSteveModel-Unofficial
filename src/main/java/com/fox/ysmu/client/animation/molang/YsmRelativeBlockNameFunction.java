package com.fox.ysmu.client.animation.molang;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.relative_block_name(dx, dy, dz)} - the registry name of the block at the frame entity's position plus an
 * offset, and {@code ysm.relative_block_name_any(dx, dy, dz, name...)} - whether that block is one of the named ones.
 * <p>
 * Ported from OpenYSM's {@code RelativeBlockName} / {@code RelativeBlockNameAny}, whose lookup is
 * {@code MolangUtils#getRelativeBlockState}: the offset is rounded after subtracting {@code 0.5} (so {@code 0} means
 * the block the entity stands in) and any axis beyond five blocks answers nothing. The result is returned as a
 * {@link MolangStringPool} id, which is exactly how this runtime compares strings - the pack writes
 * {@code ysm.relative_block_name(0,0,0)==('minecraft:ladder')} and both sides end up as the same pooled id.
 * <p>
 * The name comes from 1.7.10's block registry ({@code minecraft:ladder}). Nothing is read from a chunk that is not
 * loaded, so a query can never force client-side generation.
 */
public class YsmRelativeBlockNameFunction extends Function {

    private static final int MAX_OFFSET = 5;

    private final IValue[] arguments;
    private final boolean matchAny;

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public YsmRelativeBlockNameFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.arguments = values;
        this.matchAny = name != null && name.endsWith("_any");
    }

    @Override
    public double get() {
        if (this.arguments == null || this.arguments.length < 3) {
            return 0;
        }
        String name = blockNameAt(getArg(0), getArg(1), getArg(2));
        if (name == null) {
            return 0;
        }
        if (!this.matchAny) {
            return MolangStringPool.intern(name);
        }
        for (int index = 3; index < this.arguments.length; index++) {
            String candidate = getStringArg(index);
            if (candidate != null && candidate.equals(name)) {
                return 1;
            }
        }
        return 0;
    }

    /** {@code modid:name} of the block at the offset, or {@code null} when there is nothing readable there. */
    private static String blockNameAt(double offsetX, double offsetY, double offsetZ) {
        EntityLivingBase entity = MolangFrameContext.getEntity();
        if (entity == null || entity.worldObj == null) {
            return null;
        }
        if (Math.abs(offsetX) > MAX_OFFSET || Math.abs(offsetY) > MAX_OFFSET || Math.abs(offsetZ) > MAX_OFFSET) {
            return null;
        }
        int x = MathHelper.floor_double(entity.posX + offsetX - 0.5D);
        int y = MathHelper.floor_double(entity.posY + offsetY - 0.5D);
        int z = MathHelper.floor_double(entity.posZ + offsetZ - 0.5D);
        if (!entity.worldObj.blockExists(x, y, z)) {
            return null;
        }
        Block block = entity.worldObj.getBlock(x, y, z);
        if (block == null || block == Blocks.air) {
            return null;
        }
        Object key = Block.blockRegistry.getNameForObject(block);
        return key == null ? null : key.toString();
    }
}
