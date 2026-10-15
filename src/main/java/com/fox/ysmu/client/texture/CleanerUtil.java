package com.fox.ysmu.client.texture;

import java.lang.ref.Cleaner;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A direct port of upstream's {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/util/CleanerUtil.java}
 * (the same class name, the same single static {@link Cleaner} at {@code :8}, the same two call shapes at
 * {@code :10-12} and {@code :14-16}).
 *
 * <p>It is the whole reason {@link CustomTextureManager} needs no idle timer: {@link Cleaner#register(Object, Runnable)}
 * runs the callback once its referent becomes <b>unreachable</b>, which is how a texture holder that nobody needs any
 * more asks to be released.
 *
 * <p><b>Why this Java 9+ API is legitimate here.</b> The project does not compile Java 8 source: {@code
 * gradle.properties} sets {@code enableModernJavaSyntax = jvmDowngrader} with {@code downgradeTargetVersion = 8}, so
 * the source may use Java 9+ stdlib APIs and JVM Downgrader rewrites those calls onto its own stubs
 * ({@code xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api}) so the produced jar still carries JVM 8 class files.
 * Two consequences worth stating rather than leaving implicit:
 * <ul>
 *   <li>The mod's normal runtime is a modern JVM, where {@link Cleaner} is native.</li>
 *   <li>On a Java 8 launcher the calls land on the downgrader's stubs, so the API needs those stubs to be present at
 *       runtime. {@code jvmDowngraderStubsProvider} is deliberately left empty in {@code gradle.properties} until a
 *       real Java 8 failure is reported; that decision is the owner's and is not changed here.</li>
 * </ul>
 *
 * <p>Package-private and in this package rather than upstream's {@code com.elfmcys.ysm.util}, because its only user is
 * {@link CustomTextureManager} and the callback type it carries is that class's own cleanup event - the shape is
 * upstream's, the placement is this port's. See {@code .agent/phase34-texture-release.md}.
 */
final class CleanerUtil {

    /** Upstream {@code :8}: one cleaner for the whole mod. */
    private static final Cleaner CLEANER = Cleaner.create();

    private CleanerUtil() {}

    /** Upstream {@code :10-12}. */
    static <T> Cleaner.Cleanable ref(Object obj, T arg, Consumer<T> cleanAction) {
        return CLEANER.register(obj, () -> cleanAction.accept(arg));
    }

    /** Upstream {@code :14-16}. */
    static <T0, T1> Cleaner.Cleanable ref(Object obj, T0 arg0, T1 arg1, BiConsumer<T0, T1> cleanAction) {
        return CLEANER.register(obj, () -> cleanAction.accept(arg0, arg1));
    }
}
