package com.fox.ysmu.client.compat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.client.texture.PbrComponentTexture;
import com.fox.ysmu.ysmu;

/** Optional exact-class host loader, mirroring upstream IrisCompat:154-199. No Angelica linkage. */
public final class AngelicaPbrCompat {
    /**
     * The host's {@code PBRTextureManager} singleton, or {@code null} when no host is present.
     * <p>
     * {@code volatile} because {@link #isAvailable()} is read from the model loader thread (the PBR component decode
     * gate in {@code ClientModelManager#buildModel}) while {@link #init()} writes it on the client thread. The loader
     * task submission already gives the write a happens-before edge, but the field is cheap to publish properly and
     * this keeps the answer correct for any future off-thread reader.
     */
    private static volatile Object manager;
    private static Method delete;
    private static boolean attempted;
    private AngelicaPbrCompat() {}

    public static void init() {
        if (attempted) return;
        attempted = true;
        try {
            Class<?> loader = Class.forName("net.coderbot.iris.texture.pbr.loader.PBRTextureLoader");
            Class<?> registry = Class.forName("net.coderbot.iris.texture.pbr.loader.PBRTextureLoaderRegistry");
            Class<?> consumer = Class.forName("net.coderbot.iris.texture.pbr.loader.PBRTextureLoader$PBRTextureConsumer");
            Class<?> hostManager = Class.forName("net.coderbot.iris.texture.pbr.PBRTextureManager");
            manager = hostManager.getField("INSTANCE").get(null);
            delete = hostManager.getMethod("onDeleteTexture", int.class);
            Method normal = consumer.getMethod("acceptNormalTexture", net.minecraft.client.renderer.texture.AbstractTexture.class);
            Method specular = consumer.getMethod("acceptSpecularTexture", net.minecraft.client.renderer.texture.AbstractTexture.class);
            Object proxy = Proxy.newProxyInstance(loader.getClassLoader(), new Class<?>[] {loader}, (self, method, args) -> {
                if (method.getName().equals("load")) {
                    OuterFileTexture base = (OuterFileTexture) args[0];
                    Map<Integer, PbrComponentTexture> components = base.preparePbr(
                        (net.minecraft.client.resources.IResourceManager) args[1]);
                    PbrPublication.publish(components, (role, component) ->
                        (role == 1 ? normal : specular).invoke(args[2], component));
                    return null;
                }
                if (method.getName().equals("toString")) return "YSMU PBR loader";
                if (method.getName().equals("hashCode")) return System.identityHashCode(self);
                if (method.getName().equals("equals")) return self == args[0];
                return null;
            });
            Object registryInstance = registry.getField("INSTANCE").get(null);
            Object existing = registry.getMethod("getLoader", Class.class).invoke(registryInstance, OuterFileTexture.class);
            if (existing == null) {
                registry.getMethod("register", Class.class, loader).invoke(registryInstance, OuterFileTexture.class, proxy);
            } else {
                ysmu.LOG.warn("OuterFileTexture already has a PBR loader; leaving host registration unchanged");
            }
        } catch (ClassNotFoundException absent) {
            manager = null; delete = null;
        } catch (ReflectiveOperationException | LinkageError failure) {
            manager = null; delete = null;
            ysmu.LOG.warn("Optional Angelica PBR loader unavailable: {}", failure.toString());
        }
    }

    public static void invalidate(int oldBaseId) {
        if (manager == null) return;
        try { delete.invoke(manager, oldBaseId); }
        catch (ReflectiveOperationException failure) {
            ysmu.LOG.warn("Could not invalidate Angelica PBR holder {}: {}", oldBaseId, failure.toString());
        }
    }

    /**
     * Whether this client has a host that will consume an {@link OuterFileTexture}'s PBR components, i.e. whether the
     * loader registered above can ever be called.
     * <p>
     * The components (normal/specular images) are decoded on the model loader thread and retained by the published
     * texture, and their <b>only</b> consumer is {@link OuterFileTexture#preparePbr}, which is called from the proxy
     * loader this class registers with the host's {@code PBRTextureLoaderRegistry} (see {@link #init()}). Without a
     * host there is no caller, so decoding them would retain two extra {@code w·h·4} pixel buffers per PBR texture for
     * nothing - the payload's compressed bytes are dropped when the model is published, so nothing could use them
     * later either.
     * <p>
     * {@code init()} is called once from {@code ClientProxy#init} ({@code ClientProxy.java:43}), before any model is
     * loaded (the built-in default is materialised after the first {@code TextureStitchEvent.Post}, server models
     * after login), so this answer is fixed before the first decode and can be read from the loader thread. A host
     * that only becomes available after mod init is not supported - the field is only written by {@code init()}.
     */
    public static boolean isAvailable() {
        return manager != null;
    }
}
