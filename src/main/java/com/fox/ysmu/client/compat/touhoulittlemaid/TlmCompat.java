package com.fox.ysmu.client.compat.touhoulittlemaid;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import cpw.mods.fml.common.Loader;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * Optional adapter for the 1.7.10 Touhou Little Maid port (mod id {@code touhou_little_maid}), the YSM-side half of the
 * integration upstream YesSteveModel 1.20.1 used to ship.
 *
 * <p>
 * Upstream has since removed that integration: {@code docs/status/known-issues/animation.md:60} says "女仆模组联动已
 * 移除；模型中的 {@code tlm} 动画集读取与内置动画资源暂时保留，不提供女仆实体接入或脚本接口". The 25-file
 * {@code client/compat/touhoulittlemaid} package in the upstream tree is therefore orphaned leftover code - no caller of
 * its entry points, no TLM dependency in its build, and the current tree cannot even compile it - so this port does not
 * resurrect it; see {@code .agent/phase79-tlm-compat-live.md}. Only the surfaces upstream still runs are aligned here:
 *
 * <ul>
 * <li>the 16 {@code tlm.*} Molang names ({@link TlmBindingInner}), which upstream keeps registered (its
 * {@code TLMBinding} feeds the context binding table, and the names exist even without the mod);</li>
 * <li>{@code ysm.is_fishing}'s maid branch (upstream {@code YSMBinding#isFishing}, wired in
 * {@link com.fox.ysmu.client.animation.AnimationRegister}).</li>
 * </ul>
 *
 * <p>
 * {@code ConditionalChair} is the third upstream survivor and is dead there too (nothing constructs it outside its own
 * file), so it is deliberately not ported.
 *
 * <p>
 * <b>Classloading.</b> This facade references no {@code com.github.tartaricacid.touhoulittlemaid} type, so it is safe to
 * load with the mod absent. The TLM-typed half lives in {@link TlmCompatInner} / {@link TlmBindingInner} and is reached
 * only behind {@link #isInstalled()}, i.e. after {@link Loader#isModLoaded(String)} has answered yes - the same
 * outer/inner split the TacZ adapter uses ({@code client/compat/tacz/TACZCompat}).
 *
 * <p>
 * <b>Direction.</b> In 1.7.10 the integration runs the other way round from 1.20.1: TLM's own {@code compat.ysm} package
 * reflects into YSMU's published API ({@code EntityModelApi}/{@code EntityModelRenderApi}/{@code ModelGuiApi}/
 * {@code EntityAnimationApi}), and TLM keeps the maid's YSM state in its own data watcher. So this adapter reads a
 * maid's own public state only; it never calls TLM's bridge classes and never adds a YSMU host API that only the current
 * TLM build would use - the TLM port is expected to return to upstream behaviour, and only the TLM-typed expressions in
 * {@link TlmCompatInner}/{@link TlmBindingInner} should need revisiting when it does.
 */
public final class TlmCompat {

    private static final String MOD_ID = "touhou_little_maid";

    private static boolean installed;

    private TlmCompat() {}

    /**
     * Called once from {@code ClientProxy#init}, before {@code AnimationRegister#registerVariables} - the live binding
     * replaces the {@code tlm.*} defaults that method registers.
     */
    public static void init() {
        installed = Loader.isModLoaded(MOD_ID);
    }

    public static boolean isInstalled() {
        return installed;
    }

    /** Whether {@code entity} is a Touhou Little Maid maid. Loads no TLM class while the mod is absent. */
    public static boolean isMaid(Entity entity) {
        return installed && entity != null && TlmCompatInner.isMaid(entity);
    }

    /** Upstream {@code YSMBinding#isFishing} for a non-player living entity: {@code TlmClientCompat#isMaidFishing}. */
    public static boolean isMaidFishing(EntityLivingBase entity) {
        return installed && entity != null && TlmCompatInner.isMaidFishing(entity);
    }

    /**
     * Binds the live values of the 16 {@code tlm.*} names, replacing the defaults
     * {@code AnimationRegister#registerThirdPartyCompatPlaceholders} registered. Upstream shape: its {@code TLMBinding}
     * constructor calls {@code TlmClientCompat.addBinding(this)} ({@code client/animation/molang/TLMBinding.java:14}),
     * which forwards to {@code TLMBindingInner#addInnerBinding}.
     */
    public static void addBinding(MolangParser parser) {
        if (installed) {
            TlmBindingInner.addInnerBinding(parser);
        }
    }
}
