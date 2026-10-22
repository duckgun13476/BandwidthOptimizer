package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.RequiredPeerHandshake;
import io.netty.channel.embedded.EmbeddedChannel;

public final class RequiredPeerHandshakeRegressionMain {
    public static void main(String[] args) {
        check(!RequiredPeerHandshake.matches("v152", null), "missing peer accepted");
        check(!RequiredPeerHandshake.matches("v152", "ABSENT"), "Forge absent sentinel accepted");
        check(!RequiredPeerHandshake.matches("v152", "ACCEPTVANILLA"), "vanilla sentinel accepted");
        check(!RequiredPeerHandshake.matches("v152", "v151"), "incompatible peer accepted");
        check(RequiredPeerHandshake.matches("v152", "v152"), "matching peer rejected");
        EmbeddedChannel old = new EmbeddedChannel();
        EmbeddedChannel fresh = new EmbeddedChannel();
        try {
            check(!RequiredPeerHandshake.accepted(old), "missing server challenge accepted");
            RequiredPeerHandshake.accept(old);
            check(RequiredPeerHandshake.accepted(old), "valid challenge lost");
            check(!RequiredPeerHandshake.accepted(fresh), "receipt crossed connections");
            old.close().syncUninterruptibly();
            check(!RequiredPeerHandshake.accepted(old), "closed connection accepted");
            RequiredPeerHandshake.accept(old);
            check(!RequiredPeerHandshake.accepted(old), "closed connection resurrected");
        } finally {
            old.finishAndReleaseAll();
            fresh.finishAndReleaseAll();
        }
        System.out.println("Required peer version/connection lifecycle regression PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
