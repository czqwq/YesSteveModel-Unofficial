package com.fox.ysmu.client.compat.tacz;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunMeleeEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.EntityLivingBase;

/**
 * Port of upstream {@code client/compat/tacz/TacEvent.java}: firing (including the empty-magazine click), melee and
 * reload set the "replay the empty-gun clip" hint that {@link TacCompatInner#playGunOnceAnimation} consumes.
 *
 * <p>
 * Registered only when TacZ is installed ({@link TACZCompat#init}); the three events are posted on both sides by TacZ
 * itself, exactly like upstream's, and only the client-side copy reaches this client's animatables.
 */
public class TacEvent {

    @SubscribeEvent
    public void onGunFire(GunFireEvent event) {
        TacGunAnimationState.markNeedReload(event.getShooter());
    }

    @SubscribeEvent
    public void onGunMelee(GunMeleeEvent event) {
        TacGunAnimationState.markNeedReload(event.getShooter());
    }

    @SubscribeEvent
    public void onGunReload(GunReloadEvent event) {
        EntityLivingBase entity = event.getEntity();
        TacGunAnimationState.markNeedReload(entity);
    }
}
