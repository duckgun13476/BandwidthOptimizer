package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundSectionBlocksUpdatePacketAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class IdleGateForegroundViewPolicy {

    private static final double ALWAYS_SEND_DISTANCE_SQR = 8.0D * 8.0D;
    private static final double LOOK_DOT_THRESHOLD = 0.35D;
    private static final double LOOK_DOT_THRESHOLD_SQR = LOOK_DOT_THRESHOLD * LOOK_DOT_THRESHOLD;

    private IdleGateForegroundViewPolicy() {}

    public static boolean shouldDefer(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return false;
        }
        return !isImmediatelyVisible(capture(player), pos);
    }

    public static boolean shouldDefer(ServerPlayer player, ClientboundSectionBlocksUpdatePacket packet) {
        if (player == null || packet == null) {
            return false;
        }
        ClientboundSectionBlocksUpdatePacketAccessor accessor =
                (ClientboundSectionBlocksUpdatePacketAccessor) packet;
        SectionPos sectionPos = accessor.bandwidthoptimizer$getSectionPos();
        short[] positions = accessor.bandwidthoptimizer$getPositions();
        if (sectionPos == null || positions == null || positions.length == 0) {
            return false;
        }
        ViewState view = capture(player);
        for (short relativePosition : positions) {
            if (isImmediatelyVisible(view, sectionPos.relativeToBlockPos(relativePosition))) {
                return false;
            }
        }
        return true;
    }

    private static ViewState capture(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        return new ViewState(
                player.getEyePosition(),
                look == null ? Vec3.ZERO : look.normalize());
    }

    private static boolean isImmediatelyVisible(ViewState view, BlockPos pos) {
        double dx = pos.getX() + 0.5D - view.eye().x;
        double dy = pos.getY() + 0.5D - view.eye().y;
        double dz = pos.getZ() + 0.5D - view.eye().z;
        double distanceSqr = dx * dx + dy * dy + dz * dz;
        if (distanceSqr <= ALWAYS_SEND_DISTANCE_SQR) {
            return true;
        }
        double projected = view.look().x * dx + view.look().y * dy + view.look().z * dz;
        return projected > 0.0D && projected * projected >= LOOK_DOT_THRESHOLD_SQR * distanceSqr;
    }

    private record ViewState(Vec3 eye, Vec3 look) {}
}
