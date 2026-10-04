package software.bernie.geckolib3.molang.context;

import java.util.function.Supplier;

/**
 * Factory hook for {@link IMolangSoundManager}.
 * <p>
 * The engine creates one sound manager per animation/controller context. A host mod installs its own implementation
 * during startup; when nothing is installed a no-op manager is used so the engine stays inert instead of throwing.
 */
public final class MolangSoundManagers {

    private static final IMolangSoundManager NOOP = new IMolangSoundManager() {
        @Override
        public void playSound(Object animatable, int channel, String soundName, boolean looping, Object extra) {}

        @Override
        public void stopAllPlayingSounds() {}
    };

    private static volatile Supplier<IMolangSoundManager> factory = () -> NOOP;

    private MolangSoundManagers() {}

    /** Installs the host's sound manager factory. Passing {@code null} restores the no-op manager. */
    public static void setFactory(Supplier<IMolangSoundManager> newFactory) {
        factory = newFactory == null ? () -> NOOP : newFactory;
    }

    /** Creates a new sound manager for one animation context. */
    public static IMolangSoundManager create() {
        IMolangSoundManager manager = factory.get();
        return manager == null ? NOOP : manager;
    }
}
