package software.bernie.geckolib3.molang.context;

import org.jetbrains.annotations.Nullable;

import software.bernie.geckolib3.molang.storage.IForeignVariableStorage;

/**
 * Static registry for the host's {@link ForeignStorageResolver}. See that interface for why this indirection exists.
 */
public final class MolangForeignStorage {

    private static volatile ForeignStorageResolver resolver;

    private MolangForeignStorage() {}

    public static void setResolver(ForeignStorageResolver newResolver) {
        resolver = newResolver;
    }

    /** The storage for {@code entity}, or {@code null} when no resolver is installed or it knows nothing about it. */
    @Nullable
    public static IForeignVariableStorage resolve(Object entity) {
        ForeignStorageResolver current = resolver;
        return current == null ? null : current.resolve(entity);
    }
}
