package com.fox.ysmu.client.compat.touhoulittlemaid;

import java.util.Locale;

import net.minecraft.entity.EntityLivingBase;

import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.github.tartaricacid.touhoulittlemaid.api.backpack.IMaidBackpack;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidGomokuAI;
import com.github.tartaricacid.touhoulittlemaid.entity.backpack.BackpackManager;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidGameRecordManager;
import com.github.tartaricacid.touhoulittlemaid.entity.schedule.MaidSchedule;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.util.MolangUtils;

/**
 * Port of the live half of upstream {@code client/compat/touhoulittlemaid/client/animation/molang/TLMBindingInner.java}:
 * the 16 {@code tlm.*} Molang names, answered from a 1.7.10 maid's own state while Touhou Little Maid is installed.
 *
 * <p>
 * Upstream binds them as {@code livingEntityVar}s on its context binding table and answers <b>0</b> for a context
 * entity that is not a maid ({@code TLMBindingInner#checkMaid}) - not the registered default. This port's parser has one
 * flat variable table, so each name is a {@link LazyVariable} supplier reading the frame entity from
 * {@link MolangFrameContext}, the same shape {@code TacCtrlBinding} uses for {@code ctrl.tac_*}. The maid is the frame
 * entity on YSMU's non-player render path: {@code AnimationManager#predicateEntityLocomotion} publishes it before the
 * model's animation files evaluate, and Touhou Little Maid draws a maid through {@code EntityModelRenderApi#render}.
 *
 * <p>
 * <b>This is the only place that touches TLM classes for these values.</b> Four upstream concepts have no 1.7.10
 * counterpart; each substitution below names its upstream member and is the only line that needs revisiting when the TLM
 * port returns to upstream behaviour:
 * <ul>
 * <li>{@code tlm.is_sitting} - upstream {@code EntityMaid#isMaidInSittingPose()}, substituted by vanilla
 * {@code EntityTameable#isSitting()} (the flag TLM 1.7.10's own renderer and entity cache use).</li>
 * <li>{@code tlm.activity} - upstream {@code getScheduleDetail().getName()}, substituted by the body of the 1.7.10
 * private {@code EntityMaid#currentActivity()} (javap: {@code getSchedule().activityAt((int) (worldTime % 24000L))},
 * and {@code MaidSchedule.DAY_LENGTH} is 24000).</li>
 * <li>{@code tlm.is_entity} / {@code tlm.is_statue} / {@code tlm.is_garage_kit} - upstream compares the maid's
 * {@code renderState} with {@code MaidRenderState}; 1.7.10 has neither the enum nor the field. A maid reaching this
 * bind path is an ordinary entity render, so {@code is_entity} keeps upstream's normal-render value (true) and the other
 * two keep their normal-render values (false). Recorded gap, not an invented approximation.</li>
 * <li>{@code tlm.show_item} - upstream {@code getBackpackShowItem()}; 1.7.10 has no backpack-show-item pass, so the
 * empty string, which is upstream's own default for "nothing to show". Recorded gap.</li>
 * </ul>
 */
final class TlmBindingInner {

    private TlmBindingInner() {}

    static void addInnerBinding(MolangParser parser) {
        livingEntityVar(parser, "tlm.is_begging", maid -> MolangUtils.booleanToFloat(maid.isBegging()));
        livingEntityVar(parser, "tlm.is_sitting", maid -> MolangUtils.booleanToFloat(maid.isSitting()));
        livingEntityVar(parser, "tlm.has_backpack", TlmBindingInner::hasBackpack);
        livingEntityVar(parser, "tlm.favorability_point", maid -> maid.getFavorability());
        livingEntityVar(parser, "tlm.favorability_level", maid -> maid.getFavorabilityManager()
            .level());
        livingEntityVar(parser, "tlm.task_id", maid -> intern(maid.getTask()));
        livingEntityVar(parser, "tlm.schedule", maid -> intern(
            maid.getSchedule()
                .name()
                .toLowerCase(Locale.ENGLISH)));
        livingEntityVar(parser, "tlm.activity", maid -> intern(
            maid.getSchedule()
                .activityAt((int) (maid.worldObj.getWorldTime() % MaidSchedule.DAY_LENGTH))
                .name()
                .toLowerCase(Locale.ENGLISH)));
        livingEntityVar(
            parser,
            "tlm.gomoku_win_count",
            maid -> maid.getGameRecordManager()
                .getGomokuWinCount());
        livingEntityVar(parser, "tlm.gomoku_rank", maid -> MaidGomokuAI.getRank(maid));
        livingEntityVar(parser, "tlm.game_statue", TlmBindingInner::gameStatue);
        livingEntityVar(parser, "tlm.backpack_type", TlmBindingInner::backpackType);
        // Upstream: renderState == MaidRenderState.ENTITY. 1.7.10 has no render state; a maid on this path is an
        // ordinary entity render, which is what ENTITY means there.
        livingEntityVar(parser, "tlm.is_entity", maid -> 1.0D);
        livingEntityVar(parser, "tlm.is_statue", maid -> 0.0D);
        livingEntityVar(parser, "tlm.is_garage_kit", maid -> 0.0D);
        // Upstream: getBackpackShowItem() -> registry name or ''. No such pass in 1.7.10, so the empty string.
        livingEntityVar(parser, "tlm.show_item", maid -> MolangStringPool.EMPTY_ID);
    }

    /** One {@code livingEntityVar}: register the name, then answer it from the frame's maid (0 for a non-maid). */
    private static void livingEntityVar(MolangParser parser, String name, MaidQuery query) {
        parser.register(new LazyVariable(name, 0));
        parser.setValue(name, () -> {
            EntityLivingBase entity = MolangFrameContext.getEntity();
            if (!(entity instanceof EntityMaid)) {
                return 0.0D;
            }
            return query.query((EntityMaid) entity);
        });
    }

    /** Upstream IMaid default: no backpack means the manager's empty backpack instance. */
    private static double hasBackpack(EntityMaid maid) {
        IMaidBackpack type = maid.getMaidBackpackType();
        return MolangUtils.booleanToFloat(type != null && type != BackpackManager.getEmptyBackpack());
    }

    /** Upstream {@code getGameStatue}: only a maid sitting on a game seat reports win/lost. */
    private static double gameStatue(EntityMaid maid) {
        if (maid.ridingEntity instanceof EntitySit) {
            MaidGameRecordManager manager = maid.getGameRecordManager();
            if (manager.isWin()) {
                return intern("win");
            }
            if (manager.isLost()) {
                return intern("lost");
            }
        }
        return MolangStringPool.EMPTY_ID;
    }

    /** Upstream {@code getMaidBackpackType().getId().toString()}, with the 1.7.10 null cases guarded. */
    private static double backpackType(EntityMaid maid) {
        IMaidBackpack type = maid.getMaidBackpackType();
        if (type == null || type.getId() == null) {
            return MolangStringPool.EMPTY_ID;
        }
        return intern(type.getId()
            .toString());
    }

    /** A string value is a pooled id in this parser, which is how a pack's {@code 'win'} comparison matches. */
    private static double intern(String value) {
        return value == null ? MolangStringPool.EMPTY_ID : MolangStringPool.intern(value);
    }

    private interface MaidQuery {

        double query(EntityMaid maid);
    }
}
