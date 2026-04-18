package com.PinkCats.bandwidthoptimizer.Old.network.algorithm;

import java.util.List;

public interface BatchAlgorithm {

    String id();

    Session createSession();

    record EncodedBatch(
            byte[] bytes,
            List<EntryInfo> entryInfos,
            int addedMappings,
            int removedMappings
    ) {
    }

    interface Session {

        EncodedBatch encode(List<byte[]> payloads);

        default EncodedBatch encodeEntries(List<BatchInput> entries) {
            return encode(entries.stream().map(BatchInput::payload).toList());
        }

        List<byte[]> decode(byte[] encodedBytes);

        default void reset() {
        }
    }

    record BatchInput(
            String groupKey,
            byte[] payload
    ) {
    }

    record EntryInfo(
            int index,
            boolean reference,
            int referenceIndex,
            int referencedWireBytes,
            int encodedBytes
    ) {
        public static EntryInfo literal(int index, int literalBytes, int encodedBytes) {
            return new EntryInfo(index, false, -1, literalBytes, encodedBytes);
        }

        public static EntryInfo reference(int index, int referenceIndex, int referencedWireBytes, int encodedBytes) {
            return new EntryInfo(index, true, referenceIndex, referencedWireBytes, encodedBytes);
        }
    }
}
