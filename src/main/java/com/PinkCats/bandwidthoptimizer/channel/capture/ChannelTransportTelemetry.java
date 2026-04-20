package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;

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
    private static final LongAdder OUTBOUND_ZSTD_BODY_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_TRANSPORT_FRAME_BYTES = new LongAdder();
    private static final LongAdder OUTBOUND_SHRUNK_FRAME_COUNT = new LongAdder();
    private static final LongAdder OUTBOUND_EXPANDED_FRAME_COUNT = new LongAdder();
    private static final LongAdder INBOUND_TRANSPORT_FRAME_BYTES = new LongAdder();
    private static final LongAdder INBOUND_ZSTD_BODY_BYTES = new LongAdder();
    private static final LongAdder INBOUND_RESTORED_PACKET_BYTES = new LongAdder();


    private ChannelTransportTelemetry() {}


    public static void recordOutboundWrap(String protocolName, ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame) {
        if (wrappedFrame == null) {
            return;
        }

        long wrapCount = OUTBOUND_WRAP_COUNT.incrementAndGet();
        OUTBOUND_RAW_PACKET_BYTES.add(wrappedFrame.originalPacketBytes());
        OUTBOUND_ZSTD_BODY_BYTES.add(wrappedFrame.zstdBodyBytes());
        OUTBOUND_TRANSPORT_FRAME_BYTES.add(wrappedFrame.transportFrameLength());
        updateWrapEffectCounters(wrappedFrame);

        if (wrapCount <= SAMPLE_LOG_LIMIT) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][WrapSample] index={}, protocol={}, rawPacketBytes={}, zstdBodyBytes={}, transportFrameBytes={}, zstdRatio={}, frameRatio={}, frameEffect={}, savedVsRaw={}",
                    wrapCount,
                    protocolName,
                    wrappedFrame.originalPacketBytes(),
                    wrappedFrame.zstdBodyBytes(),
                    wrappedFrame.transportFrameLength(),
                    ratioText(wrappedFrame.zstdBodyBytes(), wrappedFrame.originalPacketBytes()),
                    ratioText(wrappedFrame.transportFrameLength(), wrappedFrame.originalPacketBytes()),
                    effectText(wrappedFrame.transportFrameLength(), wrappedFrame.originalPacketBytes()),
                    wrappedFrame.originalPacketBytes() - wrappedFrame.transportFrameLength()
            );
        }

        if (wrappedFrame.transportFrameLength() < wrappedFrame.originalPacketBytes()) {
            long shrinkSampleCount = OUTBOUND_SHRINK_SAMPLE_COUNT.incrementAndGet();
            if (shrinkSampleCount <= SHRINK_SAMPLE_LOG_LIMIT) {
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport][WrapShrink] index={}, protocol={}, rawPacketBytes={}, transportFrameBytes={}, savedVsRaw={}, frameRatio={}",
                        wrapCount,
                        protocolName,
                        wrappedFrame.originalPacketBytes(),
                        wrappedFrame.transportFrameLength(),
                        wrappedFrame.originalPacketBytes() - wrappedFrame.transportFrameLength(),
                        ratioText(wrappedFrame.transportFrameLength(), wrappedFrame.originalPacketBytes())
                );
            }
        }

        if (wrapCount % SUMMARY_LOG_INTERVAL == 0L) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][WrapSummary] frames={}, shrunkFrames={}, expandedFrames={}, rawPacketBytes={}, zstdBodyBytes={}, transportFrameBytes={}, zstdRatio={}, frameRatio={}, savedVsRaw={}",
                    wrapCount,
                    OUTBOUND_SHRUNK_FRAME_COUNT.sum(),
                    OUTBOUND_EXPANDED_FRAME_COUNT.sum(),
                    OUTBOUND_RAW_PACKET_BYTES.sum(),
                    OUTBOUND_ZSTD_BODY_BYTES.sum(),
                    OUTBOUND_TRANSPORT_FRAME_BYTES.sum(),
                    ratioText(OUTBOUND_ZSTD_BODY_BYTES.sum(), OUTBOUND_RAW_PACKET_BYTES.sum()),
                    ratioText(OUTBOUND_TRANSPORT_FRAME_BYTES.sum(), OUTBOUND_RAW_PACKET_BYTES.sum()),
                    OUTBOUND_RAW_PACKET_BYTES.sum() - OUTBOUND_TRANSPORT_FRAME_BYTES.sum()
            );
        }
    }

    public static void recordInboundUnwrap(String protocolName, ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame) {
        if (unwrappedFrame == null) {
            return;
        }

        long unwrapCount = INBOUND_UNWRAP_COUNT.incrementAndGet();
        INBOUND_TRANSPORT_FRAME_BYTES.add(unwrappedFrame.inboundFrameBytes());
        INBOUND_ZSTD_BODY_BYTES.add(unwrappedFrame.zstdBodyBytes());
        INBOUND_RESTORED_PACKET_BYTES.add(unwrappedFrame.restoredPacketBytes().length);

        if (unwrapCount <= SAMPLE_LOG_LIMIT) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][UnwrapSample] index={}, protocol={}, inboundFrameBytes={}, zstdBodyBytes={}, restoredPacketBytes={}, frameRatio={}, bodyRatio={}",
                    unwrapCount,
                    protocolName,
                    unwrappedFrame.inboundFrameBytes(),
                    unwrappedFrame.zstdBodyBytes(),
                    unwrappedFrame.restoredPacketBytes().length,
                    ratioText(unwrappedFrame.inboundFrameBytes(), unwrappedFrame.restoredPacketBytes().length),
                    ratioText(unwrappedFrame.zstdBodyBytes(), unwrappedFrame.restoredPacketBytes().length)
            );
        }

        if (unwrapCount % SUMMARY_LOG_INTERVAL == 0L) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][UnwrapSummary] frames={}, inboundFrameBytes={}, zstdBodyBytes={}, restoredPacketBytes={}, frameRatio={}, bodyRatio={}",
                    unwrapCount,
                    INBOUND_TRANSPORT_FRAME_BYTES.sum(),
                    INBOUND_ZSTD_BODY_BYTES.sum(),
                    INBOUND_RESTORED_PACKET_BYTES.sum(),
                    ratioText(INBOUND_TRANSPORT_FRAME_BYTES.sum(), INBOUND_RESTORED_PACKET_BYTES.sum()),
                    ratioText(INBOUND_ZSTD_BODY_BYTES.sum(), INBOUND_RESTORED_PACKET_BYTES.sum())
            );
        }
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
