package software.bernie.geckolib3.core.molang;

import java.util.Map;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

/**
 * Contract a host mod implements so {@link MolangPhysicsRuntime} can identify the animatable instance currently
 * being evaluated.
 * <p>
 * The physics runtime keys its per-scope state (first/second order filters and roaming variables) on the owning
 * entity plus the active model and animation ids, so the host has to supply all three. Any of the three may be
 * {@code null}. The ids are compared by value, so a host that builds them anew on every frame - a detached preview
 * that re-derives its model id per render, for example - still gets the same scope each time.
 */
public interface IMolangPhysicsScope {

    /** The living entity the animation belongs to, or {@code null} for a detached preview. */
    EntityLivingBase getMolangEntity();

    /** Resource location of the model currently animating, used as part of the scope key. */
    ResourceLocation getMolangModelId();

    /** Resource location of the animation currently playing, used as part of the scope key. */
    ResourceLocation getMolangAnimationId();

    /**
     * Roaming variables the animatable itself wants visible to this evaluation, or {@code null} for none.
     * <p>
     * {@link MolangPhysicsRuntime#begin} already seeds the scope from the variables the host registered for
     * {@link #getMolangEntity()}. That is enough for anything that owns a real entity, but a detached preview has
     * none, so the animatable is the only place that knows where its roaming values come from. Upstream has the same
     * shape: its animation processor is handed a remote struct through {@code putRemoteStruct} right before every
     * evaluation, which is how a GUI preview reads the same {@code v.roaming.*} namespace as the world entity it is
     * previewing.
     * <p>
     * Names that are absent from the returned map keep whatever the animation assigned, so a host may return only the
     * values it owns.
     */
    default Map<String, Double> getMolangVariables() {
        return null;
    }
}
