package com.fox.ysmu.client.texture;

import java.util.Map;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

/**
 * The one place this port reaches into 1.7.10's {@link TextureManager} to drop a released texture.
 *
 * <h3>Why this exists at all</h3>
 * <p>1.7.10's texture manager has no way to remove an id. Its map is private
 * ({@code TextureManager.java:26}, {@code private final Map mapTextureObjects = Maps.newHashMap();}), the only
 * method that touches it destructively is {@code deleteTexture(ResourceLocation)}
 * ({@code TextureManager.java:153-161}), and that one <em>frees the GL texture but keeps the entry</em>:
 *
 * <ul>
 *   <li>the entry is the <b>strong reference</b> that keeps the released {@code OuterFileTexture} (and the decoded
 *       {@code BufferedImage} it holds) alive for the rest of the session, so without removing it the release frees
 *       VRAM but nothing on the heap - which is half of what the owner asked for;</li>
 *   <li>{@code TextureManager.onResourceManagerReload} ({@code :163-175}) re-uploads every entry in that map, so a
 *       kept entry would resurrect a texture the mod had released on the next F3+T / resource-pack reload.</li>
 * </ul>
 *
 * <p>There is therefore no platform call that means "release this texture" on 1.7.10; the closest is
 * {@code AbstractTexture.deleteGlTexture()} (which is what {@link CustomTextureManager.MinecraftHost} calls for the
 * GL half) plus this removal. Nothing here invents a GL path: {@code deleteGlTexture} is the same
 * {@code GL11.glDeleteTextures} call vanilla's own {@code TextureManager.deleteTexture} performs
 * ({@code TextureUtil.java:38-41}).
 *
 * <h3>How the field is reached, and why not reflection</h3>
 * <p>By an <b>access transformer</b>: {@code src/main/resources/META-INF/ysmu_at.cfg} widens the SRG field
 * {@code field_110585_a} (MCP {@code mapTextureObjects}) to {@code public}, the same file that already widens
 * {@code Minecraft.timer}. This replaced a reflective lookup that tried the MCP name and then the SRG name - the
 * pattern {@code FpsQuery.java:43-44} uses for a <em>static</em> field. It could never have worked here, and it
 * reported neither the bug nor its consequence: {@code TextureManager.mapTextureObjects} is an <b>instance</b> field,
 * and the lookup read it off {@code null}, which throws, so the class logged "cannot read ... mapTextureObjects" and
 * became a no-op - the GL half of the release ran, the heap half silently never did, and the javadoc above claimed
 * both. With the AT, a mapping change is a compile error rather than a runtime warning, and there is no second name to
 * try.
 */
final class TextureMapAccess {

    private TextureMapAccess() {}

    /**
     * Removes {@code id} from {@code manager}'s texture map, dropping the platform's strong reference to the texture.
     *
     * @return whether an entry was removed; {@code false} for a null argument or an id the platform never held, which
     *         is harmless
     */
    static boolean remove(TextureManager manager, ResourceLocation id) {
        if (manager == null || id == null) {
            return false;
        }
        Map<?, ?> map = manager.mapTextureObjects;
        return map != null && map.remove(id) != null;
    }
}
