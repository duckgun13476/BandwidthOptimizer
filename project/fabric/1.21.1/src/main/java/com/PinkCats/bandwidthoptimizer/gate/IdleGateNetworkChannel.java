package com.PinkCats.bandwidthoptimizer.gate;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;

public final class IdleGateNetworkChannel {

    public static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("idle_state"));
    private static boolean registered;

    private IdleGateNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.playC2S().register(IdleGateStateBytePayload.TYPE, IdleGateStateBytePayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(IdleGateStateBytePayload.TYPE, (payload, context) ->
                context.server().execute(() -> IdleGateServerState.accept(
                        context.player(),
                        IdleGateStatePayload.fromBytes(payload.bytes()))));
        Bandwidthoptimizer.LOGGER.info("[IdleGate] Registered idle state channel {}", CHANNEL_ID);
    }
}
