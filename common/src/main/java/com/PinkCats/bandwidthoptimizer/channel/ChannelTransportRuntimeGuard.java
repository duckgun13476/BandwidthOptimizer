package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.PinkCats.bandwidthoptimizer.channel.math.format.ratioText;


public final class ChannelTransportRuntimeGuard {

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
        if (!isTransportEnabled()) {
            transportAvailable = false;
            unavailableReason = "disabled by runtime property";
            Bandwidthoptimizer.LOGGER.info("[Transport] Runtime disabled by property.");
            return;
        }
        try {
            byte[] probeBytes = "bandwidthoptimizer-zstd-probe".getBytes(StandardCharsets.US_ASCII);
            try (ChannelTransportSession probeSession = new ChannelTransportSession()) {
                byte[] encodedBytes = probeSession.encodeSinglePacket(probeBytes);
                byte[] restoredBytes = probeSession.decodeSinglePacket(encodedBytes);
                if (!Arrays.equals(probeBytes, restoredBytes)) {
                    throw new IllegalStateException("Probe round trip mismatch");
                }

                transportAvailable = true;
                unavailableReason = "";
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport] Runtime ready. algorithmId={}, mapEnabled={}, zstdEnabled={}, probeRawBytes={}, probeTransportBodyBytes={}, ratio={}",
                        probeSession.algorithmId(),
                        ChannelTransportLayerRuntimeConfig.isMappingEnabled(),
                        ChannelTransportLayerRuntimeConfig.isZstdEnabled(),
                        probeBytes.length,
                        encodedBytes.length,
                        ratioText(encodedBytes.length, probeBytes.length)
                );
            }
        } catch (Throwable throwable) {
            transportAvailable = false;
            unavailableReason = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            Bandwidthoptimizer.LOGGER.warn(
                    "[Transport] Runtime unavailable, transparent transport disabled. algorithmId={}, reason={}",
                    ChannelTransportLayerRuntimeConfig.algorithmId(),
                    unavailableReason,
                    throwable
            );
        }
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

    public static void reportRuntimeFailure(String stageName, Throwable throwable) {
        Throwable failure = throwable == null
                ? new IllegalStateException("Unknown transport runtime failure")
                : throwable;
        Bandwidthoptimizer.LOGGER.error(
                "[Transport] Runtime failure on connection. stage={}, reason={}",
                stageName,
                failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                failure
        );
    }


    public static String unavailableReason() {
        if (!initialized) {
            initialize();
        }
        return unavailableReason;
    }

    private static boolean isTransportEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                Config.RuntimeProperty.Transport.ENABLED,
                Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_ENABLED)
        ));
    }

}
