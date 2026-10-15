package software.bernie.geckolib3.core.event.predicate;

import java.util.List;
import java.util.stream.Collectors;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.geo.RenderContext;

public class AnimationEvent<T extends IAnimatable> {

    private final T animatable;
    public double animationTick;
    private final float limbSwing;
    private final float limbSwingAmount;
    private final float partialTick;
    private final boolean isMoving;
    private final List<Object> extraData;
    /**
     * The pass this event was built for. {@link RenderContext#NONE} until a caller attaches one, which is the
     * context of an engine-built event - today's behaviour, and mutable.
     */
    private RenderContext renderContext = RenderContext.NONE;
    protected AnimationController controller;

    public AnimationEvent(T animatable, float limbSwing, float limbSwingAmount, float partialTick, boolean isMoving,
        List<Object> extraData) {
        this.animatable = animatable;
        this.limbSwing = limbSwing;
        this.limbSwingAmount = limbSwingAmount;
        this.partialTick = partialTick;
        this.isMoving = isMoving;
        this.extraData = extraData;
    }

    /**
     * Gets the amount of ticks that have passed in either the current transition or
     * animation, depending on the controller's AnimationState.
     *
     * @return the animation tick
     */
    public double getAnimationTick() {
        return animationTick;
    }

    public T getAnimatable() {
        return animatable;
    }

    public float getLimbSwing() {
        return limbSwing;
    }

    public float getLimbSwingAmount() {
        return limbSwingAmount;
    }

    public float getPartialTick() {
        return partialTick;
    }

    public boolean isMoving() {
        return isMoving;
    }

    public AnimationController getController() {
        return controller;
    }

    public void setController(AnimationController controller) {
        this.controller = controller;
    }

    public List<Object> getExtraData() {
        return extraData;
    }

    /**
     * The pass this event belongs to; never {@code null} - {@link RenderContext#NONE} means the caller declared no
     * pass, which is every event the engine builds itself. Upstream carries the same value on its event
     * ({@code tmp/YesSteveModel-dev-1.20 .../geckolib3/core/event/predicate/AnimationEvent.java:15,72-74}), and its
     * {@code AnimatableEntity:452-454} resolves the immutability bit through it.
     */
    public RenderContext getRenderContext() {
        return renderContext;
    }

    /** Attaches the pass this event belongs to; a {@code null} context means {@link RenderContext#NONE}. */
    public void setRenderContext(RenderContext renderContext) {
        this.renderContext = renderContext == null ? RenderContext.NONE : renderContext;
    }

    @SuppressWarnings("hiding")
    public <T> List<T> getExtraDataOfType(Class<T> type) {
        return extraData.stream()
            .filter(x -> type.isAssignableFrom(x.getClass()))
            .map(x -> type.cast(x))
            .collect(Collectors.toList());
    }
}
