package com.fox.ysmu.model.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.upload.ModelUploadTarget;
import com.fox.ysmu.network.message.HandleFile;
import com.fox.ysmu.network.message.RefreshModelManage;
import com.fox.ysmu.network.message.RequestServerModelInfo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The three phase55 model-manage packets: wire round trips, N-10 soft-fail decoding and handler direction.
 *
 * <p>No handler is invoked (each one resolves a live player or opens a client screen); the direction is pinned the
 * same way {@code YsmCommandTest} pins it for the command packets: a handler that never references the {@code Side}
 * enum cannot carry the receiving-side guard that keeps a mis-delivered packet from running.</p>
 */
class ModelManageProtocolTest {

    @Test
    void theListPacketRoundTripsBothRoots() {
        List<RequestServerModelInfo.Info> custom = new ArrayList<>();
        custom.add(new RequestServerModelInfo.Info("model.ysm", Type.YSM, 1234L));
        custom.add(new RequestServerModelInfo.Info("folderModel", Type.FOLDER, 5678L));
        List<RequestServerModelInfo.Info> auth = new ArrayList<>();
        auth.add(new RequestServerModelInfo.Info("secret.ysm", Type.YSM, 42L));

        ByteBuf buf = Unpooled.buffer();
        new RequestServerModelInfo(custom, auth).toBytes(buf);
        RequestServerModelInfo decoded = new RequestServerModelInfo();
        decoded.fromBytes(buf);

        assertNotNull(decoded.getCustomModels());
        assertNotNull(decoded.getAuthModels());
        assertEquals(2, decoded.getCustomModels()
            .size());
        assertEquals("model.ysm", decoded.getCustomModels()
            .get(0)
            .getFileName());
        assertEquals(Type.YSM, decoded.getCustomModels()
            .get(0)
            .getType());
        assertEquals(1234L, decoded.getCustomModels()
            .get(0)
            .getSize());
        assertEquals(1, decoded.getAuthModels()
            .size());
        assertEquals("secret.ysm", decoded.getAuthModels()
            .get(0)
            .getFileName());
    }

    @Test
    void aTruncatedListPacketDecodesToNullListsInsteadOfThrowing() {
        RequestServerModelInfo decoded = new RequestServerModelInfo();
        decoded.fromBytes(Unpooled.buffer());

        assertNull(decoded.getCustomModels(), "the handler must be able to tell 'malformed, ignore me'");
        assertNull(decoded.getAuthModels());
    }

    @Test
    void aListPacketWithALyingEntryCountIsDropped() {
        ByteBuf lying = Unpooled.buffer();
        lying.writeInt(1_000_000);
        RequestServerModelInfo decoded = new RequestServerModelInfo();
        decoded.fromBytes(lying);

        assertNull(decoded.getCustomModels(), "an entry count above the cap must not be trusted");
    }

    @Test
    void theFileActionPacketRoundTripsEveryField() {
        ByteBuf buf = Unpooled.buffer();
        new HandleFile(ModelUploadTarget.AUTH, "model.ysm", HandleFile.Action.RENAME, "renamed.ysm").toBytes(buf);

        HandleFile decoded = new HandleFile();
        decoded.fromBytes(buf);

        assertEquals(ModelUploadTarget.AUTH.wireId(), decoded.getTargetWireId());
        assertEquals("model.ysm", decoded.getName());
        assertEquals(HandleFile.Action.RENAME, decoded.getAction());
        assertEquals("renamed.ysm", decoded.getRename());
    }

    @Test
    void aTruncatedFileActionPacketIsMarkedMalformed() {
        HandleFile decoded = new HandleFile();
        decoded.fromBytes(Unpooled.buffer());

        assertNull(decoded.getAction(), "a truncated packet must not carry an action");
    }

    @Test
    void anUnknownFileActionIsDropped() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(ModelUploadTarget.CUSTOM.wireId());
        buf.writeInt(0); // empty name
        buf.writeByte(99); // unknown action wire id
        buf.writeInt(0);

        HandleFile decoded = new HandleFile();
        decoded.fromBytes(buf);

