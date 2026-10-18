package com.PinkCats.bandwidthoptimizer.channel.algorithm.header;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ExactEncoding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KineticPacketIdMappingLayer {

    // Layer for packet header。
    public EncodedPacketIdBatch encodeBatchPacketHeaders(List<byte[]> originalPacketBytesList) {
        List<byte[]> safePacketBytesList = copyPacketBytesList(originalPacketBytesList);
        BatchHeaderPlan plan = planBorrowed(safePacketBytesList);
        List<byte[]> entries = new ArrayList<>(safePacketBytesList.size());
        for (int index = 0; index < safePacketBytesList.size(); index++) {
            FriendlyByteBuf buffer = ExactEncoding.buffer(plan.entryLength(index));
            try {
                plan.writeEntry(buffer, index);
                entries.add(ExactEncoding.finish(buffer));
            } finally {
                buffer.release();
            }
        }
        return new EncodedPacketIdBatch(plan.packetIdTable, entries);
    }

    /** Synchronous read-only borrow. Neither plan nor its inputs may escape the encode call. */
    public BatchHeaderPlan planBorrowed(List<byte[]> safePacketBytesList) {
        Map<Integer, Integer> localPacketIds = new LinkedHashMap<>();
        int[] offsets = new int[safePacketBytesList.size()];
        int[] ids = new int[safePacketBytesList.size()];
        for (int index = 0; index < safePacketBytesList.size(); index++) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(safePacketBytesList.get(index)));
            try {
                int packetId = buffer.readVarInt();
                ids[index] = localPacketIds.computeIfAbsent(packetId, ignored -> localPacketIds.size());
                offsets[index] = buffer.readerIndex();
            } finally {
                buffer.release();
            }
        }
        return new BatchHeaderPlan(safePacketBytesList, buildPacketIdTable(localPacketIds), ids, offsets);
    }

    public static final class BatchHeaderPlan {
        private final List<byte[]> packets;
        private final int[] packetIdTable;
        private final int[] localIds;
        private final int[] bodyOffsets;

        private BatchHeaderPlan(List<byte[]> packets, int[] table, int[] ids, int[] offsets) {
            this.packets = packets;
            this.packetIdTable = table;
            this.localIds = ids;
            this.bodyOffsets = offsets;
        }

        public int tableLength() { return packetIdTable.length; }

        public int tableEncodedLength() {
            int length = ExactEncoding.varIntSize(packetIdTable.length);
            for (int id : packetIdTable) length = ExactEncoding.add(length, ExactEncoding.varIntSize(id));
            return length;
        }

        public void writeTable(FriendlyByteBuf buffer) {
            buffer.writeVarInt(packetIdTable.length);
            for (int id : packetIdTable) buffer.writeVarInt(id);
        }

        public int entryLength(int index) {
            return ExactEncoding.add(ExactEncoding.varIntSize(localIds[index]),
                    packets.get(index).length - bodyOffsets[index]);
        }

        public void writeEntry(FriendlyByteBuf buffer, int index) {
            byte[] packet = packets.get(index);
            buffer.writeVarInt(localIds[index]);
            buffer.writeBytes(packet, bodyOffsets[index], packet.length - bodyOffsets[index]);
        }
    }

    public List<byte[]> decodeBatchPacketHeaders(int[] packetIdTable, List<byte[]> mappedEntryBytesList) {
        int[] safePacketIdTable = packetIdTable == null ? new int[0] : packetIdTable.clone();
        List<byte[]> safeMappedEntryBytesList = copyPacketBytesList(mappedEntryBytesList);
        List<byte[]> restoredPacketBytesList = new ArrayList<>(safeMappedEntryBytesList.size());
        for (byte[] mappedEntryBytes : safeMappedEntryBytesList) {
            DecodedMappedEntry decodedMappedEntry = decodeMappedEntry(safePacketIdTable, mappedEntryBytes);
            restoredPacketBytesList.add(rebuildOriginalPacketBytes(decodedMappedEntry.packetId(), decodedMappedEntry.bodyBytes()));
        }
        return List.copyOf(restoredPacketBytesList);
    }


    private static DecodedMappedEntry decodeMappedEntry(int[] packetIdTable, byte[] mappedEntryBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(mappedEntryBytes));
        try {
            int localId = buffer.readVarInt();
            if (localId < 0 || localId >= packetIdTable.length) {
                throw new IllegalStateException("Unknown local packet id " + localId + " for packetIdTable size " + packetIdTable.length);
            }

            byte[] bodyBytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bodyBytes);
            return new DecodedMappedEntry(packetIdTable[localId], bodyBytes);
        } finally {
            buffer.release();
        }
    }

    private static byte[] rebuildOriginalPacketBytes(int packetId, byte[] bodyBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(packetId);
            buffer.writeBytes(bodyBytes);
            byte[] packetBytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, packetBytes);
            return packetBytes;
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

    private static List<byte[]> copyPacketBytesList(List<byte[]> packetBytesList) {
        if (packetBytesList == null || packetBytesList.isEmpty()) {
            return List.of();
        }

        List<byte[]> copiedPacketBytesList = new ArrayList<>(packetBytesList.size());
        for (byte[] packetBytes : packetBytesList) {
            copiedPacketBytesList.add(copyBytesOrEmpty(packetBytes));
        }
        return List.copyOf(copiedPacketBytesList);
    }

    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }

    public record EncodedPacketIdBatch(int[] packetIdTable, List<byte[]> mappedEntryBytesList) {
        public EncodedPacketIdBatch {
            packetIdTable = packetIdTable == null ? new int[0] : packetIdTable.clone();
            mappedEntryBytesList = copyPacketBytesList(mappedEntryBytesList);
        }
    }

    private record DecodedMappedEntry(int packetId, byte[] bodyBytes) {
    }
}
