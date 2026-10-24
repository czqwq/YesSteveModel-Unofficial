package com.fox.ysmu.client.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PbrPublicationTest {
    static class Texture implements AutoCloseable {
        int closes;
        @Override public void close() { closes++; }
    }

    @Test void successTransfersBothRolesWithoutClosing() throws Exception {
        Texture normal = new Texture(), specular = new Texture();
        Map<Integer, Texture> textures = new LinkedHashMap<>(); textures.put(1, normal); textures.put(2, specular);
        Map<Integer, Texture> accepted = new LinkedHashMap<>();
        PbrPublication.publish(textures, accepted::put);
        assertSame(normal, accepted.get(1)); assertSame(specular, accepted.get(2));
        assertEquals(0, normal.closes); assertEquals(0, specular.closes);
    }

    @Test void failedSecondAcceptClosesEvenFirstAcceptedBecauseNoHolderWasAdopted() {
        Texture normal = new Texture(), specular = new Texture();
        Map<Integer, Texture> textures = new LinkedHashMap<>(); textures.put(1, normal); textures.put(2, specular);
        Exception failure = assertThrows(Exception.class, () -> PbrPublication.publish(textures, (role, texture) -> {
            if (role == 2) throw new java.lang.reflect.InvocationTargetException(new IllegalStateException("host failure"));
        }));
        assertTrue(failure instanceof java.lang.reflect.InvocationTargetException);
        assertEquals(1, normal.closes); assertEquals(1, specular.closes);
    }

    @Test void noMapsPublishesNothing() throws Exception {
        PbrPublication.publish(Map.<Integer, Texture>of(), (role, texture) -> fail("invented component"));
    }
}
