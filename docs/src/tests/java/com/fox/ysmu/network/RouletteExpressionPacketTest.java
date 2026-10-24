package com.fox.ysmu.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.network.message.C2SSubmitRouletteExpression;
import com.fox.ysmu.network.message.S2CExecuteRouletteExpression;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class RouletteExpressionPacketTest {
    private static final net.minecraft.util.ResourceLocation MODEL =
        new net.minecraft.util.ResourceLocation("ysmu", "form_model");
    @Test
    void selfRequestCarriesExpressionNotClientChosenEntity() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            String program = "v.roaming.a=1;v.other=v.roaming.a*2;";
            new C2SSubmitRouletteExpression(MODEL, program).toBytes(bytes);
            C2SSubmitRouletteExpression decoded = new C2SSubmitRouletteExpression();
            decoded.fromBytes(bytes);
            assertEquals(program, decoded.expression());
            assertEquals(MODEL, decoded.modelId());
            assertEquals(0, bytes.readableBytes());
        } finally { bytes.release(); }
    }

    @Test
    void serverEventCarriesAuthorizedEntityAndUtf8Program() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            String program = "v.roaming.a=1;play_sound('声音','sound',0,1,1);";
            new S2CExecuteRouletteExpression(17, MODEL, program).toBytes(bytes);
            S2CExecuteRouletteExpression decoded = new S2CExecuteRouletteExpression();
            decoded.fromBytes(bytes);
            assertEquals(17, decoded.entityId());
            assertEquals(program, decoded.expression());
            assertEquals(MODEL, decoded.modelId());
        } finally { bytes.release(); }
    }

    @Test
    void aModelChangeBetweenClickAndDispatchRejectsTheExpression() {
        net.minecraft.util.ResourceLocation replacement = new net.minecraft.util.ResourceLocation("ysmu", "other");
        org.junit.jupiter.api.Assertions.assertTrue(C2SSubmitRouletteExpression.matchesModel(MODEL, MODEL));
        assertFalse(C2SSubmitRouletteExpression.matchesModel(MODEL, replacement));
        assertFalse(C2SSubmitRouletteExpression.matchesModel(null, MODEL));
    }

    @Test
    void truncatedAndOversizedProgramsAreNotAccepted() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            bytes.writeInt(Integer.MAX_VALUE);
            C2SSubmitRouletteExpression decoded = new C2SSubmitRouletteExpression();
            decoded.fromBytes(bytes);
            assertNull(decoded.expression());
            assertFalse(C2SSubmitRouletteExpression.valid("a".repeat(4097)));
            assertFalse(C2SSubmitRouletteExpression.valid(" "));
        } finally { bytes.release(); }
    }
}
