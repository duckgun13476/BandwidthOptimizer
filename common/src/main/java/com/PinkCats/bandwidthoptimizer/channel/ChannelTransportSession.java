package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmId;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithms;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.TransportAlgorithm;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.KineticStreaming;

import java.util.Arrays;

// Log and manage session
public final class ChannelTransportSession implements AutoCloseable {

    private final TransportAlgorithm algorithm = ChannelTransportAlgorithms.defaultAlgorithm();
    private final ChannelTransportAlgorithmSession outboundSession = this.algorithm.createSession();
    private final ChannelTransportAlgorithmSession inboundSession = this.algorithm.createSession();
    private final ChannelTransportAlgorithmSession outboundRecoverySession = this.algorithm.createSession();
    private final ChannelTransportAlgorithmSession inboundRecoverySession = this.algorithm.createSession();
    private boolean crossFrameZstdEnabled;
    private final ChannelTransportAlgorithmSession outboundStreamingSession;
    private final ChannelTransportAlgorithmSession inboundStreamingSession;
    private final ChannelTransportStreamingRecoveryState streamingRecoveryState = new ChannelTransportStreamingRecoveryState();
    private int outboundStreamingEpoch = 1;
    private int outboundStreamingSequence;

    public ChannelTransportSession() {
        if (this.algorithm instanceof KineticStreaming streamingAlgorithm) {
            this.outboundStreamingSession = streamingAlgorithm.createFlushSession();
            this.inboundStreamingSession = streamingAlgorithm.createFlushSession();
        } else {
            this.outboundStreamingSession = null;
            this.inboundStreamingSession = null;
        }
    }

    public synchronized byte[] encodeSinglePacket(byte[] encodedPacketBytes) {
        return encodeSinglePacketWithTelemetry(encodedPacketBytes).bytes();
    }

    public synchronized byte[] decodeSinglePacket(byte[] transportBytes) {
        return decodeSinglePacketWithTelemetry(transportBytes).bytes();
    }


