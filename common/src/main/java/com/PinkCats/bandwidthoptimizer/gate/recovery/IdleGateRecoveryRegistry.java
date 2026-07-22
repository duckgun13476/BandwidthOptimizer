package com.PinkCats.bandwidthoptimizer.gate.recovery;

import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateGeneralBlockEntityRecoveryPolicy;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateTransferBlockEntityRecoveryPolicy;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateWorkerBlockEntityRecoveryPolicy;
import com.PinkCats.bandwidthoptimizer.gate.integration.farm_and_charm.FarmAndCharmSaturationRecoveryPolicy;
import io.netty.channel.Channel;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IdleGateRecoveryRegistry {

    private static final List<IdleGateRecoveryPolicy> POLICIES = List.of(
            new FarmAndCharmSaturationRecoveryPolicy(),
            new CreateTransferBlockEntityRecoveryPolicy(),
            new CreateWorkerBlockEntityRecoveryPolicy(),
            new CreateGeneralBlockEntityRecoveryPolicy()
    );
    private static final ConcurrentHashMap<UUID, ServerPlayer> PENDING_RESTORES = new ConcurrentHashMap<>();

    private IdleGateRecoveryRegistry() {}

    public static boolean tryCapture(Channel channel, Packet<?> packet, PacketSendListener listener) {
        for (IdleGateRecoveryPolicy policy : POLICIES) {
            if (policy.tryCapture(channel, packet, listener)) {
                return true;
            }
        }
        return false;
    }

    public static void requestRestore(ServerPlayer player) {
        if (player != null) {
            PENDING_RESTORES.put(player.getUUID(), player);
        }
    }

    public static void onServerTick() {
        for (ServerPlayer player : PENDING_RESTORES.values()) {
            if (player == null || !PENDING_RESTORES.remove(player.getUUID(), player)) {
                continue;
            }
            for (IdleGateRecoveryPolicy policy : POLICIES) {
                policy.restore(player);
            }
        }
        for (IdleGateRecoveryPolicy policy : POLICIES) {
            policy.onServerTick();
        }
    }

    public static void discard(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PENDING_RESTORES.remove(player.getUUID());
        for (IdleGateRecoveryPolicy policy : POLICIES) {
            policy.discard(player);
        }
    }
}
