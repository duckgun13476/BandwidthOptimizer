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

    // 复制统计字节，避免发送后外部数组被修改影响网络 payload 内容。
    public ServerBandwidthStatsBytePayload {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    // 返回统计字节副本，避免调用方直接修改 payload 内部数组。
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    // 告诉 NeoForge 这个 payload 对应的网络 channel id。
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
