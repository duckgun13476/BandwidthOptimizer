package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.PinkCats.bandwidthoptimizer.channel.math.format.ratioText;


public final class ChannelTransportRuntimeGuard {

    private static final String EXPERIMENTAL_TRANSPORT_PROPERTY = "bandwidthoptimizer.experimentalTransport";

    private static volatile boolean initialized;
    private static volatile boolean transportAvailable;
    private static volatile String unavailableReason = "not initialized";


    private ChannelTransportRuntimeGuard() {}

    // Guard for Channel
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        if (!isExperimentalTransportEnabled()) {
            transportAvailable = false;
            unavailableReason = "disabled by default property";
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport] Experimental transparent transport is disabled by default. Set -D{}=true to enable it.",
                    EXPERIMENTAL_TRANSPORT_PROPERTY
            );
            return;
        }

        try {
            byte[] probeBytes = "bandwidthoptimizer-zstd-probe".getBytes(StandardCharsets.US_ASCII);
            ChannelTransportSession probeSession = new ChannelTransportSession();
            byte[] encodedBytes = probeSession.encodeSinglePacket(probeBytes);
            byte[] restoredBytes = probeSession.decodeSinglePacket(encodedBytes);
            if (!Arrays.equals(probeBytes, restoredBytes)) {
                throw new IllegalStateException("Probe round trip mismatch");
            }

            transportAvailable = true;
            unavailableReason = "";
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport] Zstd runtime ready. probeRawBytes={}, probeTransportBodyBytes={}, ratio={}",
                    probeBytes.length,
                    encodedBytes.length,
                    ratioText(encodedBytes.length, probeBytes.length)
            );
        } catch (Throwable throwable) {
            transportAvailable = false;
            unavailableReason = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            Bandwidthoptimizer.LOGGER.warn(
                    "[Transport] Zstd runtime unavailable, transparent transport disabled. reason={}",
                    unavailableReason,
                    throwable
            );
        }
    }


    public static boolean isExperimentalTransportEnabled() {
        return Boolean.parseBoolean(System.getProperty(EXPERIMENTAL_TRANSPORT_PROPERTY, "false"));
    }


    public static boolean isTransportAvailable() {
        if (!initialized) {
            initialize();
        }
        return transportAvailable;
    }


    public static synchronized void disableTransport(String stageName, Throwable throwable) {
        if (!initialized) {
            initialized = true;
        }
        if (!transportAvailable) {
            return;
        }

        transportAvailable = false;
        unavailableReason = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        Bandwidthoptimizer.LOGGER.error(
                "[Transport] Disabled after runtime failure. stage={}, reason={}",
                stageName,
                unavailableReason,
                throwable
        );
    }


    public static String unavailableReason() {
        if (!initialized) {
            initialize();
        }
        return unavailableReason;
    }

}
