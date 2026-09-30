package com.fox.ysmu.event.api;

import net.minecraft.entity.EntityLivingBase;

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import cpw.mods.fml.common.eventhandler.Event;

/**
 * Fired on the client after remote animation variables arrive from the server for an entity.
 * <p>
 * This is the entity-oriented equivalent of the model-replacement contract's {@code UpdateRemoteStructEvent}:
 * a model-replacement consumer (for example Touhou Little Maid) posts this when it receives an entity's
 * {@code roamingVars}, and YSMU injects the values into that entity's Molang scope. A model then reads them
 * as {@code v.roaming.<name>} without the animation file having to assign them first.
 * <p>
 * Posts on {@code MinecraftForge.EVENT_BUS}; no client-only types are referenced so the class is safe to
 * load on a dedicated server.
 */
public class UpdateRemoteStructEvent extends Event {

    private final EntityLivingBase entity;
    private final Object2FloatOpenHashMap<String> roamingVars;

    public UpdateRemoteStructEvent(EntityLivingBase entity, Object2FloatOpenHashMap<String> roamingVars) {
        this.entity = entity;
        this.roamingVars = roamingVars;
    }

    /** The entity the variables belong to. */
    public EntityLivingBase getEntity() {
        return entity;
    }

    public Object2FloatOpenHashMap<String> getRoamingVars() {
        return roamingVars;
    }
}
