package com.fox.ysmu.client.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Upstream SoundInstanceManager:47-108: named instances replace only on request; id 0 is anonymous. */
final class SoundInstanceRegistry {
    interface Instance {
        boolean isStopped();
        void stop();
    }

    private final Map<Integer, Instance> named = new HashMap<>();
    private final ArrayList<Instance> unnamed = new ArrayList<>();

    boolean play(int id, boolean replace, Supplier<Instance> create) {
        Instance old = named.get(id);
        if (id != 0 && !replace && old != null && !old.isStopped()) return false;
        Instance next = create.get();
        if (next == null) return false;
        if (id == 0) unnamed.add(next);
        else {
            named.put(id, next);
            if (old != null && !old.isStopped()) old.stop();
        }
        return true;
    }

    boolean stop(int id) {
        if (id == 0) return false;
        Instance instance = named.remove(id);
        if (instance == null) return false;
        instance.stop();
        return true;
    }

    void stopAll() {
        named.values().forEach(Instance::stop);
        unnamed.forEach(Instance::stop);
        named.clear();
        unnamed.clear();
    }

    boolean isEmpty() { return named.isEmpty() && unnamed.isEmpty(); }

    void trim() {
        named.values().removeIf(Instance::isStopped);
        unnamed.removeIf(Instance::isStopped);
    }
}
