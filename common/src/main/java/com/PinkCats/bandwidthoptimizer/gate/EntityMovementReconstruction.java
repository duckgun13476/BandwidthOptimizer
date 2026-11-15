package com.PinkCats.bandwidthoptimizer.gate;

import com.PinkCats.bandwidthoptimizer.gate.integration.minecraft.EntityMovementPacketSplit;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.EntityMotionPacketCompat;
import com.PinkCats.bandwidthoptimizer.integration.sable.SableEntityGateCompat;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundMoveEntityPacketAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.EntityGroundStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.world.entity.Entity;

/** Experimental client-only reconstruction; all calls run on the client thread. */
public final class EntityMovementReconstruction {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("bandwidthoptimizer.experimentalMovementReconstruction", "true"));
    private static final int DELAY_TICKS = Math.max(1, Math.min(2,
            Integer.getInteger("bandwidthoptimizer.experimentalMovementDelayTicks", 1)));
    private static final int MAX_PENDING = 2048;
    private static final PendingEntityMovements<Entity, ClientboundMoveEntityPacket> PENDING =
            new PendingEntityMovements<>(MAX_PENDING);
    private static ClientPacketListener connection;
    private static ClientLevel level;
    private static long ticks;
    private static boolean applying;

    private EntityMovementReconstruction() {}

    public static boolean onMovement(ClientPacketListener listener, ClientboundMoveEntityPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ENABLED || applying || !minecraft.isSameThread() || minecraft.level == null
                || minecraft.getConnection() != listener) {
            return false;
        }
        bind(listener, minecraft.level);
        int entityId = ((ClientboundMoveEntityPacketAccessor) packet).bandwidthoptimizer$getEntityId();
        flush(entityId);
        Entity entity = packet.getEntity(minecraft.level);
        if (entity == null || EntityMotionPacketCompat.isLocallyControlled(entity) || PENDING.isFull()
                || SableEntityGateCompat.shouldPassThrough(entity)) {
            return false;
        }
        ClientboundMoveEntityPacketAccessor accessor = (ClientboundMoveEntityPacketAccessor) packet;
        var parts = EntityMovementPacketSplit.split(entityId, packet,
                accessor.bandwidthoptimizer$getYRotByte(), accessor.bandwidthoptimizer$getXRotByte(),
                ((EntityGroundStateAccessor) entity).bandwidthoptimizer$getOnGround());
        if (parts == null) {
            return false;
        }
        apply(listener, parts.first());
        PENDING.put(entityId, entity, parts.last(), ticks + DELAY_TICKS);
        return true;
    }

    public static void discard(ClientPacketListener listener, int entityId) {
        if (!ENABLED || !Minecraft.getInstance().isSameThread() || listener != connection) {
            return;
        }
        PENDING.take(entityId);
    }

    public static void tick() {
        if (!ENABLED) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getConnection() == null) {
            PENDING.clear();
            level = null;
            connection = null;
            return;
        }
        bind(minecraft.getConnection(), minecraft.level);
        ticks++;
        PENDING.drainDue(ticks, (id, pending) -> {
            if (level.getEntity(id) == pending.entity()) {
                apply(connection, pending.packet());
            }
        });
    }

    private static void bind(ClientPacketListener nextConnection, ClientLevel nextLevel) {
        if (connection != nextConnection || level != nextLevel) {
            PENDING.clear();
            connection = nextConnection;
            level = nextLevel;
            ticks = 0;
        }
    }

    private static void flush(int entityId) {
        PendingEntityMovements.Entry<Entity, ClientboundMoveEntityPacket> pending = PENDING.take(entityId);
        if (pending != null && level.getEntity(entityId) == pending.entity()) {
            apply(connection, pending.packet());
        }
    }

    private static void apply(ClientPacketListener listener, ClientboundMoveEntityPacket packet) {
        applying = true;
        try {
            listener.handleMoveEntity(packet);
        } finally {
            applying = false;
        }
    }

}
