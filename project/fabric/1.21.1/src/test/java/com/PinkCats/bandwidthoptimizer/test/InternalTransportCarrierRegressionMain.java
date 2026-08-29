package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportBytePayload;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

public final class InternalTransportCarrierRegressionMain {

    private InternalTransportCarrierRegressionMain() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        require(
                ChannelTransportHooks.isInternalTransportCarrierPacket(
                        new ClientboundCustomPayloadPacket(new ChannelTransportBytePayload(new byte[]{1}))
                ),
                "clientbound carrier"
        );
        require(
                ChannelTransportHooks.isInternalTransportCarrierPacket(
                        new ServerboundCustomPayloadPacket(new ChannelTransportBytePayload(new byte[]{2}))
                ),
                "serverbound carrier"
        );
        System.out.println("Internal transport carrier isolation passed.");
    }

    private static void require(boolean value, String direction) {
        if (!value) {
            throw new IllegalStateException("Internal transport carrier was not recognized: " + direction);
        }
    }
}
