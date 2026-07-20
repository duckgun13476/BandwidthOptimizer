package com.PinkCats.bandwidthoptimizer.idle;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record IdleGateStateBytePayload(byte[] bytes) implements CustomPacketPayload {

    public static final Type<IdleGateStateBytePayload> TYPE =
            new Type<>(IdleGateNetworkChannel.CHANNEL_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, IdleGateStateBytePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeByteArray(payload.bytes()),
                    buffer -> new IdleGateStateBytePayload(buffer.readByteArray(64))
            );

    public IdleGateStateBytePayload {
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
