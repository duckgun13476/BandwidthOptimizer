package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.PayloadBatching;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlayPacketBatchCodec {

    private PlayPacketBatchCodec() {
    }

    public static EncodedPlayPacketBatch encode(List<Packet<?>> packets, PayloadBatching.Session session) {
        List<BatchAlgorithm.BatchInput> entries = new ArrayList<>(packets.size());
        Map<Integer, Integer> localPacketIds = new LinkedHashMap<>();
        for (Packet<?> packet : packets) {
            int packetId = PlayPacketReplaySupport.packetId(packet);
            int localId = localPacketIds.computeIfAbsent(packetId, ignored -> localPacketIds.size());
            entries.add(new BatchAlgorithm.BatchInput(packet.getClass().getName(), encodeMappedPacket(localId, packet)));
        }

        PayloadBatching.EncodedPayloadBatch encodedBatch = PayloadBatching.encodeEntries(entries, session);
        return new EncodedPlayPacketBatch(
                buildPacketIdTable(localPacketIds),
                encodedBatch.algorithmId(),
                encodedBatch.bytes(),
                encodedBatch.entryInfos(),
                encodedBatch.addedMappings(),
                encodedBatch.removedMappings(),
                packets.size()
        );
    }

    public static EncodedPlayPacketBatch encodeInputs(
            int[] packetIdTable,
            List<BatchAlgorithm.BatchInput> entries,
            PayloadBatching.Session session
    ) {
        PayloadBatching.EncodedPayloadBatch encodedBatch = PayloadBatching.encodeEntries(entries, session);
        return new EncodedPlayPacketBatch(
                packetIdTable.clone(),
                encodedBatch.algorithmId(),
                encodedBatch.bytes(),
                encodedBatch.entryInfos(),
                encodedBatch.addedMappings(),
                encodedBatch.removedMappings(),
                entries.size()
        );
    }

    public static List<Packet<ClientGamePacketListener>> decode(int[] packetIdTable, String algorithmId, byte[] encodedBytes, PayloadBatching.Session session) {
        List<byte[]> payloads = PayloadBatching.decodePayloads(algorithmId, encodedBytes, session);
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(payloads.size());
        for (byte[] payload : payloads) {
            packets.add(decodeMappedPacket(packetIdTable, payload));
        }
        return List.copyOf(packets);
    }

    private static byte[] encodeMappedPacket(int localId, Packet<?> packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(localId);
            buffer.writeBytes(PlayPacketReplaySupport.encodePacketBody(packet));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static Packet<ClientGamePacketListener> decodeMappedPacket(int[] packetIdTable, byte[] encodedEntryBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedEntryBytes));
        try {
            int localId = buffer.readVarInt();
            if (localId < 0 || localId >= packetIdTable.length) {
                throw new IllegalStateException("Unknown local play packet id " + localId + " for mapping table size " + packetIdTable.length);
            }
            byte[] bodyBytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bodyBytes);
            return PlayPacketReplaySupport.decodePacket(packetIdTable[localId], bodyBytes);
        } finally {
            buffer.release();
        }
    }

    private static int[] buildPacketIdTable(Map<Integer, Integer> localPacketIds) {
        int[] packetIdTable = new int[localPacketIds.size()];
        for (Map.Entry<Integer, Integer> entry : localPacketIds.entrySet()) {
            packetIdTable[entry.getValue()] = entry.getKey();
        }
        return packetIdTable;
    }

    public record EncodedPlayPacketBatch(
            int[] packetIdTable,
            String algorithmId,
            byte[] bytes,
            List<BatchAlgorithm.EntryInfo> entryInfos,
            int addedMappings,
            int removedMappings,
            int packetCount
    ) {
    }
}
