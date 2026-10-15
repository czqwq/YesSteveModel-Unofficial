package software.bernie.geckolib3.molang.context;

/**
 * Optional extra state a {@link IMolangStateTracker} may expose for player models.
 * <p>
 * The client does not always have authoritative player state, so the 1.20.1 engine read health, experience and
 * flight from the capability that tracked the player. 1.7.10 has no capabilities; a host that tracks remote players
 * implements this on its state tracker and the queries prefer it over the raw entity, which is exactly the split the
 * original code had.
 */
public interface IMolangPlayerState {

    /** True when the tracker belongs to the client's own player, in which case the raw entity is authoritative. */
    boolean isLocalPlayer();

    boolean isFlying();

    int getExpLevel();

    float getHealth();

    float getMaxHealth();

    /** Degrees per tick the tracked player is turning, used by the {@code query.yaw_speed} variable. */
    float getYawSpeed();
}
