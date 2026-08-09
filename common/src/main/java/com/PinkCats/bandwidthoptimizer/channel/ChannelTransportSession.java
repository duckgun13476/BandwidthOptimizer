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
    private boolean crossFrameZstdEnabled;
    private final ChannelTransportAlgorithmSession outboundStreamingSession;
    private final ChannelTransportAlgorithmSession inboundStreamingSession;
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

    public synchronized PacketResult decodeBatchWithStreamingZstd(byte[] transportBytes) {
        if (this.inboundStreamingSession == null) {
            throw new IllegalStateException("Cross-frame Zstd is unavailable for this transport session");
        }
        byte[] safeBytes = copyBytesOrEmpty(transportBytes);
        ChannelTransportAlgorithmSession.OperationResult result =
                this.inboundStreamingSession.decodePacketWithTelemetry(safeBytes);
        return new PacketResult(copyBytesOrEmpty(result.bytes()), fallbackTelemetry(result.telemetry(), safeBytes.length));
    }

    private void resetStreamingSessions() {
        if (this.outboundStreamingSession != null) {
            this.outboundStreamingSession.reset();
        }
        if (this.inboundStreamingSession != null) {
            this.inboundStreamingSession.reset();
        }
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


    public ChannelTransportAlgorithmId algorithmId() {
        return this.algorithm.id();
    }
}
