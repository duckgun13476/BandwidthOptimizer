package com.PinkCats.bandwidthoptimizer.Old.network.batch;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PayloadBatching {

    public static final long WINDOW_MILLIS = 10L;

    private PayloadBatching() {
    }

    public static boolean withinWindow(long batchStartTimestamp, long currentTimestamp) {
        if (batchStartTimestamp < 0L || currentTimestamp < 0L) {
            return false;
        }
        return currentTimestamp - batchStartTimestamp < WINDOW_MILLIS;
    }

    public static EncodedPayloadBatch encodePayloads(List<byte[]> payloads) {
        return encodePayloads(payloads, new Session());
    }

    public static EncodedPayloadBatch encodePayloads(List<byte[]> payloads, Session session) {
        List<BatchAlgorithm.BatchInput> entries = payloads.stream()
                .map(payload -> new BatchAlgorithm.BatchInput("", payload))
                .toList();
        return encodeEntries(entries, session);
    }

    public static EncodedPayloadBatch encodeEntries(List<BatchAlgorithm.BatchInput> entries) {
        return encodeEntries(entries, new Session());
    }

    public static EncodedPayloadBatch encodeEntries(List<BatchAlgorithm.BatchInput> entries, Session session) {
        BatchAlgorithm algorithm = BatchAlgorithmRegistry.configured();
        BatchAlgorithm.EncodedBatch encodedBatch;
        try {
            BatchAlgorithm.Session algorithmSession = session.encodeSessionFor(algorithm);
            encodedBatch = algorithmSession.encodeEntries(entries);
        } catch (Throwable error) {
            BatchAlgorithm fallbackAlgorithm = BatchAlgorithmRegistry.fallbackAfterFailure(algorithm.id(), error);
            session.resetEncode();
            BatchAlgorithm.Session fallbackSession = session.encodeSessionFor(fallbackAlgorithm);
            encodedBatch = fallbackSession.encodeEntries(entries);
            algorithm = fallbackAlgorithm;
        }
        return new EncodedPayloadBatch(
                algorithm.id(),
                encodedBatch.bytes(),
                encodedBatch.entryInfos(),
                encodedBatch.addedMappings(),
                encodedBatch.removedMappings()
        );
    }

    public static List<byte[]> decodePayloads(String algorithmId, byte[] encodedBytes) {
        return decodePayloads(algorithmId, encodedBytes, new Session());
    }

    public static List<byte[]> decodePayloads(String algorithmId, byte[] encodedBytes, Session session) {
        BatchAlgorithm algorithm = BatchAlgorithmRegistry.byId(algorithmId);
        BatchAlgorithm.Session algorithmSession = session.decodeSessionFor(algorithm);
        return algorithmSession.decode(encodedBytes);
    }

    public static final class Session {
        private String encodeAlgorithmId;
        private BatchAlgorithm.Session encodeSession;
        private final Map<String, BatchAlgorithm.Session> decodeSessions = new HashMap<>();

        public void resetAll() {
            if (this.encodeSession != null) {
                this.encodeSession.reset();
            }
            this.encodeAlgorithmId = null;
            this.encodeSession = null;
            for (BatchAlgorithm.Session decodeSession : this.decodeSessions.values()) {
                decodeSession.reset();
            }
            this.decodeSessions.clear();
        }

        public void resetDecode(String algorithmId) {
            BatchAlgorithm.Session decodeSession = this.decodeSessions.remove(algorithmId);
            if (decodeSession != null) {
                decodeSession.reset();
            }
        }

        public void resetEncode() {
            if (this.encodeSession != null) {
                this.encodeSession.reset();
            }
            this.encodeAlgorithmId = null;
            this.encodeSession = null;
        }

        private BatchAlgorithm.Session encodeSessionFor(BatchAlgorithm algorithm) {
            if (this.encodeSession == null || !algorithm.id().equals(this.encodeAlgorithmId)) {
                if (this.encodeSession != null) {
                    this.encodeSession.reset();
                }
                this.encodeAlgorithmId = algorithm.id();
                this.encodeSession = algorithm.createSession();
            }
            return this.encodeSession;
        }

        private BatchAlgorithm.Session decodeSessionFor(BatchAlgorithm algorithm) {
            return this.decodeSessions.computeIfAbsent(algorithm.id(), ignored -> algorithm.createSession());
        }
    }

    public record EncodedPayloadBatch(
            String algorithmId,
            byte[] bytes,
            List<BatchAlgorithm.EntryInfo> entryInfos,
            int addedMappings,
            int removedMappings
    ) {
    }
}
