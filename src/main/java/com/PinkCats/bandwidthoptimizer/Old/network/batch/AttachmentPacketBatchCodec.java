package com.PinkCats.bandwidthoptimizer.Old.network.batch;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public final class AttachmentPacketBatchCodec {

    private AttachmentPacketBatchCodec() {
    }

    public static EncodedAttachmentBatch encode(List<ServerToClientAttachmentPacket> packets) {
        return encode(packets, new PayloadBatching.Session());
    }

    public static EncodedAttachmentBatch encode(List<ServerToClientAttachmentPacket> packets, PayloadBatching.Session session) {
        List<BatchAlgorithm.BatchInput> entries = new ArrayList<>(packets.size());
        for (ServerToClientAttachmentPacket packet : packets) {
            entries.add(new BatchAlgorithm.BatchInput(packet.key(), encodePacket(packet)));
        }

        PayloadBatching.EncodedPayloadBatch encodedBatch = PayloadBatching.encodeEntries(entries, session);
        return new EncodedAttachmentBatch(
                encodedBatch.algorithmId(),
                encodedBatch.bytes(),
                encodedBatch.entryInfos(),
                encodedBatch.addedMappings(),
                encodedBatch.removedMappings(),
                packets.size()
        );
    }

    public static List<ServerToClientAttachmentPacket> decode(String algorithmId, byte[] encodedBytes) {
        return decode(algorithmId, encodedBytes, new PayloadBatching.Session());
    }

    public static List<ServerToClientAttachmentPacket> decode(String algorithmId, byte[] encodedBytes, PayloadBatching.Session session) {
        List<byte[]> payloads = PayloadBatching.decodePayloads(algorithmId, encodedBytes, session);
        List<ServerToClientAttachmentPacket> packets = new ArrayList<>(payloads.size());
        for (byte[] payload : payloads) {
            packets.add(decodePacket(payload));
        }
        return List.copyOf(packets);
    }

    public static byte[] encodePacket(ServerToClientAttachmentPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerToClientAttachmentPacket.encode(packet, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public static ServerToClientAttachmentPacket decodePacket(byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return ServerToClientAttachmentPacket.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    public record EncodedAttachmentBatch(
            String algorithmId,
            byte[] bytes,
            List<BatchAlgorithm.EntryInfo> entryInfos,
            int addedMappings,
            int removedMappings,
            int packetCount
    ) {
    }
}
