package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class ChannelTransportTelemetry {

    private static final long SAMPLE_LOG_LIMIT = 8L;
    private static final long SHRINK_SAMPLE_LOG_LIMIT = 8L;
    private static final long SUMMARY_LOG_INTERVAL = 512L;

    private static final AtomicLong OUTBOUND_WRAP_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_UNWRAP_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_SHRINK_SAMPLE_COUNT = new AtomicLong();

    private static final LongAdder OUTBOUND_RAW_PACKET_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_MAPPING_STAGE_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_TRANSPORT_BODY_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_TRANSPORT_FRAME_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_SHRUNK_FRAME_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXPANDED_FRAME_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_LITERAL_ENTRY_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXACT_REFERENCE_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_TEMPLATE_REFERENCE_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXACT_ADDITION_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_TEMPLATE_ADDITION_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXACT_REMOVAL_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_TEMPLATE_REMOVAL_COUNT = new LongAdder();

    private static final LongAdder INBOUND_TRANSPORT_FRAME_BYTES = new LongAdder();
    private static final LongAdder INBOUND_TRANSPORT_BODY_BYTES = new LongAdder();
    private static final LongAdder INBOUND_MAPPING_STAGE_BYTES = new LongAdder();
    private static final LongAdder INBOUND_RESTORED_PACKET_BYTES = new LongAdder();
    private static final LongAdder INBOUND_LITERAL_ENTRY_COUNT = new LongAdder();
    private static final LongAdder INBOUND_EXACT_REFERENCE_COUNT = new LongAdder();
    private static final LongAdder INBOUND_TEMPLATE_REFERENCE_COUNT = new LongAdder();
    private static final LongAdder INBOUND_EXACT_ADDITION_COUNT = new LongAdder();
    private static final LongAdder INBOUND_TEMPLATE_ADDITION_COUNT = new LongAdder();
    private static final LongAdder INBOUND_EXACT_REMOVAL_COUNT = new LongAdder();
    private static final LongAdder INBOUND_TEMPLATE_REMOVAL_COUNT = new LongAdder();

    private ChannelTransportTelemetry() {}

    // This function records one outbound transport wrap, including stage bytes and mapping counters.
    public static void recordOutboundWrap(String protocolName, ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame) {
        if (wrappedFrame == null) {
            return;
        }

        ChannelTransportOperationTelemetry telemetry =
                safeTelemetry(wrappedFrame.telemetry(), wrappedFrame.originalPacketBytes());
        long wrapCount = OUTBOUND_WRAP_COUNT.incrementAndGet();
        OUTBOUND_RAW_PACKET_BYTES.add(wrappedFrame.originalPacketBytes());
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
                    "[Transport][WrapSample] index={}, protocol={}, algorithm={}, rawPacketBytes={}, mappingBytes={}, transportBodyBytes={}, transportFrameBytes={}, mapRatio={}, zstdVsMapRatio={}, frameRatio={}, entryKind={}, exactAdds={}, templateAdds={}, exactRemovals={}, templateRemovals={}, frameEffect={}, savedVsRaw={}",
                    wrapCount,
                    protocolName,
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
                        "[Transport][WrapShrink] index={}, protocol={}, algorithm={}, rawPacketBytes={}, mappingBytes={}, transportBodyBytes={}, transportFrameBytes={}, mapRatio={}, zstdVsMapRatio={}, frameRatio={}, entryKind={}, savedVsRaw={}",
                        wrapCount,
                        protocolName,
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
                    "[Transport][WrapSummary] algorithm={}, frames={}, shrunkFrames={}, expandedFrames={}, rawPacketBytes={}, mappingBytes={}, transportBodyBytes={}, transportFrameBytes={}, mapRatio={}, zstdVsMapRatio={}, frameRatio={}, savedVsRaw={}, mapLiterals={}, mapExactRefs={}, mapTemplateRefs={}, mapExactAdds={}, mapTemplateAdds={}, mapExactRemovals={}, mapTemplateRemovals={}",
                    telemetry.algorithmId(),
                    wrapCount,
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
    }

    public static void recordInboundUnwrap(String protocolName, ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame) {
        if (unwrappedFrame == null) {
            return;
        }

        ChannelTransportOperationTelemetry telemetry =
                safeTelemetry(unwrappedFrame.telemetry(), unwrappedFrame.zstdBodyBytes());
        long unwrapCount = INBOUND_UNWRAP_COUNT.incrementAndGet();
        INBOUND_TRANSPORT_FRAME_BYTES.add(unwrappedFrame.inboundFrameBytes());
        INBOUND_TRANSPORT_BODY_BYTES.add(unwrappedFrame.zstdBodyBytes());
        INBOUND_MAPPING_STAGE_BYTES.add(telemetry.mappingStageBytes());
        INBOUND_RESTORED_PACKET_BYTES.add(unwrappedFrame.restoredPacketBytes().length);
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
                    "[Transport][UnwrapSample] index={}, protocol={}, algorithm={}, inboundFrameBytes={}, transportBodyBytes={}, mappingBytes={}, restoredPacketBytes={}, frameRatio={}, bodyRatio={}, mapRatio={}, bodyVsMapRatio={}, entryKind={}, exactAdds={}, templateAdds={}, exactRemovals={}, templateRemovals={}",
                    unwrapCount,
                    protocolName,
                    telemetry.algorithmId(),
                    unwrappedFrame.inboundFrameBytes(),
                    unwrappedFrame.zstdBodyBytes(),
                    telemetry.mappingStageBytes(),
                    unwrappedFrame.restoredPacketBytes().length,
                    ratioText(unwrappedFrame.inboundFrameBytes(), unwrappedFrame.restoredPacketBytes().length),
                    ratioText(unwrappedFrame.zstdBodyBytes(), unwrappedFrame.restoredPacketBytes().length),
                    ratioText(telemetry.mappingStageBytes(), unwrappedFrame.restoredPacketBytes().length),
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
                    "[Transport][UnwrapSummary] algorithm={}, frames={}, inboundFrameBytes={}, transportBodyBytes={}, mappingBytes={}, restoredPacketBytes={}, frameRatio={}, bodyRatio={}, mapRatio={}, bodyVsMapRatio={}, mapLiterals={}, mapExactRefs={}, mapTemplateRefs={}, mapExactAdds={}, mapTemplateAdds={}, mapExactRemovals={}, mapTemplateRemovals={}",
                    telemetry.algorithmId(),
                    unwrapCount,
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
}
