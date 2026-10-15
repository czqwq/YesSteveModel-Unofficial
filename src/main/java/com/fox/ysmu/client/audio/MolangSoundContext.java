package com.fox.ysmu.client.audio;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/** Explicit, nestable legacy evaluator context; closing restores evaluation, it does not stop playback. */
public final class MolangSoundContext implements AutoCloseable {
    private static final ThreadLocal<MolangSoundContext> CURRENT = new ThreadLocal<>();
    final Object owner;
    final Entity entity;
    final ResourceLocation model;
    final Object localManager;
    final boolean ui;
    final boolean allowEmitting;
    private final MolangSoundContext previous;

    private MolangSoundContext(Object owner, Entity entity, ResourceLocation model, Object localManager,
        boolean ui, boolean allowEmitting) {
        this.owner = owner;
        this.entity = entity;
        this.model = model;
        this.localManager = localManager;
        this.ui = ui;
        this.allowEmitting = allowEmitting;
        this.previous = CURRENT.get();
        MolangSoundPlayback.bindOwner(owner, model, localManager);
        CURRENT.set(this);
    }

    /** owner must be the stable animatable identity, including detached previews, never the listener. */
    public static MolangSoundContext push(Object owner, Entity entity, ResourceLocation model,
        Object localManager, boolean ui, boolean allowEmitting) {
        return new MolangSoundContext(owner, entity, model, localManager, ui, allowEmitting);
    }

    public static MolangSoundContext current() { return CURRENT.get(); }
    public boolean allowsEmitting() { return allowEmitting; }
    public Entity entity() { return entity; }
    public ResourceLocation model() { return model; }
    public Object owner() { return owner; }
    public boolean isUI() { return ui; }
    public Object manager(boolean global) { return global || localManager == null ? owner : localManager; }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
