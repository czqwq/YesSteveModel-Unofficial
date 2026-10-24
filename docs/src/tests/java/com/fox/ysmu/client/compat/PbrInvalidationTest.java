package com.fox.ysmu.client.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PbrInvalidationTest {
    @Test void hostHolderClosesBeforeBaseDeletionIncludingShaderOffAndReusedId() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            PbrInvalidation.delete(17, id -> order.add("holder:" + id), () -> order.add("base"));
        }
        assertEquals(List.of("holder:17", "base", "holder:17", "base"), order);
    }
    @Test void absentOrUnallocatedHostNeverBlocksBaseDeletion() {
        int[] deleted = {0};
        PbrInvalidation.delete(-1, id -> fail("no GL id"), () -> deleted[0]++);
        PbrInvalidation.delete(1, id -> {}, () -> deleted[0]++);
        assertEquals(2, deleted[0]);
    }
}
