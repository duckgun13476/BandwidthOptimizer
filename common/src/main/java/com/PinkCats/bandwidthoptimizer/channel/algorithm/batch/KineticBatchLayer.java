package com.PinkCats.bandwidthoptimizer.channel.algorithm.batch;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.header.KineticPacketIdMappingLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.TransportLayer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// This layer owns the byte-level payload codec used by transport batch frames.
public final class KineticBatchLayer implements TransportLayer {

    private static final int BATCH_PAYLOAD_VERSION = 1;
    private static final int MAX_BATCH_PACKET_COUNT = 40960;
    private static final int MAX_PACKET_ID_TABLE_ENTRIES = 4096;
    private static final int MAX_BATCH_ENTRY_BYTES = ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES;
    private static final int MAX_BATCH_TOTAL_BYTES = ChannelTransportPayloadLimits.MAX_BATCH_PAYLOAD_BYTES;

    private final KineticPacketIdMappingLayer packetIdMappingLayer = new KineticPacketIdMappingLayer();

    @Override
    public byte[] encode(byte[] inputBytes) {
        return inputBytes;
    }

    @Override
    public byte[] decode(byte[] inputBytes) {
        return inputBytes;
    }

    // Batch as a cargo
    public byte[] encodePacketBatch(List<byte[]> packetBytesList) {
        List<byte[]> safePacketBytesList = copyPacketBytesList(packetBytesList);
        validateOutboundBatchEntries(safePacketBytesList, "outbound batch packet");
        boolean packetIdMappingEnabled = ChannelTransportLayerRuntimeConfig.isPacketIdMappingEnabled();
        KineticPacketIdMappingLayer.EncodedPacketIdBatch encodedPacketIdBatch = packetIdMappingEnabled
                ? this.packetIdMappingLayer.encodeBatchPacketHeaders(safePacketBytesList)
                : new KineticPacketIdMappingLayer.EncodedPacketIdBatch(new int[0], safePacketBytesList);
        List<byte[]> batchEntryBytesList = packetIdMappingEnabled
                ? encodedPacketIdBatch.mappedEntryBytesList()
                : safePacketBytesList;
        validateOutboundBatchEntries(batchEntryBytesList, "outbound mapped batch packet");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(BATCH_PAYLOAD_VERSION);
            buffer.writeBoolean(packetIdMappingEnabled);
            if (packetIdMappingEnabled) {
                buffer.writeVarInt(encodedPacketIdBatch.packetIdTable().length);
                for (int packetId : encodedPacketIdBatch.packetIdTable()) {
                    buffer.writeVarInt(packetId);
                }
            }

            buffer.writeVarInt(batchEntryBytesList.size());
            for (byte[] packetBytes : batchEntryBytesList) {
                buffer.writeVarInt(packetBytes.length);
                buffer.writeBytes(packetBytes);
            }

            byte[] encodedBytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, encodedBytes);
            return encodedBytes;
        } finally {
            buffer.release();
        }
    }

    // Sender and receiver must enforce the same batch limits.
    private static void validateOutboundBatchEntries(List<byte[]> packetBytesList, String fieldName) {
        if (packetBytesList.size() > MAX_BATCH_PACKET_COUNT) {
            throw new IllegalArgumentException("batch packet count out of range: " + packetBytesList.size() + " > " + MAX_BATCH_PACKET_COUNT);
        }
        int totalBytes = 0;
        for (byte[] packetBytes : packetBytesList) {
            int packetLength = packetBytes == null ? 0 : packetBytes.length;
            if (packetLength > MAX_BATCH_ENTRY_BYTES) {
                throw new IllegalArgumentException(fieldName + " bytes out of range: " + packetLength + " > " + MAX_BATCH_ENTRY_BYTES);
            }
            totalBytes += packetLength;
            if (totalBytes < 0 || totalBytes > MAX_BATCH_TOTAL_BYTES) {
                throw new IllegalArgumentException("batch total bytes out of range: " + totalBytes + " > " + MAX_BATCH_TOTAL_BYTES);
            }
        }
    }

    // unpack as a Cargo
    public List<byte[]> decodePacketBatch(byte[] batchBytes) {
        byte[] safeBatchBytes = batchBytes == null ? new byte[0] : Arrays.copyOf(batchBytes, batchBytes.length);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(safeBatchBytes));
        try {
            int payloadVersion = buffer.readVarInt();
            if (payloadVersion != BATCH_PAYLOAD_VERSION) {
                throw new IllegalStateException("Unsupported channel transport batch payload version: " + payloadVersion);
            }

            boolean packetIdMappingEnabled = buffer.readBoolean();
            int[] packetIdTable = packetIdMappingEnabled ? readPacketIdTable(buffer) : new int[0];
            int packetCount = readBoundedVarInt(buffer, MAX_BATCH_PACKET_COUNT, "batch packet count");
            List<byte[]> batchEntryBytesList = new ArrayList<>(packetCount);
            int totalPacketBytes = 0;
            for (int index = 0; index < packetCount; index++) {
                int packetLength = readBoundedReadableLength(buffer, MAX_BATCH_ENTRY_BYTES, "batch packet bytes");
                totalPacketBytes = checkedTotalBytes(totalPacketBytes, packetLength);
                byte[] packetBytes = new byte[packetLength];
                buffer.readBytes(packetBytes);
                batchEntryBytesList.add(packetBytes);
            }

            if (buffer.isReadable()) {
                throw new IllegalStateException("Channel transport batch left unexpected trailing bytes: " + buffer.readableBytes());
            }

            if (!packetIdMappingEnabled) {
                return List.copyOf(batchEntryBytesList);
            }
            return this.packetIdMappingLayer.decodeBatchPacketHeaders(packetIdTable, batchEntryBytesList);
        } finally {
            buffer.release();
        }
    }

    // packetIdTable for use
    private static int[] readPacketIdTable(FriendlyByteBuf buffer) {
        int[] packetIdTable = new int[readBoundedVarInt(buffer, MAX_PACKET_ID_TABLE_ENTRIES, "packet id table size")];
        for (int index = 0; index < packetIdTable.length; index++) {
            packetIdTable[index] = buffer.readVarInt();
        }
        return packetIdTable;
    }


    private static int readBoundedVarInt(FriendlyByteBuf buffer, int maxValue, String fieldName) {
        int value = buffer.readVarInt();
        if (value < 0 || value > maxValue) {
            throw new IllegalStateException(fieldName + " out of range: " + value + " > " + maxValue);
        }
        return value;
    }

    private static int readBoundedReadableLength(FriendlyByteBuf buffer, int maxValue, String fieldName) {
        int value = readBoundedVarInt(buffer, maxValue, fieldName);
        if (value > buffer.readableBytes()) {
            throw new IllegalStateException(fieldName + " exceeds remaining bytes: " + value + " > " + buffer.readableBytes());
        }
        return value;
    }

    private static int checkedTotalBytes(int currentBytes, int addedBytes) {
        int totalBytes = currentBytes + addedBytes;
        if (totalBytes < currentBytes || totalBytes > MAX_BATCH_TOTAL_BYTES) {
            throw new IllegalStateException("batch total bytes out of range: " + totalBytes);
        }
        return totalBytes;
    }

    private static List<byte[]> copyPacketBytesList(List<byte[]> packetBytesList) {
        if (packetBytesList == null || packetBytesList.isEmpty()) {
            return List.of();
        }

        List<byte[]> copiedPacketBytesList = new ArrayList<>(packetBytesList.size());
        for (byte[] packetBytes : packetBytesList) {
            copiedPacketBytesList.add(packetBytes == null ? new byte[0] : Arrays.copyOf(packetBytes, packetBytes.length));
        }
        return List.copyOf(copiedPacketBytesList);
    }
}
