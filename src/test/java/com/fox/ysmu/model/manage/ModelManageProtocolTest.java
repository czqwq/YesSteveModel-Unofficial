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
