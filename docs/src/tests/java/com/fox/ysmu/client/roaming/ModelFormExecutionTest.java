package com.fox.ysmu.client.roaming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ModelFormExecutionTest {

    @Test
    void physicsSnapshotDiffReportsOnlyChangedRoamingMembersToTheRealLocalStore() {
        UUID owner = UUID.randomUUID();
        int modelKey = 71231;
        Map<String, Double> before = new HashMap<>();
        // syncToRuntimeState strips only v.; these keys mirror its real output contract.
        before.put("roaming.changed", 1d);
        before.put("roaming.unchanged", 4d);
        before.put("other", 1d);
        Map<String, Double> after = new HashMap<>(before);
        after.put("roaming.changed", 2d);
        after.put("roaming.created", 0d);
        after.put("other", 2d);
        after.put("roamingish.wrong_namespace", 9d);
        after.put("roaming.", 9d);
        ClientRoamingStore.clear();
        try {
            ModelFormExecution.reportRoamingChanges(before, after,
                (name, value) -> ClientRoamingStore.setLocal(owner, modelKey, name, value));

            Map<String, Double> expected = new HashMap<>();
            expected.put("v.roaming.changed", 2d);
            expected.put("v.roaming.created", 0d);
            assertEquals(expected, ClientRoamingStore.valuesFor(owner, modelKey));
            assertEquals(expected, ClientRoamingStore.takePending().get(modelKey));
        } finally {
            ClientRoamingStore.clear();
        }
    }
}
