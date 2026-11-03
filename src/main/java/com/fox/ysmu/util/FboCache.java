package com.fox.ysmu.util;

import java.nio.FloatBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.GL11;

/**
 * Reusable off-screen framebuffer cache for model previews.
 * <p>
 * Manages a {@link Framebuffer} with automatic resizing, dirty-flag
 * invalidation, and periodic refresh.  Handles the boilerplate of binding,
 * clearing, restoring the main framebuffer, and drawing the cached texture
 * as a GUI quad.
 * <p>
 * Three consumers, one per place this port bakes a model into a texture:
 * <ul>
 *   <li>{@code client/renderer/HudPreviewCache} - the HUD selfie paperdoll, one instance for the whole client.
 *       Device-sized, because it inherits the GUI projection the overlay event already installed.</li>
 *   <li>{@code client/gui/button/ModelButton} and {@code client/gui/button/TextureButton} - one instance per
 *       catalog/model-picker tile, sized to the tile ({@code width * guiScale}). Each of those holds its own
 *       instance and releases it from {@code dispose()} on a page flip or screen close.</li>
 * </ul>
 * <p>
 * Note in particular that {@link #checkAndResize} resets its own countdown whenever the interval
 * <b>value</b> changes, which is why {@code HudPreviewCache} passes the constant 0 and keeps its measured refresh
 * clock on its own side.
 *<p>
 * <b>State this class owns.</b> {@link #bind()} saves the clear colour and {@link #unbind} puts it back, because
 * every bake clears to a transparent colour ({@code (0,0,0,0)}) that must not outlive the bake.
 * {@link #draw(int, int, int, int)} leaves the blend function at the 1.7.10 GUI baseline after its one quad, since
 * the premultiplied function it needs for the blit is wrong for everything drawn after it in the same frame.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * FboCache cache = new FboCache();
 *
 * // Each frame:
 * int fbW = ..., fbH = ...;
 * if (cache.checkAndResize(fbW, fbH, refreshInterval)) {
 *     cache.bind();
 *     GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
 *     // ... set up projection matrix for the FBO ...
 *     // ... render model ...
 *     cache.unbind(Minecraft.getMinecraft());
 * }
 * cache.draw(x, y, w, h); // draw cached texture
 * }</pre>
 */
public class FboCache {

    private Framebuffer fbo;
    private boolean dirty = true;
    private int framesUntilRefresh = 0;
    private int lastRefreshInterval = -1;
    /** Readback target for the clear colour {@link #bind()} saves; heap, so no off-heap allocation per cache. */
    private final FloatBuffer clearColorReadback = FloatBuffer.allocate(4);
    /** True between {@link #bind()} and {@link #unbind(Minecraft)}: only then is {@link #clearColorReadback} valid. */
    private boolean clearColorSaved;
    private final float[] savedClearColor = new float[4];
    /** Clear colour a {@link Framebuffer} creation clobbers; separate from the {@link #bind()} bracket's copy. */
    private final float[] creationClearColor = new float[4];

    /** Check whether the cache needs to be re-rendered.
     *  Also handles FBO creation / resizing.  Call once per frame.
     *
     *  <p>{@code new Framebuffer(...)} is not state-neutral: its constructor clears the new target
     *  ({@code Framebuffer#createFramebuffer:133} → {@code framebufferClear:265-279}), which binds the new framebuffer,
     *  moves the viewport to it, and calls {@code glClearColor} with the framebuffer's own colour. The viewport is the
     *  caller's business (both call shapes set it), but the clear colour is restored here so creating or resizing the
     *  cache does not change the colour every later {@code glClear} in the process would use.
     *
     * @param fbWidth         desired FBO width in physical pixels
     * @param fbHeight        desired FBO height in physical pixels
     * @param refreshInterval frames between forced refreshes (0 = never, -1 = every frame)
     * @return {@code true} if the render should proceed (cache is dirty or FBO was resized)
     */
    public boolean checkAndResize(int fbWidth, int fbHeight, int refreshInterval) {
        if (fbWidth < 1) fbWidth = 1;
        if (fbHeight < 1) fbHeight = 1;

        if (refreshInterval != lastRefreshInterval) {
            framesUntilRefresh = 0;
            lastRefreshInterval = refreshInterval;
        }

        boolean timeToRefresh = refreshInterval > 0 && --framesUntilRefresh <= 0;
        boolean needRender = dirty || timeToRefresh;

        // Resize FBO if dimensions changed
        if (fbo == null || fbo.framebufferTextureWidth != fbWidth
            || fbo.framebufferTextureHeight != fbHeight) {
            readClearColor(creationClearColor);
            if (fbo != null) fbo.deleteFramebuffer();
            fbo = new Framebuffer(fbWidth, fbHeight, true);
            fbo.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClearColor(creationClearColor[0], creationClearColor[1], creationClearColor[2],
                creationClearColor[3]);
            needRender = true;
        }

        if (needRender) {
            dirty = false;
            framesUntilRefresh = refreshInterval;
        }
        return needRender;
    }

