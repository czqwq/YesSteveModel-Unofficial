package com.fox.ysmu.model.upload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.network.message.C2SUploadModelFile;
import com.fox.ysmu.network.message.S2CRequestModelUpload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The two phase55 upload packets: wire round trips, the N-10 soft-fail rules, the 32 KB chunk ceiling and the
 * handler directions.
 *
 * <p>The handlers themselves touch a client/server connection (the server one resolves an {@code EntityPlayerMP}), so
 * they are pinned by direction only - the same technique {@code YsmCommandTest} uses for the clientbound
 * command packets: a handler that never references the {@code Side} enum cannot carry the receiving-side guard that
 * keeps a mis-delivered packet from running.</p>
 */
class ModelUploadProtocolTest {

    @Test
    void theRequestPacketRoundTripsItsTargetAndPath() {
        ByteBuf buf = Unpooled.buffer();
        new S2CRequestModelUpload(ModelUploadTarget.AUTH.wireId(), "/home/user/我的 模型.ysm").toBytes(buf);

        S2CRequestModelUpload decoded = new S2CRequestModelUpload();
        decoded.fromBytes(buf);

        assertEquals(ModelUploadTarget.AUTH.wireId(), decoded.getTargetWireId());
        assertEquals("/home/user/我的 模型.ysm", decoded.getPath());
    }

    @Test
    void aTruncatedRequestPacketIsDropped() {
        S2CRequestModelUpload decoded = new S2CRequestModelUpload();
        decoded.fromBytes(Unpooled.buffer());

        assertEquals(-1, decoded.getTargetWireId(), "a truncated packet must decode as 'ignore me'");
        assertEquals("", decoded.getPath());
    }

    @Test
    void aRequestPacketWithALyingLengthIsDropped() {
        ByteBuf lying = Unpooled.buffer();
        lying.writeByte(ModelUploadTarget.CUSTOM.wireId());
        lying.writeInt(10_000);
        lying.writeByte('x');

        S2CRequestModelUpload decoded = new S2CRequestModelUpload();
        decoded.fromBytes(lying);

        assertEquals(ModelUploadTarget.CUSTOM.wireId(), decoded.getTargetWireId());
        assertEquals("", decoded.getPath());
    }

    @Test
    void theChunkPacketRoundTripsEveryField() {
        byte[] data = new byte[] { 1, 2, 3, 4, 5 };
        ByteBuf buf = Unpooled.buffer();
        new C2SUploadModelFile(ModelUploadTarget.CUSTOM, "model.ysm", 5, 0, data).toBytes(buf);

        C2SUploadModelFile decoded = new C2SUploadModelFile();
        decoded.fromBytes(buf);

        assertEquals(ModelUploadTarget.CUSTOM.wireId(), decoded.getTargetWireId());
        assertEquals("model.ysm", decoded.getFileName());
        assertEquals(5, decoded.getTotalLength());
        assertEquals(0, decoded.getOffset());
        assertArrayEquals(data, decoded.getData());
    }

    @Test
    void aTruncatedChunkPacketDecodesAsMalformedInsteadOfThrowing() {
        C2SUploadModelFile decoded = new C2SUploadModelFile();
        decoded.fromBytes(Unpooled.buffer());

        assertNull(decoded.getData(), "a decoder that throws would be turned into a disconnect by FML");
    }

    @Test
    void aChunkPacketWithAnImpossibleLengthDecodesAsMalformed() {
        ByteBuf lying = Unpooled.buffer();
        lying.writeByte(ModelUploadTarget.CUSTOM.wireId());
        lying.writeInt(0); // empty file name
        lying.writeInt(5);
        lying.writeInt(0);
        lying.writeInt(3);
        lying.writeByte(1);
        lying.writeByte(2);

        C2SUploadModelFile decoded = new C2SUploadModelFile();
        decoded.fromBytes(lying);

        assertNull(decoded.getData(), "a declared length above readableBytes must be dropped, not allocated");
    }

    @Test
    void aFullChunkFitsBelowTheVanillaCustomPayloadLimit() {
        // C17PacketCustomPayload rejects payloads of 32767 bytes or more; the chunk must leave room for the fields.
        ByteBuf buf = Unpooled.buffer();
        byte[] maximum = new byte[C2SUploadModelFile.MAX_CHUNK_BYTES];
        new C2SUploadModelFile(ModelUploadTarget.AUTH, "a".repeat(200), maximum.length, 0, maximum).toBytes(buf);

        assertTrue(
            buf.readableBytes() < 32767,
            "a full chunk plus its fields must stay below the 1.7.10 custom-payload ceiling: " + buf.readableBytes());
        assertTrue(buf.readableBytes() > maximum.length);
    }

    @Test
    void anOversizedChunkIsRefusedOnTheSendingSide() {
        ByteBuf buf = Unpooled.buffer();
        byte[] tooLarge = new byte[C2SUploadModelFile.MAX_CHUNK_BYTES + 1];

        assertThrows(
            IllegalArgumentException.class,
            () -> new C2SUploadModelFile(ModelUploadTarget.CUSTOM, "model.ysm", tooLarge.length, 0, tooLarge)
                .toBytes(buf));
    }

    @Test
    void anOutOfRangeChunkIsRefusedOnTheSendingSide() {
        ByteBuf buf = Unpooled.buffer();

        assertThrows(
            IllegalArgumentException.class,
            () -> new C2SUploadModelFile(ModelUploadTarget.CUSTOM, "model.ysm", 4, 2, new byte[4]).toBytes(buf));
    }

    @Test
    void targetWireIdsAreStableAndUnknownIdsAreRejected() {
        // The wire ids are part of the protocol; renumbering them would silently write into the wrong directory.
        assertEquals(0, ModelUploadTarget.CUSTOM.wireId());
        assertEquals(1, ModelUploadTarget.AUTH.wireId());
        assertEquals(ModelUploadTarget.CUSTOM, ModelUploadTarget.fromWireId(0));
        assertEquals(ModelUploadTarget.AUTH, ModelUploadTarget.fromWireId(1));
        assertNull(ModelUploadTarget.fromWireId(2));
        assertNull(ModelUploadTarget.fromWireId(-1));
        assertEquals(ModelUploadTarget.AUTH, ModelUploadTarget.fromCommandWord("AUTH"));
        assertEquals(ModelUploadTarget.CUSTOM, ModelUploadTarget.fromCommandWord("custom"));
        assertNull(ModelUploadTarget.fromCommandWord("builtin"));
    }

    @Test
    void theChunkHandlerIsServerboundAndTheRequestHandlerIsClientbound() throws Exception {
        assertTrue(
            mentionsSideServer(C2SUploadModelFile.Handler.class),
            "the upload chunk handler must guard on Side.SERVER");
        assertTrue(
            mentionsSideClient(S2CRequestModelUpload.Handler.class),
            "the upload request handler must guard on Side.CLIENT");
    }

    private static boolean mentionsSideServer(Class<?> handler) throws Exception {
        String pool = classConstantPool(handler);
        return pool.contains("cpw/mods/fml/relauncher/Side") && pool.contains("SERVER");
    }

    private static boolean mentionsSideClient(Class<?> handler) throws Exception {
        String pool = classConstantPool(handler);
        return pool.contains("cpw/mods/fml/relauncher/Side") && pool.contains("CLIENT");
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
