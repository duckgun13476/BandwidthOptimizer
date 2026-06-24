package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchRuntimeConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ChannelTransportCompressionReplaySimulator {

    private static final Comparator<ChannelTransportCapturedPacketSample> SAMPLE_ORDER =
            Comparator.comparingLong(ChannelTransportCapturedPacketSample::capturedAtMillis)
                    .thenComparingLong(ChannelTransportCapturedPacketSample::captureIndex);

    private ChannelTransportCompressionReplaySimulator() {}

    public static ScenarioSimulationResult simulate(
            List<ChannelTransportCapturedPacketSample> capturedPacketSamples,
            ChannelTransportReportScenarioCatalog.ScenarioPreset scenarioPreset
    ) {
        Objects.requireNonNull(capturedPacketSamples, "capturedPacketSamples");
        Objects.requireNonNull(scenarioPreset, "scenarioPreset");
        return ChannelTransportLayerRuntimeConfig.withTemporaryOverride(
                scenarioPreset.toRuntimeOverride(),
                () -> simulateWithinOverride(capturedPacketSamples, scenarioPreset)
        );
    }

    private static ScenarioSimulationResult simulateWithinOverride(
            List<ChannelTransportCapturedPacketSample> capturedPacketSamples,
            ChannelTransportReportScenarioCatalog.ScenarioPreset scenarioPreset
    ) {
        Map<ReplayChannelKey, List<ChannelTransportCapturedPacketSample>> samplesByChannelDirection = new LinkedHashMap<>();
        Set<String> channelIds = new HashSet<>();
        for (ChannelTransportCapturedPacketSample capturedPacketSample : capturedPacketSamples) {
            channelIds.add(capturedPacketSample.channelId());
            samplesByChannelDirection.computeIfAbsent(
                    new ReplayChannelKey(capturedPacketSample.channelId(), capturedPacketSample.direction()),
                    ignored -> new ArrayList<>()
            ).add(capturedPacketSample);
        }

        DirectionMetrics outboundMetrics = DirectionMetrics.empty();
        DirectionMetrics inboundMetrics = DirectionMetrics.empty();
        for (Map.Entry<ReplayChannelKey, List<ChannelTransportCapturedPacketSample>> entry : samplesByChannelDirection.entrySet()) {
            List<ChannelTransportCapturedPacketSample> orderedSamples = new ArrayList<>(entry.getValue());
            orderedSamples.sort(SAMPLE_ORDER);
            DirectionMetrics directionMetrics = scenarioPreset.batchEnabled()
                    ? simulateBatchedDirection(orderedSamples)
                    : simulateSinglePacketDirection(orderedSamples);
            if (entry.getKey().isOutbound()) {
                outboundMetrics = outboundMetrics.plus(directionMetrics);
            } else {
                inboundMetrics = inboundMetrics.plus(directionMetrics);
            }
        }

        return new ScenarioSimulationResult(
                scenarioPreset,
                channelIds.size(),
                outboundMetrics,
                inboundMetrics
        );
    }

    private static DirectionMetrics simulateSinglePacketDirection(List<ChannelTransportCapturedPacketSample> orderedSamples) {
        try (ChannelTransportSession transportSession = new ChannelTransportSession()) {
            DirectionMetrics directionMetrics = DirectionMetrics.empty();
            for (ChannelTransportCapturedPacketSample capturedPacketSample : orderedSamples) {
                ChannelTransportPacketCodec.WrappedTransportFrame wrappedTransportFrame =
                        ChannelTransportPacketCodec.wrapPacket(transportSession, capturedPacketSample.copyPacketBytes());
                if (wrappedTransportFrame == null) {
                    throw new IllegalStateException("Failed to wrap packet for scenario replay: " + capturedPacketSample.packetClassName());
                }
                directionMetrics = directionMetrics.plus(DirectionMetrics.singlePacket(
                        capturedPacketSample.packetBytes().length,
                        wrappedTransportFrame.transportFrameLength()
                ));
            }
            return directionMetrics;
        }
    }

    private static DirectionMetrics simulateBatchedDirection(List<ChannelTransportCapturedPacketSample> orderedSamples) {
        try (ChannelTransportSession transportSession = new ChannelTransportSession()) {
            long batchWindowMillis = ChannelTransportBatchRuntimeConfig.windowMillis();
            DirectionMetrics directionMetrics = DirectionMetrics.empty();
            List<byte[]> pendingPacketBytesList = new ArrayList<>();
            long batchStartAtMillis = Long.MIN_VALUE;

            for (ChannelTransportCapturedPacketSample capturedPacketSample : orderedSamples) {
                if (pendingPacketBytesList.isEmpty()) {
                    batchStartAtMillis = capturedPacketSample.capturedAtMillis();
                    pendingPacketBytesList.add(capturedPacketSample.copyPacketBytes());
                    continue;
                }

                long elapsedMillis = capturedPacketSample.capturedAtMillis() - batchStartAtMillis;
                if (elapsedMillis < batchWindowMillis) {
                    pendingPacketBytesList.add(capturedPacketSample.copyPacketBytes());
                    continue;
                }

                directionMetrics = directionMetrics.plus(flushBatch(transportSession, pendingPacketBytesList));
                pendingPacketBytesList = new ArrayList<>();
                batchStartAtMillis = capturedPacketSample.capturedAtMillis();
                pendingPacketBytesList.add(capturedPacketSample.copyPacketBytes());
            }

            if (!pendingPacketBytesList.isEmpty())
                directionMetrics = directionMetrics.plus(flushBatch(transportSession, pendingPacketBytesList));
            return directionMetrics;
        }
    }

    private static DirectionMetrics flushBatch(ChannelTransportSession transportSession, List<byte[]> pendingPacketBytesList) {
        ChannelTransportPacketCodec.WrappedTransportFrame wrappedTransportFrame =
                ChannelTransportPacketCodec.wrapBatchPackets(transportSession, pendingPacketBytesList);
        if (wrappedTransportFrame == null)
            throw new IllegalStateException("Failed to wrap batch during scenario replay");

        int rawPacketBytes = 0;
        for (byte[] packetBytes : pendingPacketBytesList) {
            rawPacketBytes += packetBytes == null ? 0 : packetBytes.length;
        }
        return new DirectionMetrics(
                pendingPacketBytesList.size(),
                1,
                rawPacketBytes,
                wrappedTransportFrame.transportFrameLength()
        );
    }

    private record ReplayChannelKey(String channelId, String direction) {

        // 这个函数统一判断桶键是否属于出站方向，避免外层循环到处散落字符串比较。
        private boolean isOutbound() {
            return "OUTBOUND".equalsIgnoreCase(this.direction);
        }
    }

    public record ScenarioSimulationResult(
            ChannelTransportReportScenarioCatalog.ScenarioPreset scenarioPreset,
            int channelCount,
            DirectionMetrics outboundMetrics,
            DirectionMetrics inboundMetrics
    ) {

        public long totalPacketCount() {
            return this.outboundMetrics.packetCount() + this.inboundMetrics.packetCount();
        }

        public long totalTransportFrameCount() {
            return this.outboundMetrics.transportFrameCount() + this.inboundMetrics.transportFrameCount();
        }

        public long totalRawPacketBytes() {
            return this.outboundMetrics.rawPacketBytes() + this.inboundMetrics.rawPacketBytes();
        }

        public long totalTransportFrameBytes() {
            return this.outboundMetrics.transportFrameBytes() + this.inboundMetrics.transportFrameBytes();
        }

        public long savedVsRawBytes() {
            return totalRawPacketBytes() - totalTransportFrameBytes();
        }
    }

    public record DirectionMetrics(
            long packetCount,
            long transportFrameCount,
            long rawPacketBytes,
            long transportFrameBytes
    ) {
        private static DirectionMetrics empty() {
            return new DirectionMetrics(0L, 0L, 0L, 0L);
        }

        private static DirectionMetrics singlePacket(long rawPacketBytes, long transportFrameBytes) {
            return new DirectionMetrics(1L, 1L, rawPacketBytes, transportFrameBytes);
        }

        public DirectionMetrics plus(DirectionMetrics other) {
            return new DirectionMetrics(
                    this.packetCount + other.packetCount,
                    this.transportFrameCount + other.transportFrameCount,
                    this.rawPacketBytes + other.rawPacketBytes,
                    this.transportFrameBytes + other.transportFrameBytes
            );
        }
    }
}
