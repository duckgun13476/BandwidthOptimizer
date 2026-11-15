package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

/** Builds two exact relative movement packets for an eligible incoming update. */
public final class EntityMovementPacketSplit {
    private static final long TWO_BLOCKS_IN_PACKET_UNITS = 2L * 4096L;
    private static final long MAX_DISTANCE_SQUARED = TWO_BLOCKS_IN_PACKET_UNITS * TWO_BLOCKS_IN_PACKET_UNITS;

    private EntityMovementPacketSplit() {}

    public static Parts split(int entityId, ClientboundMoveEntityPacket packet,
                              byte yaw, byte pitch, boolean previousOnGround) {
        if (packet == null || !packet.hasPosition()) {
            return null;
        }
        int x = packet.getXa();
        int y = packet.getYa();
        int z = packet.getZa();
        long distanceSquared = (long) x * x + (long) y * y + (long) z * z;
        if (distanceSquared == 0 || distanceSquared >= MAX_DISTANCE_SQUARED) {
            return null;
        }
        int firstX = x / 2;
        int firstY = y / 2;
        int firstZ = z / 2;
        if (firstX == 0 && firstY == 0 && firstZ == 0) {
            return null;
        }
        ClientboundMoveEntityPacket first = new ClientboundMoveEntityPacket.Pos(entityId,
                (short) firstX, (short) firstY, (short) firstZ, previousOnGround);
        short lastX = (short) (x - firstX);
        short lastY = (short) (y - firstY);
        short lastZ = (short) (z - firstZ);
        ClientboundMoveEntityPacket last = packet.hasRotation()
                ? new ClientboundMoveEntityPacket.PosRot(entityId, lastX, lastY, lastZ, yaw, pitch, packet.isOnGround())
                : new ClientboundMoveEntityPacket.Pos(entityId, lastX, lastY, lastZ, packet.isOnGround());
        return new Parts(first, last);
    }

    public record Parts(ClientboundMoveEntityPacket first, ClientboundMoveEntityPacket last) {}
}
