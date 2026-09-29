package com.fox.ysmu.client.animation.molang;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code query.position(axis)} - the frame entity's interpolated world position on one axis ({@code 0} = X,
 * {@code 1} = Y, {@code 2} = Z).
 * <p>
 * Ported from OpenYSM's {@code builtin/query/Position}, whose body is
 * {@code Mth.lerp(partialTicks, entity.xo, entity.getX())} and friends. The 1.7.10 port reads the same two values
 * from {@code prevPosX}/{@code posX} and interpolates with {@code Minecraft.timer.renderPartialTicks}, the frame
 * fraction the renderer already uses for player rendering.
 * <p>
 * The frame's entity comes from {@link MolangFrameContext}, exactly like {@link QueryPositionDeltaFunction}; outside
 * a published frame - the preview screen, or before the first render - the answer is 0 rather than another entity's
 * position. Before this function existed the engine failed the whole expression with
 * {@code Function 'query.position' couldn't be found!}, which is why packs such as Wine Fox &amp; Friends lost every
 * animation and controller that used it.
 */
public class QueryPositionFunction extends Function {

    private final IValue[] arguments;

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public QueryPositionFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.arguments = values;
    }

    @Override
    public double get() {
        if (this.arguments == null || this.arguments.length == 0) {
            return 0.0;
        }
        EntityLivingBase entity = MolangFrameContext.getEntity();
        if (entity == null) {
            return 0.0;
        }
        float partialTicks = Minecraft.getMinecraft().timer.renderPartialTicks;
        switch ((int) this.arguments[0].get()) {
            case 0:
                return entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks;
            case 1:
                return entity.prevPosY + (entity.posY - entity.prevPosY) * partialTicks;
            case 2:
                return entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks;
            default:
                return 0.0;
        }
    }
}
