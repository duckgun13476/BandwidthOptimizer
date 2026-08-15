package com.PinkCats.bandwidthoptimizer.debug;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ServerBoLogExportChunkPayload(
        String sessionId,
        String fileName,
        int chunkIndex,
        int totalChunks,
        int totalBytes,
        byte[] bytes
) implements CustomPacketPayload {

    static final int MAX_CHUNK_BYTES = 256 * 1024;
    public static final Type<ServerBoLogExportChunkPayload> TYPE =
            new Type<>(ServerBoLogExportNetworkChannel.CHANNEL_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBoLogExportChunkPayload> STREAM_CODEC =
            StreamCodec.of(ServerBoLogExportChunkPayload::encode, ServerBoLogExportChunkPayload::decode);

    public ServerBoLogExportChunkPayload {
        sessionId = sessionId == null ? "" : sessionId;
        fileName = fileName == null ? "server-bo-log.log" : fileName;
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

    private static void encode(RegistryFriendlyByteBuf buffer, ServerBoLogExportChunkPayload payload) {
        buffer.writeUtf(payload.sessionId(), 64);
        buffer.writeUtf(payload.fileName(), 192);
        buffer.writeVarInt(payload.chunkIndex());
        buffer.writeVarInt(payload.totalChunks());
        buffer.writeVarInt(payload.totalBytes());
        buffer.writeByteArray(payload.bytes());
    }

    private static ServerBoLogExportChunkPayload decode(RegistryFriendlyByteBuf buffer) {
        return new ServerBoLogExportChunkPayload(
                buffer.readUtf(64),
                buffer.readUtf(192),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readByteArray(MAX_CHUNK_BYTES)
        );
    }
}
