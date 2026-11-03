package com.fox.ysmu.client.gui.button;

import java.text.DecimalFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.lwjgl.opengl.GL11;

/**
 * A flat horizontal slider for a pack-declared numeric setting, in the style of the other widgets in this package.
 * <p>
 * Upstream uses Forge's slider for a {@code range} config form ({@code client/gui/AnimationRouletteScreen.java:369-386}
 * builds a {@code FlatSlider extends ForgeSlider}); 1.7.10 has no equivalent, so this class reproduces the parts of
 * {@code ForgeSlider} the form depends on:
 * <ul>
 * <li><b>Quantisation anchored at zero.</b> {@code ForgeSlider#snapToNearest:166-180} snaps the dragged value to
 * {@code stepSize * Math.round(value / stepSize)} - and only then clamps it into the range - so the admitted values are
 * the multiples of the step, not "min + k*step". The step is used with its absolute value ({@code :54}). A form whose
 * range does not start on a step multiple therefore lands on the same values upstream would use.</li>
 * <li><b>Reverse ranges work.</b> A pack may declare a reverse range - LaPluma 2.5.3's
 * {@code 自定义动作手臂/左小臂_Rx} is {@code min: 0, max: -180} - and upstream hands those straight to the slider
 * ({@code AnimationRouletteScreen.java:379}); {@code ForgeSlider} handles them by clamping into
 * {@code [maxValue, minValue]} and mapping the normalised position back through both ends ({@code :174-179}). Both the
 * drag handling and the knob drawing here use the sorted pair, so the knob position is right for either order.</li>
 * <li><b>The displayed number follows the step's precision.</b> {@code ForgeSlider:58-74,115-117} formats the value with
 * a {@code DecimalFormat} derived from the step: {@code "0"} for an integral step, otherwise the step's own decimal
 * places ({@code step 0.1} -> one decimal, {@code 0.05} -> two). The old display was always {@code %.2f}, so an
 * integral step read "2.00" where upstream reads "2".</li>
 * </ul>
 * The callback fires only when the quantised value actually changes, so a drag does not spam the server
 * ({@code ForgeSlider:150-160} applies on change too). See {@code .agent/phase15-roaming-variables.md}, Milestone 5,
 * and {@code .agent/phase64-model-config-screen-review.md}.
 */
public class FlatSlider extends GuiButton {

    private final float min;
    private final float max;
    private final float step;
    private final String label;
    private final SliderCallback callback;
    private final DecimalFormat format;

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
        this.format = displayFormat(step);
        this.value = clamp(value);
        updateDisplayString();
    }

    /**
     * The formatter upstream derives from the step ({@code ForgeSlider:58-74}): an integral step shows no decimals, a
     * fractional step shows as many decimals as the step itself has, and a step of 0 (continuous) uses the
     * {@code precision = 0} the form passes, i.e. no decimals either. The locale is the JVM default, exactly like
     * upstream's {@code new DecimalFormat(pattern)}.
     */
    private static DecimalFormat displayFormat(float step) {
        float magnitude = Math.abs(step);
        if (!Float.isFinite(magnitude) || magnitude <= 0f) {
            return new DecimalFormat("0");
        }
        if (magnitude == (float) Math.floor(magnitude)) {
            return new DecimalFormat("0");
        }
        return new DecimalFormat(Float.toString(magnitude).replaceAll("\\d", "0"));
    }

    public float getValue() {
        return this.value;
    }

    /** Sets the value without firing the callback, for a screen that refreshes the display from a read program. */
    public void setValue(float newValue) {
        this.value = clamp(newValue);
        updateDisplayString();
    }

    /**
     * Clamps and quantises a raw setting value the way {@code ForgeSlider#snapToNearest:166-180} does: snap to the
     * nearest multiple of the step first, then clamp into the range, with the sorted pair so a reverse range
     * ({@code min > max}) keeps working. The step is applied as its absolute value ({@code ForgeSlider:54}).
     */
    private float clamp(float raw) {
        float low = Math.min(this.min, this.max);
        float high = Math.max(this.min, this.max);
        float bounded = raw;
        float magnitude = Math.abs(this.step);
        if (magnitude > 0f) {
            bounded = magnitude * Math.round(bounded / magnitude);
        }
        return Math.max(low, Math.min(high, bounded));
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
        this.displayString = this.label + ": " + this.format.format(this.value);
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
        // Normalised against the sorted pair: a reverse range (min > max) has a negative span, which used to make the
        // knob and the filled part sit at the left edge whatever the value was.
        float low = Math.min(this.min, this.max);
        float high = Math.max(this.min, this.max);
        float ratio = high > low ? (this.value - low) / (high - low) : 0f;
        ratio = Math.max(0f, Math.min(1f, ratio));
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
