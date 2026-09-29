package com.fox.ysmu.util;

import java.io.*;

import javax.annotation.Nullable;

import com.fox.ysmu.ysmu;

public final class ObjectStreamUtil {

    public static byte[] toByteArray(Object object) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(stream)) {
            output.writeObject(object);
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to serialize {} with ObjectOutputStream", object, e);
        }
        return stream.toByteArray();
    }

    @Nullable
    public static Object toObject(byte[] data) {
        ByteArrayInputStream stream = new ByteArrayInputStream(data);
        try (ObjectInputStream input = new ObjectInputStream(stream)) {
            return input.readObject();
        } catch (ClassNotFoundException | IOException e) {
            ysmu.LOG.warn("Failed to deserialize an ObjectInputStream payload of {} byte(s)", data.length, e);
        }
        return null;
    }
}
