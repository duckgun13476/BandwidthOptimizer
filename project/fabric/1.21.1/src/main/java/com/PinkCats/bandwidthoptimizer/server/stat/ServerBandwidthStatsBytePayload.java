package com.PinkCats.bandwidthoptimizer.server.stat;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ServerBandwidthStatsBytePayload(byte[] bytes) implements CustomPacketPayload {

    public static final Type<ServerBandwidthStatsBytePayload> TYPE =
            new Type<>(ServerBandwidthStatsNetworkChannel.CHANNEL_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBandwidthStatsBytePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeByteArray(payload.bytes()),
                    buffer -> new ServerBandwidthStatsBytePayload(buffer.readByteArray())
            );

    public ServerBandwidthStatsBytePayload {
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
