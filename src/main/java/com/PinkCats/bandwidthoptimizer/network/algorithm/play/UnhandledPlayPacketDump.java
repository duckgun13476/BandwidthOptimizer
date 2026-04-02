package com.PinkCats.bandwidthoptimizer.network.algorithm.play;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class UnhandledPlayPacketDump {

    private static final Path OUTPUT_PATH = Path.of("run", "packet_dumps", "unhandled-clientbound-play.jsonl");
    private static final ConcurrentLinkedQueue<String> PENDING_LINES = new ConcurrentLinkedQueue<>();
    private static final ScheduledExecutorService FLUSHER = Executors.newSingleThreadScheduledExecutor(new DumpThreadFactory());

    static {
        FLUSHER.scheduleAtFixedRate(UnhandledPlayPacketDump::flushPending, 5L, 5L, TimeUnit.SECONDS);
    }

    private UnhandledPlayPacketDump() {
    }

    public static void record(ServerPlayer player, Packet<?> packet, PacketSendListener listener, String reason, int estimatedBytes) {
        if (!Config.enableTestMode) {
            return;
        }
        if (packet == null || reason == null) {
            return;
        }

        int packetId = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
        String playerName = player == null ? "<unknown>" : player.getGameProfile().getName();
        String payloadId = packet instanceof ClientboundCustomPayloadPacket customPayloadPacket
                ? String.valueOf(customPayloadPacket.getIdentifier())
                : "";
        String line = "{\"ts\":\"" + Instant.now() + "\""
                + ",\"player\":\"" + escape(playerName) + "\""
                + ",\"packet_class\":\"" + escape(packet.getClass().getName()) + "\""
                + ",\"packet_id\":" + packetId
                + ",\"estimated_bytes\":" + Math.max(estimatedBytes, 0)
                + ",\"payload_id\":\"" + escape(payloadId) + "\""
                + ",\"listener_present\":" + (listener != null)
                + ",\"reason\":\"" + escape(reason) + "\""
                + "}";
        PENDING_LINES.add(line);
    }

    public static void flushPending() {
        if (!Config.enableTestMode) {
            PENDING_LINES.clear();
            return;
        }
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = PENDING_LINES.poll();
            if (line == null) {
                break;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return;
        }

        try {
            Files.createDirectories(OUTPUT_PATH.getParent());
            Files.write(OUTPUT_PATH, lines, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn("Failed to flush unhandled play packet dump to {}", OUTPUT_PATH.toAbsolutePath(), exception);
        }
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class DumpThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bandwidthoptimizer-unhandled-play-dump");
            thread.setDaemon(true);
            return thread;
        }
    }
}
