package com.fox.ysmu.client.animation.molang;

import net.minecraft.entity.player.EntityPlayer;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import com.fox.ysmu.client.animation.condition.InnerClassify;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * The animation-file / timeline {@code ctrl.hold('mainhand', ':sword')}.
 *
 * <p>
 * A-06③: this used to be a constant-0 stub ("暂时总是返回0，避免日志刷屏") while the controller expression
 * {@code ctrl.hold} was a real implementation in {@code OpenYsmControllerExpressionEvaluator}, so one name meant two
 * things. Both paths now call
 * {@link InnerClassify#matchesHandCondition}, which answers the same way for
 * {@code mainhand}/{@code offhand}, {@code $modid:name}, {@code #ore-dictionary-name}, {@code :kind} and
 * {@code :empty}, and never matches an off-hand request when Backhand (the 1.7.10 off-hand slot) is absent.
 *
 * <p>
 * String arguments arrive as ids in {@link MolangStringPool}: {@code MolangParser} rewrites quoted literals into
 * pool ids before parsing, the same mechanism {@code ysm.bone_*} uses. The current frame's player comes from
 * {@link MolangFrameContext#getPlayer()}; outside a published frame (a preview, or before the first frame) the
 * function answers 0, exactly as the stub did.
 */
public class CtrlHoldFunction extends Function {

    public CtrlHoldFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        EntityPlayer player = MolangFrameContext.getPlayer();
        if (player == null) {
            return 0.0D;
        }
        String hand = stringArgument(0, "mainhand");
        String matcher = stringArgument(1, "");
        return InnerClassify.matchesHandCondition(player, hand, matcher, false, false) ? 1.0D : 0.0D;
    }

    /**
     * Reads a string argument back out of {@link MolangStringPool}; a missing/empty/unresolvable argument falls
     * back, so a model written as {@code ctrl.hold()} cannot throw.
     */
    private String stringArgument(int index, String fallback) {
        if (this.args == null || index < 0 || index >= this.args.length) {
            return fallback;
        }
        String value = MolangStringPool.get((int) this.getArg(index));
        return value == null || value.isEmpty() ? fallback : value;
    }
}