    public synchronized void reset() {
        this.outboundSession.reset();
        this.inboundSession.reset();
        this.outboundRecoverySession.reset();
        this.inboundRecoverySession.reset();
        resetStreamingSessions();
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        try {
            this.outboundSession.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            this.inboundSession.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        failure = closeStreamingSession(this.outboundRecoverySession, failure);
        failure = closeStreamingSession(this.inboundRecoverySession, failure);
        failure = closeStreamingSession(this.outboundStreamingSession, failure);
        failure = closeStreamingSession(this.inboundStreamingSession, failure);
        if (failure != null) {
            throw failure;
        }
    }


    // Telemetry
    public synchronized PacketResult encodeSinglePacketWithTelemetry(byte[] encodedPacketBytes) {
        byte[] safeBytes = copyBytesOrEmpty(encodedPacketBytes);
        ChannelTransportAlgorithmSession.OperationResult result = this.outboundSession.encodePacketWithTelemetry(safeBytes);
        return new PacketResult(copyBytesOrEmpty(result.bytes()), fallbackTelemetry(result.telemetry(), safeBytes.length));
    }

    public synchronized PacketResult encodeSinglePacketWithLiteralMappingTelemetry(byte[] encodedPacketBytes) {
        byte[] safeBytes = copyBytesOrEmpty(encodedPacketBytes);
        ChannelTransportAlgorithmSession.OperationResult result =
                this.outboundSession.encodePacketWithLiteralMappingTelemetry(safeBytes);
        return new PacketResult(copyBytesOrEmpty(result.bytes()), fallbackTelemetry(result.telemetry(), safeBytes.length));
    }

    public synchronized PacketResult decodeSinglePacketWithTelemetry(byte[] transportBytes) {
        byte[] safeBytes = copyBytesOrEmpty(transportBytes);
        ChannelTransportAlgorithmSession.OperationResult result = this.inboundSession.decodePacketWithTelemetry(safeBytes);
        return new PacketResult(copyBytesOrEmpty(result.bytes()), fallbackTelemetry(result.telemetry(), safeBytes.length));
    }

    public synchronized boolean isCrossFrameZstdEnabled() {
        return this.crossFrameZstdEnabled && this.outboundStreamingSession != null;
    }

    public synchronized void setCrossFrameZstdEnabled(boolean enabled) {
        if (enabled && this.outboundStreamingSession == null) {
            throw new IllegalStateException("Cross-frame Zstd is unavailable for this transport algorithm");
        }
        if (this.crossFrameZstdEnabled == enabled) {
            return;
        }
        resetStreamingSessions();
        this.crossFrameZstdEnabled = enabled;
    }

    public synchronized StreamingPacketResult encodeBatchWithStreamingZstd(byte[] encodedPacketBytes) {
        if (!isCrossFrameZstdEnabled()) {
            throw new IllegalStateException("Cross-frame Zstd is not enabled for this transport session");
        }
        if (this.streamingRecoveryState.outboundEpochClosed()) {
            throw new IllegalStateException("Cross-frame Zstd epoch is awaiting confirmation");
        }
        byte[] safeBytes = copyBytesOrEmpty(encodedPacketBytes);
        ChannelTransportAlgorithmSession.OperationResult result =
                this.outboundStreamingSession.encodePacketWithLiteralMappingTelemetry(safeBytes);
        this.outboundStreamingSequence = this.outboundStreamingSequence == Integer.MAX_VALUE
                ? 1
                : this.outboundStreamingSequence + 1;
        return new StreamingPacketResult(
                this.outboundStreamingEpoch,
                this.outboundStreamingSequence,
                new PacketResult(copyBytesOrEmpty(result.bytes()), fallbackTelemetry(result.telemetry(), safeBytes.length))
        );
    }

    public synchronized boolean isOutboundStreamingEpochClosed() {
        return this.streamingRecoveryState.outboundEpochClosed();
    }

    public synchronized StreamingEpochBoundary outboundStreamingEpochBoundary() {
        ChannelTransportStreamingRecoveryState.EpochBoundary boundary =
                this.streamingRecoveryState.outboundEpochBoundary();
        return boundary == null ? null : new StreamingEpochBoundary(boundary.epoch(), boundary.lastSequence());
    }

    public synchronized void acceptOutboundStreamingEpochOk(int epoch, int lastSequence) {
        this.streamingRecoveryState.acceptOutboundEpochOk(epoch, lastSequence);
    }

    public synchronized void resetInboundStreamingEpoch() {
        if (this.inboundStreamingSession != null) {
            this.inboundStreamingSession.reset();
        }
        this.streamingRecoveryState.resetInbound();
    }

    public synchronized boolean acceptInboundStreamingEpochComplete(int epoch, int lastSequence) {
        return this.streamingRecoveryState.acceptInboundEpochComplete(epoch, lastSequence);
    }

    public synchronized int outboundStreamingEpoch() {
        return this.outboundStreamingEpoch;
    }

    public synchronized PacketResult decodeBatchWithStreamingZstd(byte[] transportBytes) {
        if (this.inboundStreamingSession == null) {
            throw new IllegalStateException("Cross-frame Zstd is unavailable for this transport session");
        }
        byte[] safeBytes = copyBytesOrEmpty(transportBytes);
        ChannelTransportAlgorithmSession.OperationResult result =
                this.inboundStreamingSession.decodePacketWithTelemetry(safeBytes);
        return new PacketResult(copyBytesOrEmpty(result.bytes()), fallbackTelemetry(result.telemetry(), safeBytes.length));
    }

    public synchronized void acceptInboundStreamingFrame(int epoch, int sequence) {
        boolean newEpoch = this.streamingRecoveryState.acceptInboundFrame(epoch, sequence);
        if (newEpoch && this.inboundStreamingSession != null) {
            this.inboundStreamingSession.reset();
        }
    }

    public synchronized void completeInboundStreamingFrame(int epoch, int sequence) {
        this.streamingRecoveryState.completeInboundFrame(epoch, sequence);
    }

    public synchronized void failInboundStreamingFrame(int epoch, int sequence) {
        this.streamingRecoveryState.failInboundFrame(epoch, sequence);
    }

    public synchronized void prepareInboundStreamingReplay(int epoch, int expectedSequence) {
        this.streamingRecoveryState.prepareInboundReplay(epoch, expectedSequence);
    }

    public synchronized boolean willCloseOutboundStreamingEpoch(int epoch, int additionalRetainedBytes) {
        return this.streamingRecoveryState.willCloseOutboundEpoch(epoch, additionalRetainedBytes);
    }

    public synchronized boolean retainOutboundStreamingFrame(
            int epoch,
            int sequence,
            byte[] transportFrameBytes,
            byte[] fallbackBatchPayloadBytes,
            int originalPacketBytes,
            int originalPacketCount
    ) {
        return this.streamingRecoveryState.retainOutboundFrame(
                epoch,
                sequence,
                transportFrameBytes,
                fallbackBatchPayloadBytes,
                originalPacketBytes,
                originalPacketCount
        );
    }

    public synchronized java.util.List<byte[]> replayOutboundStreamingFrames(int epoch, int expectedSequence) {
        return this.streamingRecoveryState.replayOutboundFrames(epoch, expectedSequence);
    }

    public synchronized java.util.List<StreamingFallbackBatch> fallbackOutboundStreamingBatches(
            int epoch,
            int expectedSequence
    ) {
        return this.streamingRecoveryState.fallbackOutboundBatches(epoch, expectedSequence).stream()
                .map(batch -> new StreamingFallbackBatch(
                        batch.sequence(),
                        batch.batchPayloadBytes(),
                        batch.originalPacketBytes(),
                        batch.originalPacketCount()
                ))
                .toList();
    }

    public synchronized PacketResult encodeStreamingFallbackBatch(byte[] batchPayloadBytes) {
        byte[] safeBytes = copyBytesOrEmpty(batchPayloadBytes);
        return encodeIndependentPacket(safeBytes);
    }

    public synchronized PacketResult decodeStreamingFallbackBatch(byte[] transportBytes) {
        return decodeIndependentPacket(transportBytes);
    }

    private PacketResult decodeIndependentPacket(byte[] transportBytes) {
        byte[] safeBytes = copyBytesOrEmpty(transportBytes);
        this.inboundRecoverySession.reset();
        try {
            ChannelTransportAlgorithmSession.OperationResult result =
                    this.inboundRecoverySession.decodePacketWithTelemetry(safeBytes);
            return new PacketResult(
                    copyBytesOrEmpty(result.bytes()),
                    fallbackTelemetry(result.telemetry(), safeBytes.length)
            );
        } finally {
            this.inboundRecoverySession.reset();
        }
    }

    public synchronized void resetInboundStreamingForRecovery() {
        beginInboundStreamingRecovery(this.streamingRecoveryState.inboundRecoveryPoint());
    }

    public synchronized boolean beginInboundStreamingRecovery(
            ChannelTransportStreamingControlCodec.RecoveryRequest recoveryRequest
    ) {
        if (recoveryRequest == null) {
            throw new IllegalArgumentException("Streaming recovery request is required");
        }
        boolean firstRequest = this.streamingRecoveryState.beginInboundRecovery(
                recoveryRequest.epoch(),
                recoveryRequest.expectedSequence()
        );
        if (firstRequest && this.inboundStreamingSession != null) {
            this.inboundStreamingSession.reset();
        }
        return firstRequest;
    }

    public synchronized ChannelTransportStreamingControlCodec.RecoveryRequest inboundStreamingRecoveryPoint() {
        return this.streamingRecoveryState.inboundRecoveryPoint();
    }

    public synchronized void restartOutboundStreamingEpoch() {
        if (this.outboundStreamingSession != null) {
            this.outboundStreamingSession.reset();
        }
        this.streamingRecoveryState.resetOutbound();
        this.outboundStreamingEpoch = this.outboundStreamingEpoch == Integer.MAX_VALUE
                ? 1
                : this.outboundStreamingEpoch + 1;
        this.outboundStreamingSequence = 0;
        this.crossFrameZstdEnabled = this.outboundStreamingSession != null;
    }

    public synchronized void fallbackOutboundStreamingEpoch() {
        if (this.outboundStreamingSession != null) {
            this.outboundStreamingSession.reset();
        }
        // Keep the closed epoch recoverable until its late ACK or recovery request arrives.
        this.outboundStreamingEpoch = this.outboundStreamingEpoch == Integer.MAX_VALUE
                ? 1
                : this.outboundStreamingEpoch + 1;
        this.outboundStreamingSequence = 0;
        this.crossFrameZstdEnabled = false;
    }

    private PacketResult encodeIndependentPacket(byte[] packetBytes) {
        this.outboundRecoverySession.reset();
        try {
            ChannelTransportAlgorithmSession.OperationResult result =
                    this.outboundRecoverySession.encodePacketWithLiteralMappingTelemetry(packetBytes);
            return new PacketResult(
                    copyBytesOrEmpty(result.bytes()),
                    fallbackTelemetry(result.telemetry(), packetBytes.length)
            );
        } finally {
            this.outboundRecoverySession.reset();
        }
    }

    private void resetStreamingSessions() {
        if (this.outboundStreamingSession != null) {
            this.outboundStreamingSession.reset();
        }
        if (this.inboundStreamingSession != null) {
            this.inboundStreamingSession.reset();
        }
        this.streamingRecoveryState.resetInbound();
        this.streamingRecoveryState.resetOutbound();
        this.outboundStreamingEpoch = this.outboundStreamingEpoch == Integer.MAX_VALUE
                ? 1
                : this.outboundStreamingEpoch + 1;
        this.outboundStreamingSequence = 0;
    }

    private static RuntimeException closeStreamingSession(
            ChannelTransportAlgorithmSession session,
            RuntimeException failure
    ) {
        if (session == null) {
            return failure;
        }
        try {
            session.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        }
        return failure;
    }


    // Fix return empty
    private ChannelTransportOperationTelemetry fallbackTelemetry(ChannelTransportOperationTelemetry telemetry, int mappingStageBytes) {
        if (telemetry != null) {
            return telemetry;
        }
        return ChannelTransportOperationTelemetry.passthrough(
                ChannelTransportLayerRuntimeConfig.algorithmId(),
                ChannelTransportLayerRuntimeConfig.isMappingEnabled(),
                ChannelTransportLayerRuntimeConfig.isZstdEnabled(),
                mappingStageBytes
        );
    }







    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }

    public record PacketResult(byte[] bytes, ChannelTransportOperationTelemetry telemetry) {
    }

    public record StreamingPacketResult(int epoch, int sequence, PacketResult packetResult) {
    }

    public record StreamingFallbackBatch(
            int sequence,
            byte[] batchPayloadBytes,
            int originalPacketBytes,
            int originalPacketCount
    ) {
    }

    public record StreamingEpochBoundary(int epoch, int lastSequence) {
    }


    public ChannelTransportAlgorithmId algorithmId() {
        return this.algorithm.id();
    }
}
