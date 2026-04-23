package com.PinkCats.bandwidthoptimizer.channel.algorithm.header;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KineticPacketIdMappingLayer {

    // Layer for packet header。
    public EncodedPacketIdBatch encodeBatchPacketHeaders(List<byte[]> originalPacketBytesList) {
        List<byte[]> safePacketBytesList = copyPacketBytesList(originalPacketBytesList);
        Map<Integer, Integer> localPacketIds = new LinkedHashMap<>();
        List<byte[]> mappedEntryBytesList = new ArrayList<>(safePacketBytesList.size());
        for (byte[] originalPacketBytes : safePacketBytesList) {
            DecodedPacketHeader decodedPacketHeader = decodePacketHeader(originalPacketBytes);
            int localId = localPacketIds.computeIfAbsent(decodedPacketHeader.packetId(), ignored -> localPacketIds.size());
            mappedEntryBytesList.add(encodeMappedEntry(localId, decodedPacketHeader.bodyBytes()));
        }
        return new EncodedPacketIdBatch(buildPacketIdTable(localPacketIds), List.copyOf(mappedEntryBytesList));
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

    private static DecodedPacketHeader decodePacketHeader(byte[] originalPacketBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(copyBytesOrEmpty(originalPacketBytes)));
        try {
            int packetId = buffer.readVarInt();
            byte[] bodyBytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bodyBytes);
            return new DecodedPacketHeader(packetId, bodyBytes);
        } finally {
            buffer.release();
        }
    }

    private static byte[] encodeMappedEntry(int localId, byte[] bodyBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(localId);
            buffer.writeBytes(copyBytesOrEmpty(bodyBytes));
            byte[] encodedBytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, encodedBytes);
            return encodedBytes;
        } finally {
            buffer.release();
        }
    }

    private static DecodedMappedEntry decodeMappedEntry(int[] packetIdTable, byte[] mappedEntryBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(copyBytesOrEmpty(mappedEntryBytes)));
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
            buffer.writeBytes(copyBytesOrEmpty(bodyBytes));
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

    private record DecodedPacketHeader(int packetId, byte[] bodyBytes) {
    }

    private record DecodedMappedEntry(int packetId, byte[] bodyBytes) {
    }
}
