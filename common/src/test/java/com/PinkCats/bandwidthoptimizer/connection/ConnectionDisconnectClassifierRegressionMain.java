package com.PinkCats.bandwidthoptimizer.connection;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.ReadTimeoutException;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;

public final class ConnectionDisconnectClassifierRegressionMain {

    private ConnectionDisconnectClassifierRegressionMain() {}

    public static void main(String[] args) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE)
                || DiagnosticToolRegistry.remainingMillis(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE) != 0L) {
            throw new AssertionError("Connection-close classification must be enabled without an expiry by default");
        }
        assertCategory(ReadTimeoutException.INSTANCE, ConnectionDisconnectClassifier.Category.READ_TIMEOUT);
        assertCategory(new java.net.ConnectException("Connection timed out"), ConnectionDisconnectClassifier.Category.CONNECT_TIMEOUT);
        assertCategory(new java.net.ConnectException("Connection refused"), ConnectionDisconnectClassifier.Category.CONNECT_FAILURE);
        assertCategory(new java.net.SocketException("Connection reset"), ConnectionDisconnectClassifier.Category.CONNECTION_RESET);
        assertCategory(new EOFException("end of stream"), ConnectionDisconnectClassifier.Category.REMOTE_EOF);
        assertCategory(new DecoderException("invalid packet"), ConnectionDisconnectClassifier.Category.PROTOCOL_FAILURE);
        assertCategory(new SSLHandshakeException("certificate rejected"), ConnectionDisconnectClassifier.Category.SECURITY_FAILURE);
        assertCategory(
                new IllegalStateException("wrapped", new java.net.SocketException("Connection reset")),
                ConnectionDisconnectClassifier.Category.CONNECTION_RESET
        );
        assertCategory(
                new DecoderException("decode failed", new EOFException("end of stream")),
                ConnectionDisconnectClassifier.Category.PROTOCOL_FAILURE
        );

        EmbeddedChannel channel = new EmbeddedChannel();
        ConnectionDisconnectClassifier.Decision untouched = ConnectionDisconnectClassifier.onChannelInactive(channel);
        assertDecision(
                untouched,
                ConnectionDisconnectClassifier.Category.UNKNOWN,
                ConnectionDisconnectClassifier.RecoveryPolicy.MANUAL_REVIEW
        );
        ConnectionDisconnectClassifier.observeException(channel, ReadTimeoutException.INSTANCE);
        assertDecision(
                ConnectionDisconnectClassifier.snapshot(channel),
                ConnectionDisconnectClassifier.Category.READ_TIMEOUT,
                ConnectionDisconnectClassifier.RecoveryPolicy.RECONNECT_CANDIDATE
        );

        ConnectionDisconnectClassifier.observePacketClass(
                channel,
                "net.minecraft.network.protocol.common.ClientboundDisconnectPacket",
                "inbound-packet"
        );
        assertDecision(
                ConnectionDisconnectClassifier.snapshot(channel),
                ConnectionDisconnectClassifier.Category.EXPLICIT_DISCONNECT,
                ConnectionDisconnectClassifier.RecoveryPolicy.DO_NOT_AUTO_RECONNECT
        );

        ConnectionDisconnectClassifier.markBoInitiatedClose(channel, "test-stage", new IllegalStateException("test"));
        ConnectionDisconnectClassifier.Decision decision = ConnectionDisconnectClassifier.onChannelInactive(channel);
        assertDecision(
                decision,
                ConnectionDisconnectClassifier.Category.BO_TRANSPORT_FAILURE,
                ConnectionDisconnectClassifier.RecoveryPolicy.RECONNECT_CANDIDATE
        );
        if (!"test-stage".equals(decision.boStage())) {
            throw new AssertionError("BO close stage was not preserved: " + decision);
        }
        channel.finishAndReleaseAll();
        System.out.println("Connection disconnect classifier regression passed");
    }

    private static void assertCategory(Throwable throwable, ConnectionDisconnectClassifier.Category expected) {
        ConnectionDisconnectClassifier.Category actual = ConnectionDisconnectClassifier.classifyThrowable(throwable);
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual + " for " + throwable);
        }
    }

    private static void assertDecision(
            ConnectionDisconnectClassifier.Decision decision,
            ConnectionDisconnectClassifier.Category expectedCategory,
            ConnectionDisconnectClassifier.RecoveryPolicy expectedPolicy
    ) {
        if (decision.category() != expectedCategory || decision.recoveryPolicy() != expectedPolicy) {
            throw new AssertionError("Unexpected decision: " + decision);
        }
    }
}
