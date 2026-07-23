package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;

public final class EntityMotionPacketCompat {
    private EntityMotionPacketCompat() {
    }

    public static int entityId(ClientboundSetEntityMotionPacket packet) {
        return packet.getId();
    }

    public static ClientboundTeleportEntityPacket teleport(Entity entity) {
        return new ClientboundTeleportEntityPacket(entity);
    }
}
