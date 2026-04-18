package com.PinkCats.bandwidthoptimizer.Old.optimise.monitor;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Old.network.payload.PayloadInspectionSupport;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class PacketTrafficMonitor {

    public static final long REPORT_INTERVAL_TICKS = 20L * 20L;
    public static final int PACKET_TEST_TICKS = 100;
    private static final int MAX_STACK_FRAMES = 3;
    private static final int MAX_LOG_ENTRIES = 8;
    private static final DateTimeFormatter DUMP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static volatile boolean enabled;

    private static final ConcurrentHashMap<OutboundKey, Counter> OUTBOUND_STATS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> INBOUND_STATS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> BLOCK_ENTITY_STATS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> WIRE_OUTBOUND_STATS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> WIRE_INBOUND_STATS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentLinkedDeque<PendingSend>> PENDING_SENDS =
            new ConcurrentHashMap<>();

    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final AtomicReference<TimedCaptureSession> TIMED_CAPTURE = new AtomicReference<>();

    private PacketTrafficMonitor() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            clear();
        }
    }

    public static void clear() {
        OUTBOUND_STATS.clear();
        INBOUND_STATS.clear();
        BLOCK_ENTITY_STATS.clear();
        WIRE_OUTBOUND_STATS.clear();
        WIRE_INBOUND_STATS.clear();
        PENDING_SENDS.clear();
    }

    public static boolean hasTimedCapture() {
        return TIMED_CAPTURE.get() != null;
    }

    public static String startTimedPacketTest(MinecraftServer server, CommandSourceStack source, int durationTicks) {
        TimedCaptureSession session = new TimedCaptureSession(server, source, Math.max(1, durationTicks));
        if (!TIMED_CAPTURE.compareAndSet(null, session)) {
            TimedCaptureSession running = TIMED_CAPTURE.get();
            long remaining = running == null ? -1L : Math.max(0L, running.endTick() - running.lastSeenTickValue());
            return "Packet test is already running" + (remaining >= 0 ? " (" + remaining + " ticks remaining)." : ".");
        }

        clear();
        enabled = true;
        return "Packet test started for " + durationTicks + " ticks. A full dump will be written on completion.";
    }

    public static void captureSendSource(Channel channel, Packet<?> packet) {
        if (!enabled || channel == null || packet == null) {
            return;
        }

        String channelId = channel.id().asLongText();
        String source = captureCallPath();
        PENDING_SENDS.computeIfAbsent(channelId, key -> new ConcurrentLinkedDeque<>())
                .addLast(new PendingSend(packet, source));
    }

    public static void recordEncoded(Channel channel, Packet<?> packet, int bytes) {
        recordEncoded(channel, packet, bytes, null);
    }

    public static void recordEncoded(Channel channel, Packet<?> packet, int bytes, byte[] payload) {
        if (!enabled || packet == null) {
            return;
        }

        String packetName = describePacket(packet);
        String source = resolvePendingSource(channel, packet);
        OUTBOUND_STATS.computeIfAbsent(new OutboundKey(packetName, source), key -> new Counter()).add(bytes);

        if (packet instanceof ClientboundBlockEntityDataPacket blockEntityPacket) {
            BLOCK_ENTITY_STATS.computeIfAbsent(describeBlockEntity(blockEntityPacket), key -> new Counter())
                    .add(bytes);
        }

        TimedCaptureSession session = TIMED_CAPTURE.get();
        if (session != null) {
            session.record(PacketDumpRecord.outbound(packetName, source, bytes, payload, packet));
        }
    }

    public static void recordDecoded(Packet<?> packet, int bytes) {
        recordDecoded(packet, bytes, null, 1, 0);
    }

    public static void recordDecoded(Packet<?> packet, int bytes, byte[] payload, int batchSize, int batchIndex) {
        if (!enabled || packet == null) {
            return;
        }

        String packetName = describePacket(packet);
        INBOUND_STATS.computeIfAbsent(packetName, key -> new Counter()).add(bytes);

        TimedCaptureSession session = TIMED_CAPTURE.get();
        if (session != null) {
            session.record(PacketDumpRecord.inbound(packetName, bytes, payload, packet, batchSize, batchIndex));
        }
    }

    public static void recordWireOutbound(Channel channel, int bytes, byte[] payload) {
        recordWire(channel, bytes, payload, true);
    }

    public static void recordWireInbound(Channel channel, int bytes, byte[] payload) {
        recordWire(channel, bytes, payload, false);
    }

    public static void onServerTick(MinecraftServer server, long tickCounter) {
        TimedCaptureSession session = TIMED_CAPTURE.get();
        if (session != null) {
            session.updateLastSeenTick(tickCounter);
            if (tickCounter >= session.endTick() && TIMED_CAPTURE.compareAndSet(session, null)) {
                finishTimedCapture(session, tickCounter);
            }
        }

        if (!enabled
                || (OUTBOUND_STATS.isEmpty()
                && INBOUND_STATS.isEmpty()
                && WIRE_OUTBOUND_STATS.isEmpty()
                && WIRE_INBOUND_STATS.isEmpty())) {
            return;
        }

        if (tickCounter % REPORT_INTERVAL_TICKS == 0L) {
            Bandwidthoptimizer.LOGGER.info(buildSummary("[NetTraffic] cumulative summary @tick=" + tickCounter));
        }
    }

    private static void finishTimedCapture(TimedCaptureSession session, long tickCounter) {
        String summary = buildSummary("[NetTrafficTest] summary ticks="
                + session.durationTicks()
                + ", completedAtTick="
                + tickCounter);
        DumpPaths dumpPaths;
        try {
            dumpPaths = writeTimedDump(session, tickCounter, summary);
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.error("Failed to write packet test dump", exception);
            session.source().sendFailure(Component.literal("Packet test finished, but dump write failed: " + exception.getMessage()));
            clear();
            enabled = false;
            return;
        }

        Bandwidthoptimizer.LOGGER.info(summary);
        Bandwidthoptimizer.LOGGER.info("[NetTrafficTest] full dump saved to {}", dumpPaths.indexPath().toAbsolutePath());
        Bandwidthoptimizer.LOGGER.info("[NetTrafficTest] server_to_client dump saved to {}", dumpPaths.serverToClientPath().toAbsolutePath());
        Bandwidthoptimizer.LOGGER.info("[NetTrafficTest] client_to_server dump saved to {}", dumpPaths.clientToServerPath().toAbsolutePath());
        session.source().sendSuccess(() -> Component.literal(
                "Packet test finished. Dumps: "
                        + dumpPaths.indexPath().toAbsolutePath()
                        + " | "
                        + dumpPaths.serverToClientPath().toAbsolutePath()
                        + " | "
                        + dumpPaths.clientToServerPath().toAbsolutePath()
        ), true);

        clear();
        enabled = false;
    }

    private static DumpPaths writeTimedDump(TimedCaptureSession session, long tickCounter, String summary) throws IOException {
        Path dumpDir = Path.of("run", "packet_dumps");
        Files.createDirectories(dumpDir);

        String baseName = "packettest-" + DUMP_TIMESTAMP.format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path indexPath = dumpDir.resolve(baseName + ".jsonl");
        Path serverToClientPath = dumpDir.resolve(baseName + "-server_to_client.jsonl");
        Path clientToServerPath = dumpDir.resolve(baseName + "-client_to_server.jsonl");

        List<String> lines = new ArrayList<>(session.records().size() + 4);
        lines.add(jsonEscapeLine("summary", summary));
        lines.add("{\"type\":\"meta\",\"mod\":\"bandwidthoptimizer\",\"ticks\":" + session.durationTicks()
                + ",\"completed_tick\":" + tickCounter
                + ",\"record_count\":" + session.records().size() + "}");
        List<String> serverToClientLines = new ArrayList<>(session.records().size() + 2);
        List<String> clientToServerLines = new ArrayList<>(session.records().size() + 2);
        serverToClientLines.add(jsonEscapeLine("summary", summary));
        serverToClientLines.add("{\"type\":\"meta\",\"mod\":\"bandwidthoptimizer\",\"direction\":\"OUT\",\"ticks\":" + session.durationTicks()
                + ",\"completed_tick\":" + tickCounter + "}");
        clientToServerLines.add(jsonEscapeLine("summary", summary));
        clientToServerLines.add("{\"type\":\"meta\",\"mod\":\"bandwidthoptimizer\",\"direction\":\"IN\",\"ticks\":" + session.durationTicks()
                + ",\"completed_tick\":" + tickCounter + "}");
        for (PacketDumpRecord record : session.records()) {
            String line = record.toJsonLine();
            lines.add(line);
            if ("OUT".equals(record.direction())) {
                serverToClientLines.add(line);
            } else if ("IN".equals(record.direction())) {
                clientToServerLines.add(line);
            }
        }
        Files.write(indexPath, lines, StandardCharsets.UTF_8);
        Files.write(serverToClientPath, serverToClientLines, StandardCharsets.UTF_8);
        Files.write(clientToServerPath, clientToServerLines, StandardCharsets.UTF_8);
        return new DumpPaths(indexPath, serverToClientPath, clientToServerPath);
    }

    private static String buildSummary(String header) {
        StringBuilder log = new StringBuilder(1024);
        log.append(header);
        appendOutboundSummary(log, OUTBOUND_STATS);
        appendInboundSummary(log, INBOUND_STATS);
        appendBlockEntitySummary(log, BLOCK_ENTITY_STATS);
        appendWireSummary(log, "[W-OUT]", WIRE_OUTBOUND_STATS);
        appendWireSummary(log, "[W-IN]", WIRE_INBOUND_STATS);
        appendComparisonSummary(log);
        return log.toString();
    }

    private static void appendOutboundSummary(StringBuilder log, Map<OutboundKey, Counter> snapshot) {
        long totalBytes = 0L;
        long totalPackets = 0L;
        List<OutboundEntry> entries = new ArrayList<>(snapshot.size());

        for (Map.Entry<OutboundKey, Counter> entry : snapshot.entrySet()) {
            Counter counter = entry.getValue();
            long bytes = counter.bytes.sum();
            long packets = counter.packets.sum();
            totalBytes += bytes;
            totalPackets += packets;
            entries.add(new OutboundEntry(entry.getKey(), bytes, packets));
        }

        entries.sort(Comparator.comparingLong(OutboundEntry::bytes).reversed());
        log.append("\n[OUT] packets=").append(totalPackets)
                .append(", bytes=").append(totalBytes)
                .append(", unique=").append(entries.size());

        int limit = Math.min(MAX_LOG_ENTRIES, entries.size());
        for (int i = 0; i < limit; i++) {
            OutboundEntry entry = entries.get(i);
            log.append("\n  #").append(i + 1)
                    .append(" bytes=").append(entry.bytes())
                    .append(", packets=").append(entry.packets())
                    .append(", packet=").append(entry.key().packetName())
                    .append(", source=").append(entry.key().source());
        }
    }

    private static void appendInboundSummary(StringBuilder log, Map<String, Counter> snapshot) {
        long totalBytes = 0L;
        long totalPackets = 0L;
        List<InboundEntry> entries = new ArrayList<>(snapshot.size());

        for (Map.Entry<String, Counter> entry : snapshot.entrySet()) {
            Counter counter = entry.getValue();
            long bytes = counter.bytes.sum();
            long packets = counter.packets.sum();
            totalBytes += bytes;
            totalPackets += packets;
            entries.add(new InboundEntry(entry.getKey(), bytes, packets));
        }

        entries.sort(Comparator.comparingLong(InboundEntry::bytes).reversed());
        log.append("\n[IN ] packets=").append(totalPackets)
                .append(", bytes=").append(totalBytes)
                .append(", unique=").append(entries.size());

        int limit = Math.min(MAX_LOG_ENTRIES, entries.size());
        for (int i = 0; i < limit; i++) {
            InboundEntry entry = entries.get(i);
            log.append("\n  #").append(i + 1)
                    .append(" bytes=").append(entry.bytes())
                    .append(", packets=").append(entry.packets())
                    .append(", packet=").append(entry.packetName());
        }
    }

    private static void appendBlockEntitySummary(StringBuilder log, Map<String, Counter> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }

        long totalBytes = 0L;
        long totalPackets = 0L;
        List<InboundEntry> entries = new ArrayList<>(snapshot.size());

        for (Map.Entry<String, Counter> entry : snapshot.entrySet()) {
            Counter counter = entry.getValue();
            long bytes = counter.bytes.sum();
            long packets = counter.packets.sum();
            totalBytes += bytes;
            totalPackets += packets;
            entries.add(new InboundEntry(entry.getKey(), bytes, packets));
        }

        entries.sort(Comparator.comparingLong(InboundEntry::bytes).reversed());
        log.append("\n[BE ] packets=").append(totalPackets)
                .append(", bytes=").append(totalBytes)
                .append(", unique=").append(entries.size());

        int limit = Math.min(MAX_LOG_ENTRIES, entries.size());
        for (int i = 0; i < limit; i++) {
            InboundEntry entry = entries.get(i);
            log.append("\n  #").append(i + 1)
                    .append(" bytes=").append(entry.bytes())
                    .append(", packets=").append(entry.packets())
                    .append(", blockEntity=").append(entry.packetName());
        }
    }

    private static void appendWireSummary(StringBuilder log, String label, Map<String, Counter> snapshot) {
        long totalBytes = 0L;
        long totalEvents = 0L;
        List<InboundEntry> entries = new ArrayList<>(snapshot.size());

        for (Map.Entry<String, Counter> entry : snapshot.entrySet()) {
            Counter counter = entry.getValue();
            long bytes = counter.bytes.sum();
            long events = counter.packets.sum();
            totalBytes += bytes;
            totalEvents += events;
            entries.add(new InboundEntry(entry.getKey(), bytes, events));
        }

        entries.sort(Comparator.comparingLong(InboundEntry::bytes).reversed());
        log.append("\n").append(label)
                .append(" events=").append(totalEvents)
                .append(", bytes=").append(totalBytes)
                .append(", unique=").append(entries.size());

        int limit = Math.min(MAX_LOG_ENTRIES, entries.size());
        for (int i = 0; i < limit; i++) {
            InboundEntry entry = entries.get(i);
            log.append("\n  #").append(i + 1)
                    .append(" bytes=").append(entry.bytes())
                    .append(", events=").append(entry.packets())
                    .append(", channel=").append(entry.packetName());
        }
    }

    private static void appendComparisonSummary(StringBuilder log) {
        long encodedOutBytes = sumBytes(OUTBOUND_STATS);
        long decodedInBytes = sumBytes(INBOUND_STATS);
        long wireOutBytes = sumBytes(WIRE_OUTBOUND_STATS);
        long wireInBytes = sumBytes(WIRE_INBOUND_STATS);

        log.append("\n[CMP] encoded_out=").append(encodedOutBytes)
                .append(", wire_out=").append(wireOutBytes)
                .append(", delta=").append(wireOutBytes - encodedOutBytes)
                .append(", ratio=").append(formatRatio(wireOutBytes, encodedOutBytes));
        log.append("\n[CMP] decoded_in=").append(decodedInBytes)
                .append(", wire_in=").append(wireInBytes)
                .append(", delta=").append(wireInBytes - decodedInBytes)
                .append(", ratio=").append(formatRatio(wireInBytes, decodedInBytes));
    }

    private static String resolvePendingSource(Channel channel, Packet<?> packet) {
        if (channel == null) {
            return "<no-channel>";
        }

        String channelId = channel.id().asLongText();
        ConcurrentLinkedDeque<PendingSend> queue = PENDING_SENDS.get(channelId);
        if (queue == null) {
            return "<unknown>";
        }

        PendingSend first = queue.peekFirst();
        if (first != null && first.packet() == packet) {
            queue.pollFirst();
            cleanupPendingQueue(channelId, queue);
            return first.source();
        }

        for (PendingSend pending : queue) {
            if (pending.packet() == packet && queue.removeFirstOccurrence(pending)) {
                cleanupPendingQueue(channelId, queue);
                return pending.source();
            }
        }

        cleanupPendingQueue(channelId, queue);
        return "<unknown>";
    }

    private static void cleanupPendingQueue(String channelId, ConcurrentLinkedDeque<PendingSend> queue) {
        if (queue.isEmpty()) {
            PENDING_SENDS.remove(channelId, queue);
        }
    }

    private static String captureCallPath() {
        List<String> frames = STACK_WALKER.walk(stream -> stream
                .filter(frame -> !shouldSkipFrame(frame.getClassName()))
                .limit(MAX_STACK_FRAMES)
                .map(PacketTrafficMonitor::formatFrame)
                .toList());
        return frames.isEmpty() ? "<unknown>" : String.join(" <- ", frames);
    }

    private static boolean shouldSkipFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.startsWith("jdk.")
                || className.startsWith("io.netty.")
                || className.startsWith("org.spongepowered.")
                || className.startsWith("com.PinkCats.bandwidthoptimizer.old.network.")
                || className.equals("net.minecraft.network.Connection")
                || className.startsWith("net.minecraftforge.network.");
    }

    private static String formatFrame(StackWalker.StackFrame frame) {
        return shortenClassName(frame.getClassName()) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    private static String shortenClassName(String className) {
        String[] parts = className.split("\\.");
        if (parts.length <= 2) {
            return className;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i < parts.length - 1) {
                if (!parts[i].isEmpty()) {
                    builder.append(parts[i].charAt(0)).append('.');
                }
            } else {
                builder.append(parts[i]);
            }
        }
        return builder.toString();
    }

    private static String describePacket(Packet<?> packet) {
        String packetId = packet.getClass().getName();
        if (packet instanceof ClientboundCustomPayloadPacket customPacket) {
            return packetId + "[" + describePayload(customPacket.getIdentifier()) + "]";
        }
        if (packet instanceof ServerboundCustomPayloadPacket customPacket) {
            return packetId + "[" + describePayload(customPacket.getIdentifier()) + "]";
        }
        return packetId + "(" + packet.getClass().getSimpleName() + ")";
    }

    private static String describePayload(ResourceLocation payloadId) {
        return payloadId == null ? "payload=null" : payloadId.toString();
    }

    private static String describeBlockEntity(ClientboundBlockEntityDataPacket packet) {
        ResourceLocation typeId = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(packet.getType());
        return typeId == null ? packet.getType().toString() : typeId.toString();
    }

    private static void recordWire(Channel channel, int bytes, byte[] payload, boolean outbound) {
        if (!enabled || channel == null) {
            return;
        }

        String channelLabel = describeWireChannel(channel);
        (outbound ? WIRE_OUTBOUND_STATS : WIRE_INBOUND_STATS)
                .computeIfAbsent(channelLabel, key -> new Counter())
                .add(bytes);

        TimedCaptureSession session = TIMED_CAPTURE.get();
        if (session != null) {
            session.record(PacketDumpRecord.wire(outbound ? "OUT" : "IN", channelLabel, bytes, payload));
        }
    }

    private static String describeWireChannel(Channel channel) {
        Object protocol = channel.attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get();
        String protocolName = protocol == null ? "null" : String.valueOf(protocol);
        String remote = String.valueOf(channel.remoteAddress());
        String local = String.valueOf(channel.localAddress());
        return "id=" + channel.id().asShortText()
                + ", protocol=" + protocolName
                + ", transport=" + channel.getClass().getSimpleName()
                + ", remote=" + remote
                + ", local=" + local;
    }

    private static long sumBytes(Map<?, Counter> snapshot) {
        long totalBytes = 0L;
        for (Counter counter : snapshot.values()) {
            totalBytes += counter.bytes.sum();
        }
        return totalBytes;
    }

    private static String formatRatio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return numerator <= 0L ? "1.000x" : "inf";
        }
        return String.format(java.util.Locale.ROOT, "%.3fx", (double) numerator / (double) denominator);
    }

    private static byte[] copyByteRange(ByteBuf buf, int startIndex, int length) {
        if (length <= 0 || startIndex < 0 || startIndex + length > buf.writerIndex()) {
            return new byte[0];
        }

        byte[] data = new byte[length];
        buf.getBytes(startIndex, data);
        return data;
    }

    private static String jsonEscapeLine(String key, String value) {
        return "{\"type\":\"" + escapeJson(key) + "\",\"value\":\"" + escapeJson(value) + "\"}";
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    public static byte[] copyBytes(ByteBuf buf, int startIndex, int endIndexExclusive) {
        return copyByteRange(buf, startIndex, Math.max(endIndexExclusive - startIndex, 0));
    }

    private static byte[] serializePacketPayload(Packet<?> packet) {
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(friendlyByteBuf);
            return copyBytes(friendlyByteBuf, 0, friendlyByteBuf.writerIndex());
        } finally {
            friendlyByteBuf.release();
        }
    }

    private static String hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte datum : data) {
            builder.append(Character.forDigit((datum >> 4) & 0xF, 16));
            builder.append(Character.forDigit(datum & 0xF, 16));
        }
        return builder.toString();
    }

    private static String describePacketObject(Packet<?> packet) {
        return describeValue(packet, new IdentityHashMap<>(), 0);
    }

    private static String describeValue(Object value, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null) {
            return "null";
        }
        if (depth > 4) {
            return "\"<max-depth>\"";
        }
        if (isSimpleValue(value)) {
            return "\"" + escapeJson(String.valueOf(value)) + "\"";
        }
        if (value instanceof Tag tag) {
            return "\"" + escapeJson(tag.toString()) + "\"";
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(o -> describeValue(o, visited, depth + 1)).orElse("null");
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            return "\"<cycle>\"";
        }
        try {
            if (value instanceof Iterable<?> iterable) {
                StringBuilder builder = new StringBuilder("[");
                boolean first = true;
                for (Object entry : iterable) {
                    if (!first) {
                        builder.append(',');
                    }
                    builder.append(describeValue(entry, visited, depth + 1));
                    first = false;
                }
                return builder.append(']').toString();
            }
            if (value.getClass().isArray()) {
                StringBuilder builder = new StringBuilder("[");
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(describeValue(Array.get(value, i), visited, depth + 1));
                }
                return builder.append(']').toString();
            }
            if (value instanceof Map<?, ?> map) {
                StringBuilder builder = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    builder.append("\"").append(escapeJson(String.valueOf(entry.getKey()))).append("\":")
                            .append(describeValue(entry.getValue(), visited, depth + 1));
                    first = false;
                }
                return builder.append('}').toString();
            }
            return describeObjectFields(value, visited, depth + 1);
        } finally {
            visited.remove(value);
        }
    }

    private static String describeObjectFields(Object value, IdentityHashMap<Object, Boolean> visited, int depth) {
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"class\":\"").append(escapeJson(value.getClass().getName())).append("\"");

        boolean appended = false;
        for (Field field : getAllFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                builder.append(",\"").append(escapeJson(field.getName())).append("\":")
                        .append(describeValue(fieldValue, visited, depth + 1));
                appended = true;
            } catch (Throwable ignored) {
                builder.append(",\"").append(escapeJson(field.getName())).append("\":\"<inaccessible>\"");
                appended = true;
            }
        }

        String nbtDump = tryExtractNbt(value);
        if (!nbtDump.isEmpty()) {
            builder.append(",\"nbt_hint\":\"").append(escapeJson(nbtDump)).append("\"");
            appended = true;
        }

        if (!appended) {
            builder.append(",\"value\":\"").append(escapeJson(String.valueOf(value))).append("\"");
        }
        return builder.append('}').toString();
    }

    private static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static boolean isSimpleValue(Object value) {
        return value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof CharSequence
                || value instanceof Enum<?>
                || value instanceof ResourceLocation
                || value instanceof UUID;
    }

    private static String tryExtractNbt(Object value) {
        if (value instanceof ClientboundBlockEntityDataPacket blockEntityDataPacket) {
            return blockEntityDataPacket.getTag().toString();
        }

        for (String methodName : List.of("getTag", "tag", "getUpdateTag")) {
            try {
                Method method = value.getClass().getMethod(methodName);
                if (method.getParameterCount() == 0 && Tag.class.isAssignableFrom(method.getReturnType())) {
                    Object result = method.invoke(value);
                    return result == null ? "" : result.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private record PendingSend(Packet<?> packet, String source) {
    }

    private record OutboundKey(String packetName, String source) {
        private OutboundKey {
            packetName = Objects.requireNonNullElse(packetName, "<unknown>");
            source = Objects.requireNonNullElse(source, "<unknown>");
        }
    }

    private record OutboundEntry(OutboundKey key, long bytes, long packets) {
    }

    private record InboundEntry(String packetName, long bytes, long packets) {
    }

    private static final class Counter {
        private final LongAdder packets = new LongAdder();
        private final LongAdder bytes = new LongAdder();

        private void add(int packetBytes) {
            this.packets.increment();
            this.bytes.add(Math.max(packetBytes, 0));
        }
    }

    private record TimedCaptureSession(
            MinecraftServer server,
            CommandSourceStack source,
            long durationTicks,
            long startTick,
            long endTick,
            AtomicReference<Long> lastSeenTick,
            List<PacketDumpRecord> records
    ) {
        private TimedCaptureSession(MinecraftServer server, CommandSourceStack source, long durationTicks) {
            this(
                    server,
                    source,
                    durationTicks,
                    server.getTickCount(),
                    server.getTickCount() + durationTicks,
                    new AtomicReference<>((long) server.getTickCount()),
                    java.util.Collections.synchronizedList(new ArrayList<>())
            );
        }

        private void record(PacketDumpRecord record) {
            this.records.add(record);
        }

        private void updateLastSeenTick(long tick) {
            this.lastSeenTick.set(tick);
        }

        private long lastSeenTickValue() {
            return this.lastSeenTick.get();
        }
    }

    private record DumpPaths(
            Path indexPath,
            Path serverToClientPath,
            Path clientToServerPath
    ) {
    }

    private record PacketDumpRecord(
            String captureType,
            String direction,
            long capturedAtMillis,
            String packetName,
            String packetClass,
            String packetToString,
            String source,
            String wireChannel,
            int bytes,
            String payloadBase64,
            String payloadHex,
            String serializedPayloadBase64,
            String serializedPayloadHex,
            String payloadAnalysis,
            String serializedPayloadAnalysis,
            String packetFields,
            String nbtHint,
            int batchSize,
            int batchIndex
    ) {
        private static PacketDumpRecord outbound(String packetName, String source, int bytes, byte[] payload, Packet<?> packet) {
            byte[] serializedPayload = serializePacketPayload(packet);
            String packetFields = describePacketObject(packet);
            String nbtHint = tryExtractNbt(packet);
            return new PacketDumpRecord(
                    "PACKET",
                    "OUT",
                    System.currentTimeMillis(),
                    packetName,
                    packet.getClass().getName(),
                    String.valueOf(packet),
                    source,
                    "",
                    Math.max(bytes, 0),
                    Base64.getEncoder().encodeToString(payload == null ? new byte[0] : payload),
                    hex(payload),
                    Base64.getEncoder().encodeToString(serializedPayload),
                    hex(serializedPayload),
                    PayloadInspectionSupport.inspectPayload(packet.getClass().getName(), payload == null ? new byte[0] : payload).toString(),
                    PayloadInspectionSupport.inspectPayload(packet.getClass().getName(), serializedPayload).toString(),
                    packetFields,
                    nbtHint,
                    1,
                    0
            );
        }

        private static PacketDumpRecord inbound(String packetName, int bytes, byte[] payload, Packet<?> packet, int batchSize, int batchIndex) {
            byte[] serializedPayload = serializePacketPayload(packet);
            String packetFields = describePacketObject(packet);
            String nbtHint = tryExtractNbt(packet);
            return new PacketDumpRecord(
                    "PACKET",
                    "IN",
                    System.currentTimeMillis(),
                    packetName,
                    packet.getClass().getName(),
                    String.valueOf(packet),
                    "<decoded>",
                    "",
                    Math.max(bytes, 0),
                    Base64.getEncoder().encodeToString(payload == null ? new byte[0] : payload),
                    hex(payload),
                    Base64.getEncoder().encodeToString(serializedPayload),
                    hex(serializedPayload),
                    PayloadInspectionSupport.inspectPayload(packet.getClass().getName(), payload == null ? new byte[0] : payload).toString(),
                    PayloadInspectionSupport.inspectPayload(packet.getClass().getName(), serializedPayload).toString(),
                    packetFields,
                    nbtHint,
                    batchSize,
                    batchIndex
            );
        }

        private static PacketDumpRecord wire(String direction, String channelLabel, int bytes, byte[] payload) {
            byte[] safePayload = payload == null ? new byte[0] : payload;
            return new PacketDumpRecord(
                    "WIRE",
                    direction,
                    System.currentTimeMillis(),
                    "<wire>",
                    "",
                    "",
                    "<wire>",
                    channelLabel,
                    Math.max(bytes, 0),
                    Base64.getEncoder().encodeToString(safePayload),
                    hex(safePayload),
                    "",
                    "",
                    "null",
                    "null",
                    "{}",
                    "",
                    1,
                    0
            );
        }

        private String toJsonLine() {
            return "{"
                    + "\"type\":\"packet\","
                    + "\"capture_type\":\"" + escapeJson(this.captureType) + "\","
                    + "\"direction\":\"" + escapeJson(this.direction) + "\","
                    + "\"captured_at_ms\":" + this.capturedAtMillis + ","
                    + "\"packet\":\"" + escapeJson(this.packetName) + "\","
                    + "\"packet_class\":\"" + escapeJson(this.packetClass) + "\","
                    + "\"packet_to_string\":\"" + escapeJson(this.packetToString) + "\","
                    + "\"source\":\"" + escapeJson(this.source) + "\","
                    + "\"wire_channel\":\"" + escapeJson(this.wireChannel) + "\","
                    + "\"bytes\":" + this.bytes + ","
                    + "\"batch_size\":" + this.batchSize + ","
                    + "\"batch_index\":" + this.batchIndex + ","
                    + "\"payload_base64\":\"" + this.payloadBase64 + "\","
                    + "\"payload_hex\":\"" + this.payloadHex + "\","
                    + "\"serialized_payload_base64\":\"" + this.serializedPayloadBase64 + "\","
                    + "\"serialized_payload_hex\":\"" + this.serializedPayloadHex + "\","
                    + "\"payload_analysis\":" + this.payloadAnalysis + ","
                    + "\"serialized_payload_analysis\":" + this.serializedPayloadAnalysis + ","
                    + "\"packet_fields\":" + this.packetFields + ","
                    + "\"nbt_hint\":\"" + escapeJson(this.nbtHint) + "\""
                    + "}";
        }
    }
}
