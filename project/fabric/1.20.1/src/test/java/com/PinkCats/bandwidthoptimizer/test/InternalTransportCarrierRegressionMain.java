package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportNetworkChannel;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;

public final class InternalTransportCarrierRegressionMain {

    private InternalTransportCarrierRegressionMain() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        FriendlyByteBuf clientboundData = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        clientboundData.writeByte(1);
        FriendlyByteBuf serverboundData = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        serverboundData.writeByte(2);
        ClientboundCustomPayloadPacket clientbound = new ClientboundCustomPayloadPacket(
                ChannelTransportNetworkChannel.TRANSPORT_PAYLOAD_ID,
                clientboundData
        );
        ServerboundCustomPayloadPacket serverbound = new ServerboundCustomPayloadPacket(
                ChannelTransportNetworkChannel.TRANSPORT_PAYLOAD_ID,
                serverboundData
        );
        try {
            require(ChannelTransportHooks.isInternalTransportCarrierPacket(clientbound), "clientbound carrier");
            require(ChannelTransportHooks.isInternalTransportCarrierPacket(serverbound), "serverbound carrier");
        } finally {
            clientbound.getData().release();
            serverbound.getData().release();
        }
        System.out.println("Internal transport carrier isolation passed.");
    }

    private static void require(boolean value, String direction) {
        if (!value) {
            throw new IllegalStateException("Internal transport carrier was not recognized: " + direction);
        }
    }
}
