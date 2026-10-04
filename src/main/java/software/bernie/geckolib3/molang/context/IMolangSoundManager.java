package software.bernie.geckolib3.molang.context;

/**
 * Sound keyframes are played through the host mod's sound system, not through this library, so the engine only
 * carries this handle around.
 */
public interface IMolangSoundManager {

    /**
     * Plays one model sound.
     *
     * @param animatable the model instance that asked for the sound
     * @param channel    the sound channel/priority the model used
     * @param soundName  the raw sound name from the animation file
     * @param looping    whether the sound repeats
     * @param extra      implementation specific payload, may be {@code null}
     */
    void playSound(Object animatable, int channel, String soundName, boolean looping, Object extra);

    /** Stops everything this manager started. */
    void stopAllPlayingSounds();
}
