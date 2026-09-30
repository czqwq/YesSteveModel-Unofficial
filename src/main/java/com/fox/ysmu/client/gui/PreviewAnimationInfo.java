package com.fox.ysmu.client.gui;

import org.apache.commons.lang3.StringUtils;

/**
 * The animation names a GUI preview entity should play, mirroring upstream's {@code PreviewAnimationInfo}.
 * <p>
 * Three controllers read it: the cap controller plays {@link #getPreview()}, the hover controller plays
 * {@link #getHover()} and the focus controller plays {@link #getFocus()}. A blank value means "no animation", which is
 * what makes a model without those reserved animations behave exactly as before.
 */
public class PreviewAnimationInfo {

    private String preview = "";
    private String hover = "";
    private String focus = "";

    public String getPreview() {
        return this.preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public boolean hasPreview() {
        return StringUtils.isNoneBlank(this.preview);
    }

    public boolean hasPreview(String previewAnimation) {
        return hasPreview() && previewAnimation.equals(this.preview);
    }

    public String getHover() {
        return this.hover;
    }

    public void setHover(String hover) {
        this.hover = hover;
    }

    public String getFocus() {
        return this.focus;
    }

    public void setFocus(String focus) {
        this.focus = focus;
    }

    /** Clears every channel, used when a preview stops being drawn. */
    public void clear() {
        this.preview = "";
        this.hover = "";
        this.focus = "";
    }
}
