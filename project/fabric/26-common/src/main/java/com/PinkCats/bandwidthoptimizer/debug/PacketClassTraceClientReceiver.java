package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class PacketClassTraceClientReceiver {

    private static boolean registered;

    private PacketClassTraceClientReceiver() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(
                PacketClassTraceControlBytePayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().getConnection() != null) {
                        PacketClassTraceControlService.acceptClient(
                                ((ConnectionAccessor) context.client().getConnection().getConnection())
                                        .bandwidthoptimizer$getChannel(),
                                payload.bytes()
                        );
                    }
                })
        );
    }
}
