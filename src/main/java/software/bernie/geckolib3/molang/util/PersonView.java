package software.bernie.geckolib3.molang.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import software.bernie.geckolib3.molang.context.IContext;

/**
 * Camera/person-view helpers used by the {@code is_first_person} and {@code is_in_inventory} queries.
 */
public final class PersonView {

    /** 1.20.1 {@code CameraType.FIRST_PERSON}. */
    public static final int FIRST_PERSON = 0;
    /** 1.20.1 {@code CameraType.THIRD_PERSON_BACK}. */
    public static final int THIRD_PERSON_BACK = 1;
    /** 1.20.1 {@code CameraType.THIRD_PERSON_FRONT}. */
    public static final int THIRD_PERSON_FRONT = 2;

    private PersonView() {}

    /**
     * The camera mode to report for this context.
     * <p>
     * 1.7.10's {@code gameSettings.thirdPersonView} uses the same 0/1/2 ordering the modern {@code CameraType} enum
     * does. Anything that is not the local player being drawn in the world reports the third-person front view, which
     * is how the original code kept models in GUIs facing the camera.
     */
    public static int getPersonView(IContext<? extends Entity> ctx) {
        Minecraft mc = ctx.mc();
        Entity entity = ctx.entity();
        if (mc != null && entity != null && entity == mc.thePlayer && ctx.animationEvent().isRenderingInLevel()) {
            return mc.gameSettings.thirdPersonView;
        }
        return THIRD_PERSON_FRONT;
    }

    public static boolean isFirstPersonView(IContext<? extends Entity> ctx) {
        return getPersonView(ctx) == FIRST_PERSON;
    }
}
