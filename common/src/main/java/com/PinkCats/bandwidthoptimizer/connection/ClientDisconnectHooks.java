package com.PinkCats.bandwidthoptimizer.connection;

import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

public final class ClientDisconnectHooks {

    private ClientDisconnectHooks() {}

    public static void markCurrentConnectionLocal() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener packetListener = minecraft == null ? null : minecraft.getConnection();
        if (packetListener == null || packetListener.getConnection() == null) {
            return;
        }
        ConnectionDisconnectClassifier.markLocalDisconnect(
                ((ConnectionAccessor) packetListener.getConnection()).bandwidthoptimizer$getChannel()
        );
    }
}
