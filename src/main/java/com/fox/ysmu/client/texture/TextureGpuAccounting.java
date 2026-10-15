package com.fox.ysmu.client.texture;

import java.util.function.LongConsumer;

/** One base owner's contribution, including host-adopted components. Used by real upload/delete callbacks. */
final class TextureGpuAccounting {
    private final LongConsumer budget;
    private boolean owned;
    private long base;
    private long components;

    TextureGpuAccounting(LongConsumer budget) { this.budget = budget; }
    void own() { if (!owned) { owned = true; budget.accept(total()); } }
    void base(long bytes) { if (owned) budget.accept(bytes - base); base = bytes; }
    void components(long delta) { components += delta; if (owned) budget.accept(delta); }
    long total() { return base + components; }
    void forget() { if (owned) budget.accept(-total()); owned = false; }
}
