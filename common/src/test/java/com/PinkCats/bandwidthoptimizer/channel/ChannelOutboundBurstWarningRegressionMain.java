package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;

public final class ChannelOutboundBurstWarningRegressionMain {

    private static final long MILLIS = 1_000_000L;

    private ChannelOutboundBurstWarningRegressionMain() {
    }

    public static void main(String[] args) {
        verifiesDedicatedTimedToolDefaultsOff();
        verifiesThresholdSourcesAndCooldown();
        verifiesWindowRotation();
        System.out.println("outbound-burst-warning: passed");
    }

    private static void verifiesDedicatedTimedToolDefaultsOff() {
        DiagnosticToolRegistry.disable(DiagnosticToolRegistry.Tool.OUTBOUND_BURST);
        require(!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.OUTBOUND_BURST),
                "outbound burst warning must default to disabled");
        DiagnosticToolRegistry.enable(
                DiagnosticToolRegistry.Tool.OUTBOUND_BURST,
                DiagnosticToolRegistry.MIN_MINUTES
        );
        require(DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.OUTBOUND_BURST),
                "outbound burst warning must support an independent timed session");
        require(DiagnosticToolRegistry.remainingMillis(DiagnosticToolRegistry.Tool.OUTBOUND_BURST) > 0L,
                "outbound burst warning timed session has no expiry");
        DiagnosticToolRegistry.disable(DiagnosticToolRegistry.Tool.OUTBOUND_BURST);
    }

    private static void verifiesThresholdSourcesAndCooldown() {
        ChannelOutboundBurstWarning.State state = new ChannelOutboundBurstWarning.State();
        long start = MILLIS;
        state.recordLogical("ClientboundCommandsPacket", 400 * 1024, start);
        state.recordLogical("ClientboundLevelChunkWithLightPacket", 300 * 1024, start);

        ChannelOutboundBurstWarning.Burst burst = state.recordWire(512 * 1024, true, start + MILLIS);
        require(burst != null, "threshold crossing must emit a warning sample");
        require(burst.wireBytes() == 512L * 1024L, "wire byte accounting changed");
        require(burst.logicalPackets() == 2, "logical packet count changed");
        require(burst.transportFrames() == 1, "transport frame count changed");
        require(burst.topSources().startsWith("ClientboundCommandsPacket=409600"), "top source ordering changed");
        require(state.recordWire(512 * 1024, true, start + 2 * MILLIS) == null,
                "warning cooldown must suppress repeated burst samples");
    }

    private static void verifiesWindowRotation() {
        ChannelOutboundBurstWarning.State state = new ChannelOutboundBurstWarning.State();
        long start = MILLIS;
        state.recordLogical("ClientboundRespawnPacket", 64, start);
        require(state.recordWire(256 * 1024, false, start + MILLIS) == null,
                "below-threshold frame must not emit a warning sample");
        long nextWindow = start + 1_100L * MILLIS;
        state.recordLogical("ClientboundCommandsPacket", 512 * 1024, nextWindow);
        ChannelOutboundBurstWarning.Burst burst = state.recordWire(512 * 1024, false, nextWindow + MILLIS);
        require(burst != null, "new window must independently evaluate the threshold");
        require(burst.logicalPackets() == 1, "previous-window sources leaked into the next window");
        require(burst.topSources().contains("ClientboundCommandsPacket"), "new-window source was not retained");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }
}
