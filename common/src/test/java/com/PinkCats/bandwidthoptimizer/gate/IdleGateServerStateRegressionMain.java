package com.PinkCats.bandwidthoptimizer.gate;

import com.PinkCats.bandwidthoptimizer.gate.recovery.IdleGateRecoveryRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

public final class IdleGateServerStateRegressionMain {

    private IdleGateServerStateRegressionMain() {
    }

    public static void main(String[] args) throws Exception {
        verifyActiveMovementPassesThrough();
        String channelId = "idle-gate-lifecycle-regression";
        UUID playerId = UUID.randomUUID();
        channelPlayers().put(channelId, playerId);
        resumeWindows().put(channelId, Long.MAX_VALUE);

        IdleGateServerState.clearChannelState(channelId);

        require(!channelPlayers().containsKey(channelId), "channel binding must be cleared");
        require(!resumeWindows().containsKey(channelId), "resume-direct window must be cleared");
        System.out.println("Idle gate server lifecycle regression passed.");
    }

    private static void verifyActiveMovementPassesThrough() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            var first = new ClientboundMoveEntityPacket.Pos(7, (short) 100, (short) 0, (short) 0, true);
            var second = new ClientboundMoveEntityPacket.Pos(7, (short) 200, (short) 0, (short) 0, true);
            require(!IdleGateRecoveryRegistry.tryCapture(channel, first, null), "active first movement must pass through");
            require(!IdleGateRecoveryRegistry.tryCapture(channel, second, null), "active second movement must pass through");
            require(channel.readOutbound() == null, "active movement must not be replaced or queued by the gate");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, UUID> channelPlayers() throws Exception {
        return (Map<String, UUID>) field("CHANNEL_PLAYERS").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> resumeWindows() throws Exception {
        return (Map<String, Long>) field("RESUME_DIRECT_UNTIL").get(null);
    }

    private static Field field(String name) throws Exception {
        Field field = IdleGateServerState.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
