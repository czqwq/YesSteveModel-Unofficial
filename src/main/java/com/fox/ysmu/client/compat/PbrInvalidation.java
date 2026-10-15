package com.fox.ysmu.client.compat;

import java.util.function.IntConsumer;

/** Invalidates the old holder even with shaders disabled, before deleting the base GL copy. */
public final class PbrInvalidation {
    private PbrInvalidation() {}
    public static void delete(int id, IntConsumer hostInvalidation, Runnable baseDeletion) {
        try { if (id != -1) hostInvalidation.accept(id); }
        finally { baseDeletion.run(); }
    }
}
