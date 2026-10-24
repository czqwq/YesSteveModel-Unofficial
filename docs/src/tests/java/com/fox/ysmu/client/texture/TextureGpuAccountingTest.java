package com.fox.ysmu.client.texture;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TextureGpuAccountingTest {
    @Test void replacementForgetThenHostCloseCannotDoubleSubtract() {
        long[] global = {0};
        TextureGpuAccounting owner = new TextureGpuAccounting(delta -> global[0] += delta);
        owner.own(); owner.base(64); owner.components(128);
        assertEquals(192, global[0]);
        owner.forget(); assertEquals(0, global[0]);
        assertEquals(192, owner.total(), "actual GPU copies remain until host deletion");
        owner.components(-128); owner.base(0); owner.forget();
        assertEquals(0, global[0]); assertEquals(0, owner.total());
    }

    @Test void gpuEvictionAndLazyUploadEachCountOneCompleteSet() {
        long[] global = {0};
        TextureGpuAccounting owner = new TextureGpuAccounting(delta -> global[0] += delta);
        owner.own(); owner.base(64); owner.components(64); owner.components(64);
        owner.components(-64); owner.components(-64); owner.base(0); // invalidate holder before base deletion
        assertEquals(0, global[0]);
        owner.base(64); owner.components(64); owner.components(64); // next bind creates new base/holder
        assertEquals(192, global[0]);
        owner.components(-128); // shader reload clears holder, retains base
        assertEquals(64, global[0]);
        owner.components(128); owner.forget();
        assertEquals(0, global[0]);
    }

    @Test void nonRegistryIconOrUnadoptedComponentNeverCountsAgainstModelBudget() {
        long[] global = {0};
        TextureGpuAccounting icon = new TextureGpuAccounting(delta -> global[0] += delta);
        icon.base(64); icon.components(64); icon.components(-64); icon.base(0);
        assertEquals(0, global[0]);
    }
}
