package com.PinkCats.bandwidthoptimizer.recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record RecipeSyncStructuralView(byte[] prefix, List<byte[]> records) {

    private static final int MAX_RECORDS = 262_144;

    public RecipeSyncStructuralView {
        prefix = prefix == null ? new byte[0] : prefix.clone();
        if (records == null || records.isEmpty() || records.size() > MAX_RECORDS) {
            records = List.of();
        } else {
            ArrayList<byte[]> safeRecords = new ArrayList<>(records.size());
            for (byte[] record : records) {
                if (record == null || record.length == 0) {
                    safeRecords.clear();
                    break;
                }
                safeRecords.add(record.clone());
            }
            records = List.copyOf(safeRecords);
        }
    }

    public static RecipeSyncStructuralView verified(byte[] rawPacketBytes, List<byte[]> records) {
        if (rawPacketBytes == null || rawPacketBytes.length == 0 || records == null || records.isEmpty()) {
            return null;
        }
        long recordsBytes = 0L;
        for (byte[] record : records) {
            if (record == null || record.length == 0) {
                return null;
            }
            recordsBytes += record.length;
            if (recordsBytes >= rawPacketBytes.length) {
                return null;
            }
        }
        int prefixLength = rawPacketBytes.length - (int) recordsBytes;
        int offset = prefixLength;
        for (byte[] record : records) {
            if (!Arrays.equals(rawPacketBytes, offset, offset + record.length, record, 0, record.length)) {
                return null;
            }
            offset += record.length;
        }
        return new RecipeSyncStructuralView(Arrays.copyOf(rawPacketBytes, prefixLength), records);
    }

    @Override
    public byte[] prefix() {
        return this.prefix.clone();
    }

    @Override
    public List<byte[]> records() {
        return this.records.stream().map(byte[]::clone).toList();
    }

    byte[] trustedPrefix() {
        return this.prefix;
    }

    List<byte[]> trustedRecords() {
        return this.records;
    }
}
