package com.PinkCats.bandwidthoptimizer.channel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ChannelTransportBytePayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<ChannelTransportBytePayload> TYPE =
            new Type<>(ChannelTransportNetworkChannel.TRANSPORT_PAYLOAD_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, ChannelTransportBytePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeByteArray(payload.bytes()),
                    buffer -> new ChannelTransportBytePayload(buffer.readByteArray())
            );

    public ChannelTransportBytePayload {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
