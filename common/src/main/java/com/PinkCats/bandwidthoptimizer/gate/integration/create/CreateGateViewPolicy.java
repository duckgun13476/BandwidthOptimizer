package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import com.PinkCats.bandwidthoptimizer.Config;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class CreateGateViewPolicy {

    private CreateGateViewPolicy() {}

    static boolean shouldSendImmediately(ServerPlayer player, Vec3[] points, boolean allowLookDirection) {
        if (player == null || points == null || points.length == 0) {
            return true;
        }
        return shouldSendImmediately(capture(player), points, allowLookDirection);
    }

    static ViewState capture(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        Vec3 lookAngle = player.getLookAngle();
        return new ViewState(
                player.getEyePosition(),
                lookAngle == null ? Vec3.ZERO : lookAngle.normalize(),
                alwaysSendDistanceBlocks(),
                lookDotThreshold());
    }

    static boolean shouldSendImmediately(ViewState viewState, Vec3[] points, boolean allowLookDirection) {
        if (viewState == null || points == null || points.length == 0) {
            return true;
        }
        return isAnyPointInImmediateView(
                viewState.eyePosition(),
                viewState.normalizedLook(),
                points,
                viewState.nearDistance(),
                allowLookDirection,
                viewState.dotThreshold());
    }

    private static boolean isAnyPointInImmediateView(
            Vec3 eyePosition,
            Vec3 lookAngle,
            Vec3[] points,
            double nearDistance,
            boolean allowLookDirection,
            double dotThreshold
    ) {
        if (eyePosition == null || points == null || points.length == 0) {
            return true;
        }
        Vec3 normalizedLook = lookAngle == null ? Vec3.ZERO : lookAngle;
        double nearDistanceSqr = nearDistance * nearDistance;
        boolean hadPoint = false;
        for (Vec3 point : points) {
            if (point == null) {
                continue;
            }
            hadPoint = true;
            Vec3 offset = point.subtract(eyePosition);
            double distanceSqr = offset.lengthSqr();
            if (distanceSqr <= nearDistanceSqr) {
                return true;
            }
            if (!allowLookDirection) {
                continue;
            }
            double length = Math.sqrt(distanceSqr);
            if (length <= 0.0001D) {
                return true;
            }
            double dot = normalizedLook.dot(offset.scale(1.0D / length));
            if (dot >= dotThreshold) {
                return true;
            }
        }
        return !hadPoint;
    }

    private static double alwaysSendDistanceBlocks() {
        return readDouble(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_ALWAYS_SEND_DISTANCE_BLOCKS,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_ALWAYS_SEND_DISTANCE_BLOCKS,
                0.0D,
                128.0D);
    }

    private static double lookDotThreshold() {
        return readDouble(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_LOOK_DOT,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_LOOK_DOT,
                -1.0D,
                1.0D);
    }

    private static double readDouble(String propertyName, double defaultValue, double minValue, double maxValue) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(rawValue.trim());
            return Math.max(minValue, Math.min(maxValue, parsed));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    record ViewState(Vec3 eyePosition, Vec3 normalizedLook, double nearDistance, double dotThreshold) {}
}
