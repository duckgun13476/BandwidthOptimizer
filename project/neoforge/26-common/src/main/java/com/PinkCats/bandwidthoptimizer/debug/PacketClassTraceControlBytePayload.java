package com.PinkCats.bandwidthoptimizer.debug;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PacketClassTraceControlBytePayload(byte[] bytes) implements CustomPacketPayload {

    public static final Type<PacketClassTraceControlBytePayload> TYPE =
            new Type<>(PacketClassTraceNetworkChannel.CHANNEL_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, PacketClassTraceControlBytePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeByteArray(payload.bytes()),
                    buffer -> new PacketClassTraceControlBytePayload(buffer.readByteArray(32 * 1024))
            );

    public PacketClassTraceControlBytePayload {
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
