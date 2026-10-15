package software.bernie.geckolib3.molang.context;

import org.jetbrains.annotations.Nullable;

import software.bernie.geckolib3.molang.storage.IForeignVariableStorage;

/**
 * Resolves the persistent ("foreign"/roaming) MoLang variable storage for an entity.
 * <p>
 * On 1.20.1 each animatable capability handed out its own storage; 1.7.10 has no capabilities, so the host mod
 * installs a resolver and decides which entities carry roaming variables.
 */
public interface ForeignStorageResolver {

    /** The storage owned by {@code entity}, or {@code null} when it has none. */
    @Nullable
    IForeignVariableStorage resolve(Object entity);
}
