package com.fox.ysmu.client.compat;

import java.util.Map;

/** Loader transaction: host manager only adopts a holder when all accepts return successfully. */
public final class PbrPublication {
    @FunctionalInterface
    public interface Consumer<T> { void accept(int role, T texture) throws Exception; }
    private PbrPublication() {}

    public static <T extends AutoCloseable> void publish(Map<Integer, T> prepared, Consumer<T> consumer) throws Exception {
        try {
            for (Map.Entry<Integer, T> component : prepared.entrySet()) consumer.accept(component.getKey(), component.getValue());
        } catch (Exception failure) {
            for (T texture : prepared.values()) {
                try { texture.close(); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
}
