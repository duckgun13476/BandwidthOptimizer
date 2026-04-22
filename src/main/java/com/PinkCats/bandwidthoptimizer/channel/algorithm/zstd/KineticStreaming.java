package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.TransportAlgorithm;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.KineticBatchLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mapping.KineticMapTableLayer;

import java.util.Arrays;

// streaming transport.
public final class KineticStreaming implements TransportAlgorithm {
    private static final int DEFAULT_COMPRESSION_LEVEL = 4;

    @Override
    public String id() {
        return ChannelTransportLayerRuntimeConfig.algorithmId();
    }

    @Override
    public ChannelTransportAlgorithmSession createSession() {
        return new StreamingSession();
    }


    private static int compressionLevel() {
        return Config.batchStreamingZstdLevel > 0 ? Config.batchStreamingZstdLevel : DEFAULT_COMPRESSION_LEVEL;
    }


    private static final class StreamingSession implements ChannelTransportAlgorithmSession {

        private final KineticBatchLayer batchLayer = new KineticBatchLayer();
        private final KineticMapTableLayer mapTableLayer = new KineticMapTableLayer();
        private final KineticStreamingLayer zstdLayer = new KineticStreamingLayer(compressionLevel());

        @Override
        public byte[] encodePacket(byte[] packetBytes) {
            return encodePacketWithTelemetry(packetBytes).bytes();
        }

        @Override
        public byte[] decodePacket(byte[] encodedBytes) {
            return decodePacketWithTelemetry(encodedBytes).bytes();
        }

        @Override
        public ChannelTransportAlgorithmSession.OperationResult encodePacketWithTelemetry(byte[] packetBytes) {
            byte[] safePacketBytes = copyBytesOrEmpty(packetBytes);
            byte[] batchBytes = safePacketBytes;
            KineticMapTableLayer.LayerResult mappingResult = encodeMappingStage(batchBytes);
            byte[] transportBodyBytes = encodeZstdStage(mappingResult.bytes());
            return new ChannelTransportAlgorithmSession.OperationResult(
                    transportBodyBytes,
                    toTelemetry(mappingResult, mappingResult.bytes().length)
            );
        }

        @Override
        public ChannelTransportAlgorithmSession.OperationResult decodePacketWithTelemetry(byte[] encodedBytes) {
            byte[] safeEncodedBytes = copyBytesOrEmpty(encodedBytes);
            byte[] mappingStageBytes = decodeZstdStage(safeEncodedBytes);
            KineticMapTableLayer.LayerResult mappingResult = decodeMappingStage(mappingStageBytes);
            return new ChannelTransportAlgorithmSession.OperationResult(
                    mappingResult.bytes(),
                    toTelemetry(mappingResult, mappingStageBytes.length)
            );
        }

        @Override
        public void reset() {
            this.batchLayer.reset();
            this.mapTableLayer.reset();
            this.zstdLayer.reset();
        }

        private KineticMapTableLayer.LayerResult encodeMappingStage(byte[] packetBytes) {
            if (!ChannelTransportLayerRuntimeConfig.isMappingEnabled()) {
                return KineticMapTableLayer.LayerResult.passthrough(packetBytes);
            }
            return this.mapTableLayer.encodeWithTelemetry(packetBytes);
        }

        private byte[] encodeZstdStage(byte[] mappedBytes) {
            return ChannelTransportLayerRuntimeConfig.isZstdEnabled() ? this.zstdLayer.encode(mappedBytes) : mappedBytes;
        }


        private byte[] decodeZstdStage(byte[] encodedBytes) {
            return ChannelTransportLayerRuntimeConfig.isZstdEnabled() ? this.zstdLayer.decode(encodedBytes) : encodedBytes;
        }

        private KineticMapTableLayer.LayerResult decodeMappingStage(byte[] mappingStageBytes) {
            if (!ChannelTransportLayerRuntimeConfig.isMappingEnabled()) {
                return KineticMapTableLayer.LayerResult.passthrough(mappingStageBytes);
            }
            return this.mapTableLayer.decodeWithTelemetry(mappingStageBytes);
        }

        private ChannelTransportOperationTelemetry toTelemetry(
                KineticMapTableLayer.LayerResult mappingResult,
                int mappingStageBytes
        ) {
            KineticMapTableLayer.MappingTelemetry mappingTelemetry = mappingResult.telemetry();
            return new ChannelTransportOperationTelemetry(
                    ChannelTransportLayerRuntimeConfig.algorithmId(),
                    ChannelTransportLayerRuntimeConfig.isMappingEnabled(),
                    ChannelTransportLayerRuntimeConfig.isZstdEnabled(),
                    mappingStageBytes,
                    mappingTelemetry.literalEntryCount(),
                    mappingTelemetry.exactReferenceCount(),
                    mappingTelemetry.templateReferenceCount(),
                    mappingTelemetry.exactAdditionCount(),
                    mappingTelemetry.templateAdditionCount(),
                    mappingTelemetry.exactRemovalCount(),
                    mappingTelemetry.templateRemovalCount()
            );
        }
    }



    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }
}
