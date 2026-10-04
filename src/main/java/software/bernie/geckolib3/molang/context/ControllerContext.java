package software.bernie.geckolib3.molang.context;

public class ControllerContext {
    private final boolean hasSoundManager;
    private IMolangSoundManager soundManager;
    private boolean allAnimationsFinished;
    private boolean anyAnimationFinished;

    public ControllerContext(boolean hasSoundManager) {
        this.hasSoundManager = hasSoundManager;
    }

    public void setAllAnimationsFinished(boolean allAnimationsFinished) {
        this.allAnimationsFinished = allAnimationsFinished;
    }

    public void setAnyAnimationFinished(boolean anyAnimationFinished) {
        this.anyAnimationFinished = anyAnimationFinished;
    }

    public boolean isAllAnimationsFinished() {
        return allAnimationsFinished;
    }

    public boolean isAnyAnimationFinished() {
        return anyAnimationFinished;
    }

    public IMolangSoundManager soundManager() {
        if (!hasSoundManager) {
            return null;
        }
        if (soundManager == null) {
            soundManager = MolangSoundManagers.create();
        }
        return soundManager;
    }
}
