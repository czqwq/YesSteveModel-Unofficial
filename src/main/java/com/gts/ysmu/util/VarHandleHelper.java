package com.gts.ysmu.util;

import com.gts.ysmu.YesSteveModel;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Optional;

public class VarHandleHelper {
    public static Optional<VarHandle> findField(Class<?> cls, String str, Class<?> cls2) {
        try {
            return Optional.of(MethodHandles.privateLookupIn(cls, MethodHandles.lookup()).findVarHandle(cls, str, cls2));
        } catch (IllegalAccessException | NoSuchFieldException e) {
            YesSteveModel.LOGGER.warn("Failed to find var handle for field '{}' in {}", str, cls.getName(), e);
            return Optional.empty();
        }
    }
}
