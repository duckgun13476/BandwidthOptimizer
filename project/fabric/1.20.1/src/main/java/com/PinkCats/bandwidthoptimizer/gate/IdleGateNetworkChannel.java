package com.PinkCats.bandwidthoptimizer.gate;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;

public final class IdleGateNetworkChannel {

    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(Bandwidthoptimizer.MODID, "idle_state");
    private static boolean registered;

    private IdleGateNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL_ID, (server, player, handler, buffer, responseSender) -> {
            byte[] bytes = buffer.readByteArray(64);
            server.execute(() -> IdleGateServerState.accept(player, IdleGateStatePayload.fromBytes(bytes)));
        });
        Bandwidthoptimizer.LOGGER.info("[IdleGate] Registered idle state channel {}", CHANNEL_ID);
    }
}