        assertNull(decoded.getAction());
    }

    @Test
    void theRefreshPacketHasAnEmptyPayload() {
        ByteBuf buf = Unpooled.buffer();
        new RefreshModelManage().toBytes(buf);

        assertEquals(0, buf.readableBytes(), "an empty payload is part of the wire meaning of id 103");
        RefreshModelManage decoded = new RefreshModelManage();
        decoded.fromBytes(buf);
        assertNotNull(decoded);
    }

    @Test
    void theFileActionWireIdsAreStable() {
        assertEquals(0, HandleFile.Action.DELETE.wireId());
        assertEquals(1, HandleFile.Action.MOVE.wireId());
        assertEquals(2, HandleFile.Action.RENAME.wireId());
        assertEquals(HandleFile.Action.RENAME, HandleFile.Action.fromWireId(2));
        assertNull(HandleFile.Action.fromWireId(-1));
        assertNull(HandleFile.Action.fromWireId(3));
    }

    @Test
    void theHandlersAreRegisteredOnTheExpectedSides() throws Exception {
        assertTrue(
            mentions(HandleFile.Handler.class, "SERVER"),
            "HandleFile must guard on Side.SERVER (it performs file operations)");
        assertTrue(
            mentions(RefreshModelManage.Handler.class, "SERVER"),
            "RefreshModelManage must guard on Side.SERVER");
        assertTrue(
            mentions(RequestServerModelInfo.Handler.class, "CLIENT"),
            "RequestServerModelInfo must guard on Side.CLIENT (it opens a client screen)");
    }

    /**
     * 专用服务端启动回归：FML 会在两侧 {@code newInstance()} 每个注册的 handler，而 1.7.10 的
     * {@code SideTransformer} 拒绝在 SERVER 侧加载 {@code net.minecraft.client.*}。用一个拒绝客户端类的
     * 类加载器重新定义 Handler 并实例化它，模拟"服务端没有客户端类"的环境；若 handler 方法体里又出现客户端
     * 类型（如直接 {@code new ModelManageScreen} 再传给 {@code displayGuiScreen(GuiScreen)}），JVM 校验会去
     * 加载父类链并在这个测试里失败。客户端代码现在关在 {@code RequestServerModelInfo.openScreen} 的
     * {@code @SideOnly(CLIENT)} 方法里，服务端侧它会被 SideTransformer 删除。
     */
    @Test
    void theClientboundHandlerStillInstantiatesWhenClientClassesAreUnavailable() throws Exception {
        Class<?> handler = new ClientClassBlockingLoader(RequestServerModelInfo.Handler.class.getClassLoader())
            .defineHandler("com.fox.ysmu.network.message.RequestServerModelInfo$Handler");
        Object instance = handler.getDeclaredConstructor()
            .newInstance();

        assertNotNull(instance, "FML's registerMessage must be able to instantiate the handler on a server");
    }

    /** 拒绝客户端类、自己定义 Handler 的类加载器（模拟专用服务端里不存在 net.minecraft.client 的类路径）。 */
    private static final class ClientClassBlockingLoader extends ClassLoader {

        private ClientClassBlockingLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> defineHandler(String name) throws Exception {
            byte[] bytes = readBytes(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                if (name.startsWith("net.minecraft.client.") || name.startsWith("com.fox.ysmu.client.")) {
                    throw new ClassNotFoundException(name + " is not available on a dedicated server");
                }
                return super.loadClass(name, resolve);
            }
        }

        private byte[] readBytes(String name) throws Exception {
            String resource = name.replace('.', '/') + ".class";
            try (java.io.InputStream in = getParent().getResourceAsStream(resource)) {
                assertNotNull(in, resource + " must be readable from the test classpath");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
    }

    private static boolean mentions(Class<?> handler, String sideConstant) throws Exception {
        String pool = classConstantPool(handler);
        return pool.contains("cpw/mods/fml/relauncher/Side") && pool.contains(sideConstant);
    }

    private static String classConstantPool(Class<?> type) throws Exception {
        String resource = type.getName()
            .replace('.', '/') + ".class";
        try (java.io.InputStream in = type.getClassLoader()
            .getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " must be readable from the test classpath");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}
