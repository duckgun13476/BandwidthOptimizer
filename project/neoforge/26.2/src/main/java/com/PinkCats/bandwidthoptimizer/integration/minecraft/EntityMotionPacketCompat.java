package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;

import java.util.Set;

public final class EntityMotionPacketCompat {
    private EntityMotionPacketCompat() {
    }

    public static int entityId(ClientboundSetEntityMotionPacket packet) {
        return packet.id();
    }

    public static ClientboundTeleportEntityPacket teleport(Entity entity) {
        return ClientboundTeleportEntityPacket.teleport(
                entity.getId(), PositionMoveRotation.of(entity), Set.of(), entity.onGround());
    }
}
