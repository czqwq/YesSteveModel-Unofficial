package com.fox.ysmu.client.compat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.client.texture.PbrComponentTexture;
import com.fox.ysmu.ysmu;

/** Optional exact-class host loader, mirroring upstream IrisCompat:154-199. No Angelica linkage. */
public final class AngelicaPbrCompat {
    private static Object manager;
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
}
