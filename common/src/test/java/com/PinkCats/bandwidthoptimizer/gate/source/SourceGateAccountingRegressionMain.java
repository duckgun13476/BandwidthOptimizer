package com.PinkCats.bandwidthoptimizer.gate.source;

import com.PinkCats.bandwidthoptimizer.gate.IdleGateMode;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateStatePayload;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;

public final class SourceGateAccountingRegressionMain {

    private SourceGateAccountingRegressionMain() {}

    public static void main(String[] args) throws Exception {
        verifyClientCounters();
        verifyServerDeltas();
        verifyIdleGateWireCompatibility();
        System.out.println("Source gate accounting regression passed.");
    }

    private static void verifyClientCounters() {
        ClientSourceGateStats.reset();
        ClientSourceGateStats.recordEstimatedSavings(120L, 80L, 1L);
        ClientSourceGateStats.Snapshot snapshot = ClientSourceGateStats.snapshot();
        require(snapshot.estimatedSavedBytes() == 120L, "client total estimate");
        require(snapshot.estimatedOutboundSavedBytes() == 80L, "client outbound estimate");
        require(snapshot.suppressedFrames() == 1L, "client frame count");
    }

    private static void verifyServerDeltas() {
        ServerSourceGateStats.reset();
        UUID playerId = UUID.randomUUID();
        ServerSourceGateStats.accept(playerId, 100L, 80L, 1L);
        require(ServerSourceGateStats.snapshot().estimatedSavedBytes() == 0L, "first sample establishes baseline");

        ServerSourceGateStats.accept(playerId, 220L, 170L, 3L);
        assertServerSnapshot(120L, 90L, 2L, "monotonic delta");
        ServerSourceGateStats.accept(playerId, 220L, 170L, 3L);
        assertServerSnapshot(120L, 90L, 2L, "equal sample");

        ServerSourceGateStats.accept(playerId, 5L, 4L, 1L);
        assertServerSnapshot(120L, 90L, 2L, "counter rollback");
        ServerSourceGateStats.accept(playerId, 15L, 14L, 3L);
        assertServerSnapshot(130L, 100L, 4L, "post-rollback delta");

        ServerSourceGateStats.removeClient(playerId);
        ServerSourceGateStats.accept(playerId, 1_000L, 900L, 30L);
        assertServerSnapshot(130L, 100L, 4L, "reconnect baseline");
    }

    private static void verifyIdleGateWireCompatibility() throws Exception {
        IdleGateStatePayload payload = new IdleGateStatePayload(
                7,
                IdleGateMode.BACKGROUND_IDLE,
                false,
                1234L,
                500L,
                300L,
                4L);
        IdleGateStatePayload decoded = IdleGateStatePayload.fromBytes(payload.toBytes());
        require(decoded.equals(payload), "v2 round trip");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            output.writeInt(8);
            output.writeByte(IdleGateMode.FOREGROUND_STILL.id());
            output.writeBoolean(true);
            output.writeLong(5678L);
        }
        IdleGateStatePayload legacy = IdleGateStatePayload.fromBytes(bytes.toByteArray());
        require(legacy.sequence() == 8, "v1 sequence");
        require(legacy.mode() == IdleGateMode.FOREGROUND_STILL, "v1 mode");
        require(legacy.sourceGateEstimatedSavedBytes() == 0L, "v1 total default");
        require(legacy.sourceGateEstimatedOutboundSavedBytes() == 0L, "v1 outbound default");
        require(legacy.sourceGateSuppressedFrames() == 0L, "v1 frame default");
    }

    private static void assertServerSnapshot(long total, long outbound, long frames, String label) {
        ServerSourceGateStats.Snapshot snapshot = ServerSourceGateStats.snapshot();
        require(snapshot.estimatedSavedBytes() == total, label + " total");
        require(snapshot.estimatedOutboundSavedBytes() == outbound, label + " outbound");
        require(snapshot.suppressedFrames() == frames, label + " frames");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Failed: " + label);
        }
    }
}
