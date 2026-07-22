package com.PinkCats.bandwidthoptimizer.gate.integration.farm_and_charm;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.gate.recovery.IdleGateRecoveryPolicy;
import io.netty.channel.Channel;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Retains only the newest Farm & Charm saturation HUD update while backgrounded. */
public final class FarmAndCharmSaturationRecoveryPolicy extends IdleGateRecoveryPolicy {

    private static final String ARCHITECTURY_CHANNEL = "architectury:network";
    private static final String SATURATION_CHANNEL = "farm_and_charm:sync_saturation";
    private final ConcurrentHashMap<UUID, PendingPacket> pendingPackets = new ConcurrentHashMap<>();

    @Override
    public boolean tryCapture(Channel channel, Packet<?> packet, PacketSendListener listener) {
        if (channel == null
                || packet == null
                || listener != null
                || !IdleGateServerState.snapshot(channel).mode().suppressesWorldPresentation()
                || !ARCHITECTURY_CHANNEL.equals(CustomPayloadPacketCompat.payloadChannel(packet))
                || !isSaturationPayload(CustomPayloadPacketCompat.payloadBytes(packet))) {
            return false;
        }
        ServerPlayer player = IdleGateServerState.resolvePlayer(channel);
        if (player == null) {
            return false;
        }
        pendingPackets.put(player.getUUID(), new PendingPacket(player, packet));
        return true;
    }

    @Override
    public void restore(ServerPlayer player) {
        if (player == null) {
            return;
        }
        send(pendingPackets.remove(player.getUUID()));
    }

    @Override
    public void onServerTick() {
        for (PendingPacket pending : pendingPackets.values()) {
            if (pending.player() == null
                    || IdleGateServerState.snapshot(pending.player()).mode().suppressesWorldPresentation()
                    || !pendingPackets.remove(pending.player().getUUID(), pending)) {
                continue;
            }
            send(pending);
        }
    }

    @Override
    public void discard(ServerPlayer player) {
        if (player != null) {
            pendingPackets.remove(player.getUUID());
        }
    }

    private static boolean isSaturationPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return false;
        }
        try {
            int[] index = {0};
            int channelLength = readVarInt(payload, index);
            if (channelLength != SATURATION_CHANNEL.length()
                    || index[0] + channelLength > payload.length) {
                return false;
            }
            return SATURATION_CHANNEL.equals(new String(payload, index[0], channelLength, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static int readVarInt(byte[] payload, int[] index) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (index[0] >= payload.length) {
                throw new IllegalArgumentException("truncated varint");
            }
            int next = payload[index[0]++] & 0xFF;
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("oversized varint");
    }

    private static void send(PendingPacket pending) {
        if (pending != null && pending.player() != null) {
            pending.player().connection.send(pending.packet());
        }
    }

    private record PendingPacket(ServerPlayer player, Packet<?> packet) {}
}
