package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class ChannelTransportTelemetry {

    private static final long SAMPLE_LOG_LIMIT = 8L;
    private static final long SHRINK_SAMPLE_LOG_LIMIT = 8L;
    private static final long SUMMARY_LOG_INTERVAL = 512L;
    private static final String TELEMETRY_DUMP_FILE_NAME_PROPERTY =
            Config.RuntimeProperty.Transport.TELEMETRY_DUMP_FILE_NAME;
    private static final long TELEMETRY_DUMP_INTERVAL_MILLIS = 1000L;
    private static final Object TELEMETRY_DUMP_LOCK = new Object();

    private static final AtomicLong OUTBOUND_WRAP_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_UNWRAP_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_SHRINK_SAMPLE_COUNT = new AtomicLong();
    private static final AtomicLong LAST_TELEMETRY_DUMP_AT_MILLIS = new AtomicLong();
    private static final AtomicLong LAST_ACTIVITY_AT_MILLIS = new AtomicLong();

    private static final LongAdder OUTBOUND_RAW_PACKET_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_MAPPING_STAGE_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_TRANSPORT_BODY_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_TRANSPORT_FRAME_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_ORIGINAL_PACKET_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_SHRUNK_FRAME_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXPANDED_FRAME_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_LITERAL_ENTRY_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXACT_REFERENCE_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_TEMPLATE_REFERENCE_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXACT_ADDITION_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_TEMPLATE_ADDITION_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXACT_REMOVAL_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_TEMPLATE_REMOVAL_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_BYPASS_PACKET_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_BYPASS_PACKET_BYTES = new LongAdder();

    private static final LongAdder INBOUND_TRANSPORT_FRAME_BYTES = new LongAdder();
    private static final LongAdder INBOUND_TRANSPORT_BODY_BYTES = new LongAdder();
    private static final LongAdder INBOUND_MAPPING_STAGE_BYTES = new LongAdder();
    private static final LongAdder INBOUND_RESTORED_PACKET_BYTES = new LongAdder();
    private static final LongAdder INBOUND_RESTORED_PACKET_COUNT = new LongAdder();
    private static final LongAdder INBOUND_LITERAL_ENTRY_COUNT = new LongAdder();
    private static final LongAdder INBOUND_EXACT_REFERENCE_COUNT = new LongAdder();
    private static final LongAdder INBOUND_TEMPLATE_REFERENCE_COUNT = new LongAdder();
    private static final LongAdder INBOUND_EXACT_ADDITION_COUNT = new LongAdder();
    private static final LongAdder INBOUND_TEMPLATE_ADDITION_COUNT = new LongAdder();
    private static final LongAdder INBOUND_EXACT_REMOVAL_COUNT = new LongAdder();
    private static final LongAdder INBOUND_TEMPLATE_REMOVAL_COUNT = new LongAdder();
    private static final LongAdder INBOUND_BYPASS_PACKET_COUNT = new LongAdder();
    private static final LongAdder INBOUND_BYPASS_PACKET_BYTES = new LongAdder();

    private ChannelTransportTelemetry() {}

    // This function records one outbound transport wrap, including stage bytes and mapping counters.
    public static void recordOutboundWrap(String protocolName, ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame) {
        if (wrappedFrame == null) {
            return;
        }

        LAST_ACTIVITY_AT_MILLIS.set(System.currentTimeMillis());
        ChannelTransportOperationTelemetry telemetry =
                safeTelemetry(wrappedFrame.telemetry(), wrappedFrame.originalPacketBytes());
        long wrapCount = OUTBOUND_WRAP_COUNT.incrementAndGet();
        OUTBOUND_RAW_PACKET_BYTES.add(wrappedFrame.originalPacketBytes());
        OUTBOUND_ORIGINAL_PACKET_COUNT.add(wrappedFrame.originalPacketCount());
        OUTBOUND_MAPPING_STAGE_BYTES.add(telemetry.mappingStageBytes());
        OUTBOUND_TRANSPORT_BODY_BYTES.add(wrappedFrame.zstdBodyBytes());
        OUTBOUND_TRANSPORT_FRAME_BYTES.add(wrappedFrame.transportFrameLength());
        updateWrapEffectCounters(wrappedFrame);
        updateMappingCounters(
                telemetry,
                OUTBOUND_LITERAL_ENTRY_COUNT,
                OUTBOUND_EXACT_REFERENCE_COUNT,
                OUTBOUND_TEMPLATE_REFERENCE_COUNT,
                OUTBOUND_EXACT_ADDITION_COUNT,
                OUTBOUND_TEMPLATE_ADDITION_COUNT,
                OUTBOUND_EXACT_REMOVAL_COUNT,
                OUTBOUND_TEMPLATE_REMOVAL_COUNT
        );

        if (wrapCount <= SAMPLE_LOG_LIMIT) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][WrapSample] index={}, protocol={}, frameKind={}, packetCount={}, algorithm={}, rawPacketBytes={}, mappingBytes={}, transportBodyBytes={}, transportFrameBytes={}, mapRatio={}, zstdVsMapRatio={}, frameRatio={}, entryKind={}, exactAdds={}, templateAdds={}, exactRemovals={}, templateRemovals={}, frameEffect={}, savedVsRaw={}",
                    wrapCount,
                    protocolName,
                    wrappedFrame.frameKind(),
                    wrappedFrame.originalPacketCount(),
                    telemetry.algorithmId(),
                    wrappedFrame.originalPacketBytes(),
                    telemetry.mappingStageBytes(),
                    wrappedFrame.zstdBodyBytes(),
                    wrappedFrame.transportFrameLength(),
                    ratioText(telemetry.mappingStageBytes(), wrappedFrame.originalPacketBytes()),
                    ratioText(wrappedFrame.zstdBodyBytes(), telemetry.mappingStageBytes()),
                    ratioText(wrappedFrame.transportFrameLength(), wrappedFrame.originalPacketBytes()),
                    entryKindText(telemetry),
                    telemetry.exactAdditionCount(),
                    telemetry.templateAdditionCount(),
                    telemetry.exactRemovalCount(),
                    telemetry.templateRemovalCount(),
                    effectText(wrappedFrame.transportFrameLength(), wrappedFrame.originalPacketBytes()),
                    wrappedFrame.originalPacketBytes() - wrappedFrame.transportFrameLength()
            );
        }

        if (wrappedFrame.transportFrameLength() < wrappedFrame.originalPacketBytes()) {
            long shrinkSampleCount = OUTBOUND_SHRINK_SAMPLE_COUNT.incrementAndGet();
            if (shrinkSampleCount <= SHRINK_SAMPLE_LOG_LIMIT) {
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport][WrapShrink] index={}, protocol={}, frameKind={}, packetCount={}, algorithm={}, rawPacketBytes={}, mappingBytes={}, transportBodyBytes={}, transportFrameBytes={}, mapRatio={}, zstdVsMapRatio={}, frameRatio={}, entryKind={}, savedVsRaw={}",
                        wrapCount,
                        protocolName,
                        wrappedFrame.frameKind(),
                        wrappedFrame.originalPacketCount(),
                        telemetry.algorithmId(),
                        wrappedFrame.originalPacketBytes(),
                        telemetry.mappingStageBytes(),
                        wrappedFrame.zstdBodyBytes(),
                        wrappedFrame.transportFrameLength(),
                        ratioText(telemetry.mappingStageBytes(), wrappedFrame.originalPacketBytes()),
                        ratioText(wrappedFrame.zstdBodyBytes(), telemetry.mappingStageBytes()),
                        ratioText(wrappedFrame.transportFrameLength(), wrappedFrame.originalPacketBytes()),
                        entryKindText(telemetry),
                        wrappedFrame.originalPacketBytes() - wrappedFrame.transportFrameLength()
                );
            }
        }

        if (wrapCount % SUMMARY_LOG_INTERVAL == 0L) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][WrapSummary] algorithm={}, frames={}, rawPackets={}, shrunkFrames={}, expandedFrames={}, rawPacketBytes={}, mappingBytes={}, transportBodyBytes={}, transportFrameBytes={}, mapRatio={}, zstdVsMapRatio={}, frameRatio={}, savedVsRaw={}, mapLiterals={}, mapExactRefs={}, mapTemplateRefs={}, mapExactAdds={}, mapTemplateAdds={}, mapExactRemovals={}, mapTemplateRemovals={}",
                    telemetry.algorithmId(),
                    wrapCount,
                    OUTBOUND_ORIGINAL_PACKET_COUNT.sum(),
                    OUTBOUND_SHRUNK_FRAME_COUNT.sum(),
                    OUTBOUND_EXPANDED_FRAME_COUNT.sum(),
                    OUTBOUND_RAW_PACKET_BYTES.sum(),
                    OUTBOUND_MAPPING_STAGE_BYTES.sum(),
                    OUTBOUND_TRANSPORT_BODY_BYTES.sum(),
                    OUTBOUND_TRANSPORT_FRAME_BYTES.sum(),
                    ratioText(OUTBOUND_MAPPING_STAGE_BYTES.sum(), OUTBOUND_RAW_PACKET_BYTES.sum()),
                    ratioText(OUTBOUND_TRANSPORT_BODY_BYTES.sum(), OUTBOUND_MAPPING_STAGE_BYTES.sum()),
                    ratioText(OUTBOUND_TRANSPORT_FRAME_BYTES.sum(), OUTBOUND_RAW_PACKET_BYTES.sum()),
                    OUTBOUND_RAW_PACKET_BYTES.sum() - OUTBOUND_TRANSPORT_FRAME_BYTES.sum(),
                    OUTBOUND_LITERAL_ENTRY_COUNT.sum(),
                    OUTBOUND_EXACT_REFERENCE_COUNT.sum(),
                    OUTBOUND_TEMPLATE_REFERENCE_COUNT.sum(),
                    OUTBOUND_EXACT_ADDITION_COUNT.sum(),
                    OUTBOUND_TEMPLATE_ADDITION_COUNT.sum(),
                    OUTBOUND_EXACT_REMOVAL_COUNT.sum(),
                    OUTBOUND_TEMPLATE_REMOVAL_COUNT.sum()
            );
        }

        maybeWriteTelemetryDumpFile();
    }

    public static void recordInboundUnwrap(String protocolName, ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame) { // 这个函数负责记录一帧入站 transport 解包统计，并刷新 HUD 会读取的累计计数。
        if (unwrappedFrame == null) {
            return;
        }

        LAST_ACTIVITY_AT_MILLIS.set(System.currentTimeMillis());
        ChannelTransportOperationTelemetry telemetry =
                safeTelemetry(unwrappedFrame.telemetry(), unwrappedFrame.zstdBodyBytes());
        long unwrapCount = INBOUND_UNWRAP_COUNT.incrementAndGet();
        INBOUND_TRANSPORT_FRAME_BYTES.add(unwrappedFrame.inboundFrameBytes());
        INBOUND_TRANSPORT_BODY_BYTES.add(unwrappedFrame.zstdBodyBytes());
        INBOUND_MAPPING_STAGE_BYTES.add(telemetry.mappingStageBytes());
        INBOUND_RESTORED_PACKET_BYTES.add(unwrappedFrame.restoredPacketBytes());
        INBOUND_RESTORED_PACKET_COUNT.add(unwrappedFrame.restoredPacketCount());
        updateMappingCounters(
                telemetry,
                INBOUND_LITERAL_ENTRY_COUNT,
                INBOUND_EXACT_REFERENCE_COUNT,
                INBOUND_TEMPLATE_REFERENCE_COUNT,
                INBOUND_EXACT_ADDITION_COUNT,
                INBOUND_TEMPLATE_ADDITION_COUNT,
                INBOUND_EXACT_REMOVAL_COUNT,
                INBOUND_TEMPLATE_REMOVAL_COUNT
        );

        if (unwrapCount <= SAMPLE_LOG_LIMIT) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][UnwrapSample] index={}, protocol={}, frameKind={}, packetCount={}, algorithm={}, inboundFrameBytes={}, transportBodyBytes={}, mappingBytes={}, restoredPacketBytes={}, frameRatio={}, bodyRatio={}, mapRatio={}, bodyVsMapRatio={}, entryKind={}, exactAdds={}, templateAdds={}, exactRemovals={}, templateRemovals={}",
                    unwrapCount,
                    protocolName,
                    unwrappedFrame.frameKind(),
                    unwrappedFrame.restoredPacketCount(),
                    telemetry.algorithmId(),
                    unwrappedFrame.inboundFrameBytes(),
                    unwrappedFrame.zstdBodyBytes(),
                    telemetry.mappingStageBytes(),
                    unwrappedFrame.restoredPacketBytes(),
                    ratioText(unwrappedFrame.inboundFrameBytes(), unwrappedFrame.restoredPacketBytes()),
                    ratioText(unwrappedFrame.zstdBodyBytes(), unwrappedFrame.restoredPacketBytes()),
                    ratioText(telemetry.mappingStageBytes(), unwrappedFrame.restoredPacketBytes()),
                    ratioText(unwrappedFrame.zstdBodyBytes(), telemetry.mappingStageBytes()),
                    entryKindText(telemetry),
                    telemetry.exactAdditionCount(),
                    telemetry.templateAdditionCount(),
                    telemetry.exactRemovalCount(),
                    telemetry.templateRemovalCount()
            );
        }

        if (unwrapCount % SUMMARY_LOG_INTERVAL == 0L) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][UnwrapSummary] algorithm={}, frames={}, restoredPackets={}, inboundFrameBytes={}, transportBodyBytes={}, mappingBytes={}, restoredPacketBytes={}, frameRatio={}, bodyRatio={}, mapRatio={}, bodyVsMapRatio={}, mapLiterals={}, mapExactRefs={}, mapTemplateRefs={}, mapExactAdds={}, mapTemplateAdds={}, mapExactRemovals={}, mapTemplateRemovals={}",
                    telemetry.algorithmId(),
                    unwrapCount,
                    INBOUND_RESTORED_PACKET_COUNT.sum(),
                    INBOUND_TRANSPORT_FRAME_BYTES.sum(),
                    INBOUND_TRANSPORT_BODY_BYTES.sum(),
                    INBOUND_MAPPING_STAGE_BYTES.sum(),
                    INBOUND_RESTORED_PACKET_BYTES.sum(),
                    ratioText(INBOUND_TRANSPORT_FRAME_BYTES.sum(), INBOUND_RESTORED_PACKET_BYTES.sum()),
                    ratioText(INBOUND_TRANSPORT_BODY_BYTES.sum(), INBOUND_RESTORED_PACKET_BYTES.sum()),
                    ratioText(INBOUND_MAPPING_STAGE_BYTES.sum(), INBOUND_RESTORED_PACKET_BYTES.sum()),
                    ratioText(INBOUND_TRANSPORT_BODY_BYTES.sum(), INBOUND_MAPPING_STAGE_BYTES.sum()),
                    INBOUND_LITERAL_ENTRY_COUNT.sum(),
                    INBOUND_EXACT_REFERENCE_COUNT.sum(),
                    INBOUND_TEMPLATE_REFERENCE_COUNT.sum(),
                    INBOUND_EXACT_ADDITION_COUNT.sum(),
                    INBOUND_TEMPLATE_ADDITION_COUNT.sum(),
                    INBOUND_EXACT_REMOVAL_COUNT.sum(),
                    INBOUND_TEMPLATE_REMOVAL_COUNT.sum()
            );
        }

        maybeWriteTelemetryDumpFile();
    }


    public static void recordOutboundBypass(String protocolName, int packetBytes) {
        LAST_ACTIVITY_AT_MILLIS.set(System.currentTimeMillis());
        OUTBOUND_BYPASS_PACKET_COUNT.increment();
        OUTBOUND_BYPASS_PACKET_BYTES.add(Math.max(packetBytes, 0));
        maybeWriteTelemetryDumpFile();
    }


    public static void recordInboundBypass(String protocolName, int packetBytes, int decodedPacketCount) {
        LAST_ACTIVITY_AT_MILLIS.set(System.currentTimeMillis());
        INBOUND_BYPASS_PACKET_COUNT.add(Math.max(decodedPacketCount, 1));
        INBOUND_BYPASS_PACKET_BYTES.add(Math.max(packetBytes, 0));
        maybeWriteTelemetryDumpFile();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                LAST_ACTIVITY_AT_MILLIS.get(),
                ChannelTransportLayerRuntimeConfig.algorithmId().toString(),
                ChannelTransportLayerRuntimeConfig.isMappingEnabled(),
                ChannelTransportLayerRuntimeConfig.isZstdEnabled(),
                ChannelTransportLayerRuntimeConfig.isPacketIdMappingEnabled(),
                new DirectionSnapshot(
                        OUTBOUND_WRAP_COUNT.get(),
                        OUTBOUND_ORIGINAL_PACKET_COUNT.sum(),
                        OUTBOUND_RAW_PACKET_BYTES.sum(),
                        OUTBOUND_MAPPING_STAGE_BYTES.sum(),
                        OUTBOUND_TRANSPORT_BODY_BYTES.sum(),
                        OUTBOUND_TRANSPORT_FRAME_BYTES.sum(),
                        OUTBOUND_SHRUNK_FRAME_COUNT.sum(),
                        OUTBOUND_EXPANDED_FRAME_COUNT.sum(),
                        OUTBOUND_LITERAL_ENTRY_COUNT.sum(),
                        OUTBOUND_EXACT_REFERENCE_COUNT.sum(),
                        OUTBOUND_TEMPLATE_REFERENCE_COUNT.sum(),
                        OUTBOUND_EXACT_ADDITION_COUNT.sum(),
                        OUTBOUND_TEMPLATE_ADDITION_COUNT.sum(),
                        OUTBOUND_EXACT_REMOVAL_COUNT.sum(),
                        OUTBOUND_TEMPLATE_REMOVAL_COUNT.sum(),
                        OUTBOUND_BYPASS_PACKET_COUNT.sum(),
                        OUTBOUND_BYPASS_PACKET_BYTES.sum()
                ),
                new DirectionSnapshot(
                        INBOUND_UNWRAP_COUNT.get(),
                        INBOUND_RESTORED_PACKET_COUNT.sum(),
                        INBOUND_RESTORED_PACKET_BYTES.sum(),
                        INBOUND_MAPPING_STAGE_BYTES.sum(),
                        INBOUND_TRANSPORT_BODY_BYTES.sum(),
                        INBOUND_TRANSPORT_FRAME_BYTES.sum(),
                        0L,
                        0L,
                        INBOUND_LITERAL_ENTRY_COUNT.sum(),
                        INBOUND_EXACT_REFERENCE_COUNT.sum(),
                        INBOUND_TEMPLATE_REFERENCE_COUNT.sum(),
                        INBOUND_EXACT_ADDITION_COUNT.sum(),
                        INBOUND_TEMPLATE_ADDITION_COUNT.sum(),
                        INBOUND_EXACT_REMOVAL_COUNT.sum(),
                        INBOUND_TEMPLATE_REMOVAL_COUNT.sum(),
                        INBOUND_BYPASS_PACKET_COUNT.sum(),
                        INBOUND_BYPASS_PACKET_BYTES.sum()
                )
        );
    }

    private static ChannelTransportOperationTelemetry safeTelemetry(
            ChannelTransportOperationTelemetry telemetry,
            int fallbackMappingStageBytes
    ) {
        if (telemetry != null) {
            return telemetry;
        }
        return ChannelTransportOperationTelemetry.passthrough(
                ChannelTransportLayerRuntimeConfig.algorithmId(),
                ChannelTransportLayerRuntimeConfig.isMappingEnabled(),
                ChannelTransportLayerRuntimeConfig.isZstdEnabled(),
                fallbackMappingStageBytes
        );
    }

    private static void updateMappingCounters(
            ChannelTransportOperationTelemetry telemetry,
            LongAdder literalEntryCount,
            LongAdder exactReferenceCount,
            LongAdder templateReferenceCount,
            LongAdder exactAdditionCount,
            LongAdder templateAdditionCount,
            LongAdder exactRemovalCount,
            LongAdder templateRemovalCount
    ) {
        literalEntryCount.add(telemetry.literalEntryCount());
        exactReferenceCount.add(telemetry.exactReferenceCount());
        templateReferenceCount.add(telemetry.templateReferenceCount());
        exactAdditionCount.add(telemetry.exactAdditionCount());
        templateAdditionCount.add(telemetry.templateAdditionCount());
        exactRemovalCount.add(telemetry.exactRemovalCount());
        templateRemovalCount.add(telemetry.templateRemovalCount());
    }

    private static String entryKindText(ChannelTransportOperationTelemetry telemetry) {
        if (telemetry.templateReferenceCount() > 0) {
            return "template_reference";
        }
        if (telemetry.exactReferenceCount() > 0) {
            return "exact_reference";
        }
        if (telemetry.literalEntryCount() > 0) {
            return "literal";
        }
        return "none";
    }

    private static String ratioText(long currentBytes, long baselineBytes) {
        if (baselineBytes <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3fx", (double) currentBytes / (double) baselineBytes);
    }

    private static void updateWrapEffectCounters(ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame) {
        if (wrappedFrame.transportFrameLength() < wrappedFrame.originalPacketBytes()) {
            OUTBOUND_SHRUNK_FRAME_COUNT.increment();
            return;
        }
        if (wrappedFrame.transportFrameLength() > wrappedFrame.originalPacketBytes()) {
            OUTBOUND_EXPANDED_FRAME_COUNT.increment();
        }
    }

    private static String effectText(long currentBytes, long baselineBytes) {
        if (currentBytes < baselineBytes) {
            return "shrink";
        }
        if (currentBytes > baselineBytes) {
            return "expand";
        }
        return "equal";
    }

    private static void maybeWriteTelemetryDumpFile() {
        String dumpFileName = System.getProperty(TELEMETRY_DUMP_FILE_NAME_PROPERTY);
        if (dumpFileName == null || dumpFileName.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        long lastDumpAt = LAST_TELEMETRY_DUMP_AT_MILLIS.get();
        if (now - lastDumpAt < TELEMETRY_DUMP_INTERVAL_MILLIS) {
            return;
        }

        synchronized (TELEMETRY_DUMP_LOCK) {
            long refreshedLastDumpAt = LAST_TELEMETRY_DUMP_AT_MILLIS.get();
            long refreshedNow = System.currentTimeMillis();
            if (refreshedNow - refreshedLastDumpAt < TELEMETRY_DUMP_INTERVAL_MILLIS) {
                return;
            }

            try {
                writeTelemetryDumpFile(Path.of(dumpFileName));
                LAST_TELEMETRY_DUMP_AT_MILLIS.set(refreshedNow);
            } catch (IOException exception) {
                Bandwidthoptimizer.LOGGER.warn("[Transport] Failed to write telemetry dump file {}", dumpFileName, exception);
            }
        }
    }

    private static void writeTelemetryDumpFile(Path dumpFilePath) throws IOException {
        Path parentPath = dumpFilePath.getParent();
        if (parentPath != null) {
            Files.createDirectories(parentPath);
        }
        Files.writeString(dumpFilePath, buildTelemetryDumpText(), StandardCharsets.UTF_8);
    }

    private static String buildTelemetryDumpText() {
        StringBuilder builder = new StringBuilder(512);
        appendDumpLine(builder, "updatedAtMillis", Long.toString(System.currentTimeMillis()));
        appendDumpLine(builder, "algorithmId", ChannelTransportLayerRuntimeConfig.algorithmId().toString());
        appendDumpLine(builder, "mappingEnabled", Boolean.toString(ChannelTransportLayerRuntimeConfig.isMappingEnabled()));
        appendDumpLine(builder, "zstdEnabled", Boolean.toString(ChannelTransportLayerRuntimeConfig.isZstdEnabled()));
        appendDumpLine(builder, "packetIdMappingEnabled", Boolean.toString(ChannelTransportLayerRuntimeConfig.isPacketIdMappingEnabled()));
        appendDumpLine(builder, "outbound.frames", Long.toString(OUTBOUND_WRAP_COUNT.get()));
        appendDumpLine(builder, "outbound.rawPackets", Long.toString(OUTBOUND_ORIGINAL_PACKET_COUNT.sum()));
        appendDumpLine(builder, "outbound.rawPacketBytes", Long.toString(OUTBOUND_RAW_PACKET_BYTES.sum()));
        appendDumpLine(builder, "outbound.mappingBytes", Long.toString(OUTBOUND_MAPPING_STAGE_BYTES.sum()));
        appendDumpLine(builder, "outbound.transportBodyBytes", Long.toString(OUTBOUND_TRANSPORT_BODY_BYTES.sum()));
        appendDumpLine(builder, "outbound.transportFrameBytes", Long.toString(OUTBOUND_TRANSPORT_FRAME_BYTES.sum()));
        appendDumpLine(builder, "outbound.frameRatio", ratioText(OUTBOUND_TRANSPORT_FRAME_BYTES.sum(), OUTBOUND_RAW_PACKET_BYTES.sum()));
        appendDumpLine(builder, "outbound.savedVsRaw", Long.toString(OUTBOUND_RAW_PACKET_BYTES.sum() - OUTBOUND_TRANSPORT_FRAME_BYTES.sum()));
        appendDumpLine(builder, "outbound.bypassPackets", Long.toString(OUTBOUND_BYPASS_PACKET_COUNT.sum()));
        appendDumpLine(builder, "outbound.bypassPacketBytes", Long.toString(OUTBOUND_BYPASS_PACKET_BYTES.sum()));
        appendDumpLine(builder, "inbound.frames", Long.toString(INBOUND_UNWRAP_COUNT.get()));
        appendDumpLine(builder, "inbound.restoredPackets", Long.toString(INBOUND_RESTORED_PACKET_COUNT.sum()));
        appendDumpLine(builder, "inbound.inboundFrameBytes", Long.toString(INBOUND_TRANSPORT_FRAME_BYTES.sum()));
        appendDumpLine(builder, "inbound.transportBodyBytes", Long.toString(INBOUND_TRANSPORT_BODY_BYTES.sum()));
        appendDumpLine(builder, "inbound.mappingBytes", Long.toString(INBOUND_MAPPING_STAGE_BYTES.sum()));
        appendDumpLine(builder, "inbound.restoredPacketBytes", Long.toString(INBOUND_RESTORED_PACKET_BYTES.sum()));
        appendDumpLine(builder, "inbound.frameRatio", ratioText(INBOUND_TRANSPORT_FRAME_BYTES.sum(), INBOUND_RESTORED_PACKET_BYTES.sum()));
        appendDumpLine(builder, "inbound.bypassPackets", Long.toString(INBOUND_BYPASS_PACKET_COUNT.sum()));
        appendDumpLine(builder, "inbound.bypassPacketBytes", Long.toString(INBOUND_BYPASS_PACKET_BYTES.sum()));
        return builder.toString();
    }

    private static void appendDumpLine(StringBuilder builder, String key, String value) {
        builder.append(key).append('=').append(value).append('\n');
    }

    public record Snapshot(
            long lastActivityAtMillis,
            String algorithmId,
            boolean mappingEnabled,
            boolean zstdEnabled,
            boolean packetIdMappingEnabled,
            DirectionSnapshot outbound,
            DirectionSnapshot inbound
    ) {
    }

    public record DirectionSnapshot(
            long frameCount,
            long packetCount,
            long baselineBytes,
            long mappingStageBytes,
            long transportBodyBytes,
            long transportFrameBytes,
            long shrunkFrameCount,
            long expandedFrameCount,
            long literalEntryCount,
            long exactReferenceCount,
            long templateReferenceCount,
            long exactAdditionCount,
            long templateAdditionCount,
            long exactRemovalCount,
            long templateRemovalCount,
            long bypassPacketCount,
            long bypassPacketBytes
    ) {
    }
}
