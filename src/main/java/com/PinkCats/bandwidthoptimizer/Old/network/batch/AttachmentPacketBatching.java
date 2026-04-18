package com.PinkCats.bandwidthoptimizer.Old.network.batch;

import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentBatchPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;

import java.util.List;

public final class AttachmentPacketBatching {

    public static final long WINDOW_MILLIS = PayloadBatching.WINDOW_MILLIS;

    private AttachmentPacketBatching() {
    }

    public static boolean withinWindow(long batchStartTimestamp, long currentTimestamp) {
        return PayloadBatching.withinWindow(batchStartTimestamp, currentTimestamp);
    }

    public static AttachmentPacketBatchCodec.EncodedAttachmentBatch encodeBatch(List<ServerToClientAttachmentPacket> packets) {
        return encodeBatch(packets, new PayloadBatching.Session());
    }

    public static AttachmentPacketBatchCodec.EncodedAttachmentBatch encodeBatch(
            List<ServerToClientAttachmentPacket> packets,
            PayloadBatching.Session session
    ) {
        return AttachmentPacketBatchCodec.encode(packets, session);
    }

    public static List<ServerToClientAttachmentPacket> decodeBatch(String algorithmId, byte[] encodedBytes) {
        return decodeBatch(algorithmId, encodedBytes, new PayloadBatching.Session());
    }

    public static List<ServerToClientAttachmentPacket> decodeBatch(
            String algorithmId,
            byte[] encodedBytes,
            PayloadBatching.Session session
    ) {
        return AttachmentPacketBatchCodec.decode(algorithmId, encodedBytes, session);
    }

    public static ServerToClientAttachmentBatchPacket createBatchPacket(
            AttachmentPacketBatchCodec.EncodedAttachmentBatch encodedBatch,
            boolean resetSession
    ) {
        return new ServerToClientAttachmentBatchPacket(
                encodedBatch.algorithmId(),
                resetSession,
                encodedBatch.bytes(),
                encodedBatch.packetCount()
        );
    }
}
