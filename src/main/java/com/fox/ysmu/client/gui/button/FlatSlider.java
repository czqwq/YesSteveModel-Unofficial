package com.fox.ysmu.client.gui.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.lwjgl.opengl.GL11;

/**
 * A flat horizontal slider for a pack-declared numeric setting, in the style of the other widgets in this package.
 * <p>
 * Upstream uses Forge's slider for a {@code range} config form; 1.7.10 has no equivalent, so this class provides the
 * same behaviour: a value between {@code min} and {@code max}, quantised to {@code step} when a step is given, and a
 * callback that fires only when the value actually changes so a drag does not spam the server. See
 * {@code .agent/phase15-roaming-variables.md}, Milestone 5.
 */
public class FlatSlider extends GuiButton {

    private final float min;
    private final float max;
    private final float step;
    private final String label;
    private final SliderCallback callback;

    private float value;
    private boolean dragging;

    /** Receives the new value every time the user moves the knob to a different quantised position. */
    public interface SliderCallback {

        void onValueChanged(FlatSlider slider, float value);
    }

    public FlatSlider(int id, int x, int y, int width, String label, float min, float max, float step, float value,
        SliderCallback callback) {
        super(id, x, y, width, 20, label);
        this.min = min;
        this.max = max;
        this.step = step;
        this.label = label == null ? "" : label;
        this.callback = callback;
        this.value = clamp(value);
        updateDisplayString();
    }

    public float getValue() {
        return this.value;
    }

    /** Sets the value without firing the callback, for a screen that rebuilds its widgets. */
    public void setValue(float newValue) {
        this.value = clamp(newValue);
        updateDisplayString();
    }

    /**
     * Clamps against the **sorted** pair, not against {@code min}/{@code max} as declared.
     * <p>
     * A pack may declare a reverse range - LaPluma 2.5.3's {@code 自定义动作手臂/左小臂_Rx} is {@code min: 0,
     * max: -180} - and upstream hands those straight to a slider
     * ({@code com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java:379}). The old body used
     * {@code Math.max(min, Math.min(max, raw))}, which for that pair answers {@code 0} for every input, so the knob
     * moved and the value never did. The interpolation in {@link #applyMouse} and the ratio in
     * {@link #drawButton} already work for either order (both are expressed as {@code min + ratio * (max - min)}),
     * so only the bound and the step base had to change. Behaviour for {@code min < max} is identical.
     */
    private float clamp(float raw) {
        float low = Math.min(this.min, this.max);
        float high = Math.max(this.min, this.max);
        float bounded = Math.max(low, Math.min(high, raw));
        if (this.step > 0f) {
            float steps = Math.round((bounded - low) / this.step);
            bounded = Math.max(low, Math.min(high, low + steps * this.step));
        }
        return bounded;
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!super.mousePressed(mc, mouseX, mouseY)) {
            return false;
        }
        this.dragging = true;
        applyMouse(mouseX);
        return true;
    }

    /** Called by the owning screen from {@code mouseClickMove}; {@code GuiButton} has no drag hook of its own. */
    public void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (this.dragging && this.visible) {
            applyMouse(mouseX);
        }
    }

    /** Called by the owning screen from {@code mouseMovedOrUp}. */
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }

    public boolean isDragging() {
        return this.dragging;
    }

    private void applyMouse(int mouseX) {
        int trackLeft = this.xPosition + 4;
        int trackWidth = this.width - 8;
        float ratio = trackWidth <= 0 ? 0f : (float) (mouseX - trackLeft) / (float) trackWidth;
        ratio = Math.max(0f, Math.min(1f, ratio));
        float next = clamp(this.min + ratio * (this.max - this.min));
        if (next == this.value) {
            return;
        }
        this.value = next;
        updateDisplayString();
        if (this.callback != null) {
            this.callback.onValueChanged(this, next);
        }
    }

    private void updateDisplayString() {
        this.displayString = String.format("%s: %.2f", this.label, this.value);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height;
        // Track.
        drawRect(this.xPosition, this.yPosition + 8, this.xPosition + this.width, this.yPosition + 12, 0xFF000000);
        int trackLeft = this.xPosition + 4;
        int trackWidth = this.width - 8;
        float ratio = this.max > this.min ? (this.value - this.min) / (this.max - this.min) : 0f;
        int filled = Math.round(trackWidth * ratio);
        drawRect(trackLeft, this.yPosition + 8, trackLeft + filled, this.yPosition + 12, 0xFF9E9E9E);
        // Knob.
        int knobX = trackLeft + filled - 2;
        drawRect(knobX, this.yPosition + 5, knobX + 4, this.yPosition + 15, 0xFFF3EFE0);
        // Label and value, centred like the other flat widgets.
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        drawCenteredString(mc.fontRenderer, this.displayString, this.xPosition + this.width / 2, this.yPosition - 10,
            0xF3EFE0);
    }
}
