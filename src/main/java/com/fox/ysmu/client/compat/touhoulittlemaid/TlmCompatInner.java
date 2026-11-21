package com.fox.ysmu.client.compat.touhoulittlemaid;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * The TLM-typed half of {@link TlmCompat}: every direct reference to a {@code com.github.tartaricacid.touhoulittlemaid}
 * class sits in this class or in {@link TlmBindingInner}, and both are only loaded after the facade has seen the mod
 * installed.
 *
 * <p>
 * Upstream source: {@code client/compat/touhoulittlemaid/client/TlmClientCompatInner.java} (isMaid at :39-41,
 * maidIsFishing at :72-75).
 */
final class TlmCompatInner {

    private TlmCompatInner() {}

    static boolean isMaid(Entity entity) {
        return entity instanceof EntityMaid;
    }

    /** Upstream {@code maidIsFishing}: a maid is fishing while her hook entity is out. 1.7.10 exposes it as a field. */
    static boolean isMaidFishing(EntityLivingBase entity) {
        return entity instanceof EntityMaid && ((EntityMaid) entity).fishing != null;
    }
}
