package com.PinkCats.bandwidthoptimizer.connection;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.embedded.EmbeddedChannel;

public final class KeepAliveTimeoutDiagnosticRegressionMain {

    private static final String LOGIN_PACKET =
            "net.minecraft.network.protocol.login.ClientboundHelloPacket";
    private static final String CONFIGURATION_PACKET =
            "net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket";
    private static final String GAME_PACKET =
            "net.minecraft.network.protocol.game.ClientboundLoginPacket";
    private static final String CLIENTBOUND_KEEP_ALIVE =
            "net.minecraft.network.protocol.game.ClientboundKeepAlivePacket";
    private static final String SERVERBOUND_KEEP_ALIVE =
            "net.minecraft.network.protocol.game.ServerboundKeepAlivePacket";
    private static final String MODERN_CLIENTBOUND_KEEP_ALIVE =
            "net.minecraft.network.protocol.common.ClientboundKeepAlivePacket";
    private static final String MODERN_SERVERBOUND_KEEP_ALIVE =
            "net.minecraft.network.protocol.common.ServerboundKeepAlivePacket";

    private KeepAliveTimeoutDiagnosticRegressionMain() {}

    public static void main(String[] args) {
        DiagnosticToolRegistry.disable(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT);
        EmbeddedChannel disabled = new EmbeddedChannel();
        KeepAliveTimeoutDiagnostic.observePacketClass(disabled, CLIENTBOUND_KEEP_ALIVE, true, 1_000L);
        check(KeepAliveTimeoutDiagnostic.snapshot(disabled, 1_100L).challengeCount() == 0L,
                "disabled probe allocated or mutated connection state");
        disabled.finishAndReleaseAll();

        DiagnosticToolRegistry.enable(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT, 5);
        EmbeddedChannel server = new EmbeddedChannel();
        KeepAliveTimeoutDiagnostic.observePacketClass(server, LOGIN_PACKET, false, 100L);
        check(KeepAliveTimeoutDiagnostic.snapshot(server, 100L).phase()
                        == KeepAliveTimeoutDiagnostic.Phase.LOGIN,
                "login phase was not observed");
        KeepAliveTimeoutDiagnostic.observePacketClass(server, CONFIGURATION_PACKET, false, 200L);
        check(KeepAliveTimeoutDiagnostic.snapshot(server, 200L).phase()
                        == KeepAliveTimeoutDiagnostic.Phase.CONFIGURATION,
                "configuration phase was not observed");

        KeepAliveTimeoutDiagnostic.observePacketClass(server, CLIENTBOUND_KEEP_ALIVE, true, 1_000L);
        KeepAliveTimeoutDiagnostic.Snapshot pending = KeepAliveTimeoutDiagnostic.snapshot(server, 1_250L);
        check(pending.phase() == KeepAliveTimeoutDiagnostic.Phase.PLAY,
                "keepalive challenge did not advance to PLAY");
        check(pending.endpoint() == KeepAliveTimeoutDiagnostic.Endpoint.SERVER,
                "outbound challenge did not identify the server endpoint");
        check(pending.pendingChallenge() && pending.challengeAgeMillis() == 250L,
                "pending challenge timing was not retained");

        KeepAliveTimeoutDiagnostic.observePacketClass(server, SERVERBOUND_KEEP_ALIVE, false, 1_400L);
        KeepAliveTimeoutDiagnostic.Snapshot acknowledged = KeepAliveTimeoutDiagnostic.snapshot(server, 1_500L);
        check(!acknowledged.pendingChallenge()
                        && acknowledged.challengeCount() == 1L
                        && acknowledged.ackCount() == 1L
                        && acknowledged.lastAckAgeMillis() == 100L,
                "server ACK observation did not close the pending challenge");

        EmbeddedChannel client = new EmbeddedChannel();
        KeepAliveTimeoutDiagnostic.observePacketClass(client, CLIENTBOUND_KEEP_ALIVE, false, 2_000L);
        KeepAliveTimeoutDiagnostic.observePacketClass(client, SERVERBOUND_KEEP_ALIVE, true, 2_100L);
        KeepAliveTimeoutDiagnostic.Snapshot clientSnapshot = KeepAliveTimeoutDiagnostic.snapshot(client, 2_200L);
        check(clientSnapshot.endpoint() == KeepAliveTimeoutDiagnostic.Endpoint.CLIENT
                        && !clientSnapshot.pendingChallenge()
                        && clientSnapshot.ackCount() == 1L,
                "client challenge/ACK direction was not preserved");

        EmbeddedChannel modern = new EmbeddedChannel();
        KeepAliveTimeoutDiagnostic.observePacketClass(modern, GAME_PACKET, true, 2_450L);
        KeepAliveTimeoutDiagnostic.observePacketClass(modern, MODERN_CLIENTBOUND_KEEP_ALIVE, true, 2_500L);
        KeepAliveTimeoutDiagnostic.observePacketClass(modern, MODERN_SERVERBOUND_KEEP_ALIVE, false, 2_550L);
        KeepAliveTimeoutDiagnostic.Snapshot modernSnapshot =
                KeepAliveTimeoutDiagnostic.snapshot(modern, 2_600L);
        check(modernSnapshot.endpoint() == KeepAliveTimeoutDiagnostic.Endpoint.SERVER
                        && modernSnapshot.phase() == KeepAliveTimeoutDiagnostic.Phase.PLAY
                        && modernSnapshot.challengeCount() == 1L
                        && modernSnapshot.ackCount() == 1L,
                "modern common-protocol KeepAlive classes were not observed");

        EmbeddedChannel configuration = new EmbeddedChannel();
        KeepAliveTimeoutDiagnostic.observePacketClass(configuration, CONFIGURATION_PACKET, true, 2_700L);
        KeepAliveTimeoutDiagnostic.observePacketClass(configuration, MODERN_CLIENTBOUND_KEEP_ALIVE, true, 2_800L);
        check(KeepAliveTimeoutDiagnostic.snapshot(configuration, 2_900L).phase()
                        == KeepAliveTimeoutDiagnostic.Phase.CONFIGURATION,
                "common-protocol KeepAlive incorrectly promoted configuration to PLAY");

        KeepAliveTimeoutDiagnostic.observePacketClass(server, CLIENTBOUND_KEEP_ALIVE, true, 3_000L);
        KeepAliveTimeoutDiagnostic.observeVanillaKeepAliveTimeoutBoundary(server, 15_500L, true);
        KeepAliveTimeoutDiagnostic.Snapshot timedOut =
                KeepAliveTimeoutDiagnostic.snapshot(server, System.currentTimeMillis());
        check(timedOut.keepAliveTimeoutBoundaryObserved()
                        && timedOut.pendingChallenge()
                        && timedOut.challengeAgeMillis() >= 15_000L,
                "exact vanilla KeepAlive timeout boundary was not retained");

        server.finishAndReleaseAll();
        client.finishAndReleaseAll();
        modern.finishAndReleaseAll();
        configuration.finishAndReleaseAll();
        DiagnosticToolRegistry.disable(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT);
        System.out.println("KeepAlive timeout diagnostic regression passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
