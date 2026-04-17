package com.PinkCats.bandwidthoptimizer.network.client;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionReplayInvokerMixin;
import com.PinkCats.bandwidthoptimizer.network.batch.PayloadBatching;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.ClientboundPlayPacketBatchPacket;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.PlayPacketBatchCodec;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.PlayPacketReplaySupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.util.List;
import java.util.Arrays;

public final class ClientPlayPacketBatchHandler {

    private static final PayloadBatching.Session BATCH_DECODE_SESSION = new PayloadBatching.Session();
    private static long lastSessionId = Long.MIN_VALUE;
    private static long lastSequence = -1L;

    private ClientPlayPacketBatchHandler() {
    }

    public static void resetForRespawnBoundary() {
        BATCH_DECODE_SESSION.resetAll();
        lastSessionId = Long.MIN_VALUE;
        lastSequence = -1L;
    }

    public static void handle(ClientboundPlayPacketBatchPacket batchPacket) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null) {
            return;
        }

        boolean shouldReset = batchPacket.resetSession()
                || batchPacket.sessionId() != lastSessionId
                || batchPacket.sequence() != lastSequence + 1L;
        if (shouldReset) {
            BATCH_DECODE_SESSION.resetAll();
        }

        List<Packet<ClientGamePacketListener>> packets = decodeWithRetry(batchPacket, shouldReset);
        lastSessionId = batchPacket.sessionId();
        lastSequence = batchPacket.sequence();
        ClientOptimizationStats.recordBatch(
                PlayPacketReplaySupport.totalEncodedBytes(packets),
                batchPacket.encodedSize(),
                batchPacket.packetCount(),
                batchPacket.algorithmId()
        );

        Connection connection = listener.getConnection();
        if (Config.optimizerDebugLoggingEnabled() && Bandwidthoptimizer.LOGGER.isDebugEnabled()) {
            Bandwidthoptimizer.LOGGER.debug(
                    "[PlayBatch][Client][Receive] entries={}, algorithm={}, resetSession={}, mappedPacketIds={}",
                    packets.size(),
                    batchPacket.algorithmId(),
                    shouldReset,
                    batchPacket.packetIdTable().length
            );
        }

        for (Packet<ClientGamePacketListener> packet : packets) {
            replayPacket(connection, listener, packet);
        }
    }

    private static List<Packet<ClientGamePacketListener>> decodeWithRetry(ClientboundPlayPacketBatchPacket batchPacket, boolean alreadyReset) {
        try {
            return PlayPacketBatchCodec.decode(
                    batchPacket.packetIdTable(),
                    batchPacket.algorithmId(),
                    batchPacket.encodedBytes(),
                    BATCH_DECODE_SESSION
            );
        } catch (Throwable error) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[PlayBatch][Client][DecodeRetry] sessionId={}, sequence={}, algorithm={}, resetSession={}, packetCount={}, packetIdTable={}, encodedBytes={}, reason={}",
                    batchPacket.sessionId(),
                    batchPacket.sequence(),
                    batchPacket.algorithmId(),
                    alreadyReset,
                    batchPacket.packetCount(),
                    Arrays.toString(batchPacket.packetIdTable()),
                    batchPacket.encodedBytes().length,
                    error.toString()
            );
            if (!alreadyReset) {
                BATCH_DECODE_SESSION.resetAll();
            }
            return PlayPacketBatchCodec.decode(
                    batchPacket.packetIdTable(),
                    batchPacket.algorithmId(),
                    batchPacket.encodedBytes(),
                    BATCH_DECODE_SESSION
            );
        }
    }

    private static void replayPacket(Connection connection, ClientPacketListener listener, Packet<ClientGamePacketListener> packet) {
        try {
            ((ConnectionReplayInvokerMixin) (Object) connection).bandwidthoptimizer$invokeChannelRead0(null, packet);
        } catch (Throwable error) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[PlayBatch][Client][ReplayFallback] packet={}, listener={}, reason={}",
                    packet.getClass().getName(),
                    listener.getClass().getName(),
                    error.toString()
            );
            packet.handle(listener);
        }
    }
}
