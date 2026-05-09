package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmId;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithms;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.TransportAlgorithm;

import java.util.Arrays;

// Log and manage session
public final class ChannelTransportSession {

    private final TransportAlgorithm algorithm = ChannelTransportAlgorithms.defaultAlgorithm();
    private final ChannelTransportAlgorithmSession outboundSession = this.algorithm.createSession();
    private final ChannelTransportAlgorithmSession inboundSession = this.algorithm.createSession();

    public synchronized byte[] encodeSinglePacket(byte[] encodedPacketBytes) {
        return encodeSinglePacketWithTelemetry(encodedPacketBytes).bytes();
    }

    public synchronized byte[] decodeSinglePacket(byte[] transportBytes) {
        return decodeSinglePacketWithTelemetry(transportBytes).bytes();
    }


    public synchronized void reset() {
        this.outboundSession.reset();
        this.inboundSession.reset();
    }


    // Telemetry
    public synchronized PacketResult encodeSinglePacketWithTelemetry(byte[] encodedPacketBytes) {
        byte[] safeBytes = copyBytesOrEmpty(encodedPacketBytes);
        ChannelTransportAlgorithmSession.OperationResult result = this.outboundSession.encodePacketWithTelemetry(safeBytes);
        return new PacketResult(copyBytesOrEmpty(result.bytes()), fallbackTelemetry(result.telemetry(), safeBytes.length));
    }

    public synchronized PacketResult decodeSinglePacketWithTelemetry(byte[] transportBytes) {
        byte[] safeBytes = copyBytesOrEmpty(transportBytes);
        ChannelTransportAlgorithmSession.OperationResult result = this.inboundSession.decodePacketWithTelemetry(safeBytes);
        return new PacketResult(copyBytesOrEmpty(result.bytes()), fallbackTelemetry(result.telemetry(), safeBytes.length));
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


    public ChannelTransportAlgorithmId algorithmId() {
        return this.algorithm.id();
    }
}
