package com.PinkCats.bandwidthoptimizer.platform;

import com.pinkcats.torque.layer.platform.NativeBacked;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class TorqueNative {
    private TorqueNative() {}

    public static ServerPlayer serverPlayer(com.pinkcats.torque.layer.net.minecraft.server.level.ServerPlayer player) {
        Object nativeHandle = nativeHandle(player);
        if (nativeHandle instanceof ServerPlayer serverPlayer) {
            return serverPlayer;
        }
        throw new IllegalArgumentException("Expected native ServerPlayer");
    }

    public static ServerLevel serverLevel(com.pinkcats.torque.layer.net.minecraft.server.level.ServerLevel level) {
        Object nativeHandle = nativeHandle(level);
        if (nativeHandle instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        throw new IllegalArgumentException("Expected native ServerLevel");
    }

    private static Object nativeHandle(Object value) {
        if (value instanceof NativeBacked nativeBacked) {
            return nativeBacked.nativeHandle();
        }
        throw new IllegalArgumentException("Expected NativeBacked wrapper");
    }
}