    /** Current {@code GL_COLOR_CLEAR_VALUE} into {@code out}. One state read; the caller decides when it is worth it. */
    private void readClearColor(float[] out) {
        clearColorReadback.clear();
        GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, clearColorReadback);
        for (int i = 0; i < 4; i++) {
            out[i] = clearColorReadback.get(i);
        }
    }

    /** Bind the off-screen FBO (viewports NOT set — caller must call
     *  {@code GL11.glViewport} explicitly if needed).
     *  <p>
     *  Also remembers the current clear colour so {@link #unbind(Minecraft)} can put it back: every bake clears the
     *  FBO to {@code (0,0,0,0)} (the model is drawn on nothing, so anything else would show up as a background), and
     *  that colour used to stay behind for whatever cleared next in the same frame. One state read per bake, not per
     *  frame - {@link #checkAndResize} decides whether a bake happens. */
    public void bind() {
        readClearColor(savedClearColor);
        clearColorSaved = true;
        fbo.bindFramebuffer(false);
    }

    /** Restore the main (screen) framebuffer.  Call in {@code finally} block.
     *  <p>
     *  Restores the clear colour {@link #bind()} saved as well, so an exception between the two leaves no more state
     *  behind than a normal path does. The flag makes a bare {@code unbind} (a caller whose {@code bind} threw) a
     *  no-op for the colour instead of restoring a stale one. */
    public void unbind(Minecraft mc) {
        if (clearColorSaved) {
            clearColorSaved = false;
            GL11.glClearColor(savedClearColor[0], savedClearColor[1], savedClearColor[2], savedClearColor[3]);
        }
        mc.getFramebuffer().bindFramebuffer(true);
    }

    /** Draw the cached FBO texture as a GUI quad covering the given area.
     *  Resets depth test, lighting, colour-material, enables blending, and
     *  resets the texture matrix (GeckoLib may leave a transform).
     *  <p>
     *  <b>The blit is a premultiplied-alpha composite, not a straight one.</b> Everything a bake draws into the FBO is
     *  already multiplied by its own alpha: the FBO is cleared to {@code (0,0,0,0)} and translucent geometry is drawn
     *  with {@code SRC_ALPHA/ONE_MINUS_SRC_ALPHA} (the {@code MaterialDrawSession} translucent partition,
     *  {@code MaterialDrawSession.java:348}), which onto a zero dst yields {@code (C*a, a)}; fully opaque geometry
     *  ({@code a == 255}) is unchanged by that multiplication. Some faces are drawn with blending off instead - the
     *  cutout partition, alpha test at 0.1 - but a face only reaches that partition when every texel of its uv
     *  footprint is 0 or 255 ({@code MaterialBake.java:148-163}), so its surviving texels are the {@code a == 255}
     *  ones. Using {@code SRC_ALPHA} again here would multiply the alpha twice and darken every semi-transparent
     *  texel (a 50 % texel would be composited at 25 %).
     *  <p>
     *  The blend function is put back to the 1.7.10 GUI baseline ({@code SRC_ALPHA/ONE_MINUS_SRC_ALPHA}, the state
     *  {@code GuiIngameForge.renderHotbar:192-194} and {@code Gui.drawGradientRect:100} set) before returning: the
     *  premultiplied function is correct for this quad and wrong for the font, hotbar and item draws that follow it
     *  in the same frame. {@code GL_TEXTURE_2D} is enabled rather than assumed - the blit is a textured quad and the
     *  previous draw is free to have left texturing off. */
    public void draw(int x, int y, int w, int h) {
        if (fbo == null) return;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        // Reset texture matrix — GeckoLib may leave a transform that would
        // distort the UV mapping and cause flickering/invisible textures.
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fbo.framebufferTexture);
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertexWithUV(x, y + h, 0.0, 0.0, 0.0);
        tess.addVertexWithUV(x + w, y + h, 0.0, 1.0, 0.0);
        tess.addVertexWithUV(x + w, y, 0.0, 1.0, 1.0);
        tess.addVertexWithUV(x, y, 0.0, 0.0, 1.0);
        tess.draw();
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Force a re-render on the next frame. */
    public void invalidate() {
        dirty = true;
    }

    /** Release the FBO.  Call when the cache is no longer needed. */
    public void delete() {
        if (fbo != null) {
            fbo.deleteFramebuffer();
            fbo = null;
        }
    }

    /** The raw FBO texture ID, or 0 if no FBO has been created yet. */
    public int getTextureId() {
        return fbo != null ? fbo.framebufferTexture : 0;
    }
}
