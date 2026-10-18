package com.PinkCats.bandwidthoptimizer.channel.algorithm.mapping;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ExactEncoding;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.KineticBatchLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.header.KineticPacketIdMappingLayer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.*;
import java.nio.file.*;
import javax.tools.ToolProvider;

/** Reproducible encoder A/B. Optional baseline sources must come from a reviewed local Git revision. */
public final class EncodingAllocationRegressionMain {
    private static volatile byte[] sink;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        String sources = System.getProperty("bo.encodingBaselineSources");
        if (sources != null) {
            compareBaseline(Path.of(sources));
            return;
        }
        verifyOwnership();
        verifyMalformedAndBoundaries();
        KineticTemplateDictionarySecurityRegressionMain.main(new String[0]);
        com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.SynchronousCodecOwnershipRegressionMain.main(new String[0]);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int size : new int[]{1, 127, 128, 16383, 16384, 1024 * 1024, 8 * 1024 * 1024}) {
            List<byte[]> packets = payload(1, size);
            for (boolean ids : new boolean[]{false, true}) {
                ChannelTransportLayerRuntimeConfig.runWithTemporaryOverride(
                        new ChannelTransportLayerRuntimeConfig.RuntimeOverride(true, true, ids), () -> {
                            KineticBatchLayer layer = new KineticBatchLayer();
                            byte[] encoded = layer.encodePacketBatch(packets);
                            assertPackets(packets, layer.decodePacketBatch(encoded));
                            digest.update(encoded);
                        });
            }
        }
        for (boolean ids : new boolean[]{false, true}) {
            var config = new ChannelTransportLayerRuntimeConfig.RuntimeOverride(true, true, ids);
            ChannelTransportLayerRuntimeConfig.runWithTemporaryOverride(config, () -> {
                List<byte[]> packets = payload(32, 1024);
                KineticBatchLayer batch = new KineticBatchLayer();
                measure("batch-ids-" + ids, () -> batch.encodePacketBatch(packets), 150, digest);
                KineticPacketIdMappingLayer headers = new KineticPacketIdMappingLayer();
                measure("headers-" + ids, () -> headers.encodeBatchPacketHeaders(packets).mappedEntryBytesList().get(0), 150, digest);
                measure("template-literal-" + ids, () -> templateLiteral(packets.get(0)), 300, digest);
                measure("template-large-" + ids, () -> templateLiteral(LARGE), 40, digest);
                try (ChannelTransportSession sender = new ChannelTransportSession();
                     ChannelTransportSession receiver = new ChannelTransportSession()) {
                    for (int index = 0; index < 40; index++) {
                        var frame = ChannelTransportPacketCodec.wrapPacket(sender, packets.get(index % packets.size()));
                        digest.update(frame.transportFrameBytes());
                        assertPackets(List.of(packets.get(index % packets.size())),
                                ChannelTransportPacketCodec.tryUnwrapPacket(receiver, frame.transportFrameBytes()).restoredPacketBytesList());
                    }
                }
                try (ChannelTransportSession sender = new ChannelTransportSession()) {
                    measure("transport-batch-" + ids,
                            () -> ChannelTransportPacketCodec.wrapBatchPackets(sender, packets).transportFrameBytes(), 100, digest);
                }
                try (ChannelTransportSession sender = new ChannelTransportSession()) {
                    sender.setCrossFrameZstdEnabled(true);
                    measure("transport-stream-" + ids, () -> {
                        if (sender.isOutboundStreamingEpochClosed()) {
                            var boundary = sender.outboundStreamingEpochBoundary();
                            sender.acceptOutboundStreamingEpochOk(boundary.epoch(), boundary.lastSequence());
                            sender.restartOutboundStreamingEpoch();
                        }
                        return ChannelTransportPacketCodec.wrapBatchPackets(sender, packets).transportFrameBytes();
                    }, 100, digest);
                }
            });
        }
        verifyTemplateShapes(digest);
        System.out.println("ENCODING_HASH " + HexFormat.of().formatHex(digest.digest()));
        System.out.println("Encoding ownership, bounds, round-trip and allocation regression passed.");
    }

    private static final byte[] LARGE = payload(1, 1024 * 1024).get(0);

    private static byte[] templateLiteral(byte[] bytes) {
        return KineticTemplateMappingCodec.encodeFrame(new KineticTemplateMappingCodec.FrameData(
                List.of(), List.of(), KineticTemplateMappingCodec.MappingEntry.literal(bytes)));
    }

    private static void verifyTemplateShapes(MessageDigest digest) {
        var segments = List.of(KineticTemplateMappingCodec.MappingSegment.literal(new byte[]{7, 8}),
                KineticTemplateMappingCodec.MappingSegment.variable(3));
        var additions = List.of(KineticTemplateMappingCodec.MappingAddition.exact(127, new byte[]{1, 2}),
                KineticTemplateMappingCodec.MappingAddition.template(128,
                        new KineticTemplateMappingCodec.TemplateDescription(5, segments)));
        var removals = List.of(new KineticTemplateMappingCodec.MappingRemoval(0, 16384));
        for (var entry : List.of(KineticTemplateMappingCodec.MappingEntry.literal(new byte[128]),
                KineticTemplateMappingCodec.MappingEntry.exactReference(127),
                KineticTemplateMappingCodec.MappingEntry.templateReference(128, List.of(new byte[]{9, 10, 11})))) {
            byte[] bytes = KineticTemplateMappingCodec.encodeFrame(
                    new KineticTemplateMappingCodec.FrameData(additions, removals, entry));
            var decoded = KineticTemplateMappingCodec.decodeFrame(bytes);
            if (decoded.additions().size() != 2 || decoded.removals().size() != 1
                    || decoded.entry().type() != entry.type()) throw new AssertionError("Template shape changed");
            digest.update(bytes);
        }
    }

    private static void measure(String label, java.util.function.Supplier<byte[]> operation, int iterations, MessageDigest digest) {
        // Windows thread CPU accounting has coarse ticks: each timed round must span many ticks.
        iterations = label.startsWith("template-literal") ? 500_000
                : label.startsWith("template-large") ? 1_000
                : label.startsWith("headers") ? 20_000 : 10_000;
        for (int i = 0; i < 500; i++) sink = operation.get();
        long[] allocated = new long[5], cpu = new long[5], wall = new long[5];
        long id = Thread.currentThread().getId();
        for (int round = 0; round < 5; round++) {
            long a = THREADS.getThreadAllocatedBytes(id), c = THREADS.getCurrentThreadCpuTime(), w = System.nanoTime();
            for (int i = 0; i < iterations; i++) sink = operation.get();
            wall[round] = System.nanoTime() - w;
            cpu[round] = THREADS.getCurrentThreadCpuTime() - c;
            allocated[round] = THREADS.getThreadAllocatedBytes(id) - a;
        }
        Arrays.sort(allocated); Arrays.sort(cpu); Arrays.sort(wall);
        digest.update(sink);
        System.out.printf(Locale.ROOT, "METRIC %s %d %d %d%n", label,
                allocated[2] / iterations, cpu[2] / iterations, wall[2] / iterations);
    }

    private static List<byte[]> payload(int count, int length) {
        List<byte[]> result = new ArrayList<>();
        Random random = new Random(991);
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[length]; random.nextBytes(bytes);
            bytes[0] = (byte) (i % 32); result.add(bytes);
        }
        return result;
    }

    private static void verifyOwnership() {
        byte[] original = payload(1, 4096).get(0), expected = original.clone();
        KineticBatchLayer layer = new KineticBatchLayer();
        byte[] encoded = layer.encodePacketBatch(List.of(original));
        Arrays.fill(original, (byte) 0);
        assertPackets(List.of(expected), layer.decodePacketBatch(encoded));
        var mapped = new KineticPacketIdMappingLayer().encodeBatchPacketHeaders(List.of(expected));
        Arrays.fill(expected, (byte) 0);
        if (mapped.mappedEntryBytesList().get(0)[1] == 0) throw new AssertionError("Header output aliases input");
        ChannelTransportLayerRuntimeConfig.runWithTemporaryOverride(
                new ChannelTransportLayerRuntimeConfig.RuntimeOverride(true, true, true), () -> {
                    try (ChannelTransportSession sender = new ChannelTransportSession();
                         ChannelTransportSession receiver = new ChannelTransportSession()) {
                        sender.setCrossFrameZstdEnabled(true);
                        int epoch = sender.outboundStreamingEpoch();
                        byte[] input = payload(1, 4096).get(0), snapshot = input.clone();
                        var frame = ChannelTransportPacketCodec.wrapBatchPackets(sender, List.of(input));
                        byte[] sent = frame.transportFrameBytes().clone();
                        Arrays.fill(input, (byte) 0);
                        Arrays.fill(frame.transportFrameBytes(), (byte) 0);
                        var suffix = sender.replayOutboundStreamingFrames(epoch, 1);
                        if (!Arrays.equals(sent, suffix.get(0))) throw new AssertionError("Retained frame aliases caller output");
                        var fallback = sender.fallbackOutboundStreamingBatches(epoch, 1);
                        var recovery = ChannelTransportPacketCodec.wrapStreamingFallbackBatch(sender, fallback.get(0));
                        assertPackets(List.of(snapshot), ChannelTransportPacketCodec.tryUnwrapPacket(
                                receiver, recovery.transportFrameBytes()).restoredPacketBytesList());
                        sender.reset();
                        if (!sender.replayOutboundStreamingFrames(epoch, 1).isEmpty()) throw new AssertionError("Reset retained frames");
                    }
                });
    }

    private static void verifyMalformedAndBoundaries() {
        mustFail(() -> ExactEncoding.add(Integer.MAX_VALUE, 1));
        mustFail(() -> ExactEncoding.add(1, -1));
        if (ExactEncoding.varIntSize(-1) != 5 || ExactEncoding.varIntSize(128) != 2) throw new AssertionError("VarInt size");
        KineticBatchLayer layer = new KineticBatchLayer();
        ChannelTransportLayerRuntimeConfig.runWithTemporaryOverride(
                new ChannelTransportLayerRuntimeConfig.RuntimeOverride(true, true, false), () -> {
                    assertPackets(List.of(new byte[0]), layer.decodePacketBatch(layer.encodePacketBatch(List.of(new byte[0]))));
                    if (!layer.decodePacketBatch(layer.encodePacketBatch(List.of())).isEmpty()) throw new AssertionError();
                    List<byte[]> maximum = List.of(new byte[8 * 1024 * 1024], new byte[8 * 1024 * 1024]);
                    assertPackets(maximum, layer.decodePacketBatch(layer.encodePacketBatch(maximum)));
                    mustFail(() -> layer.encodePacketBatch(List.of(maximum.get(0), maximum.get(1), new byte[]{1})));
                });
        ChannelTransportLayerRuntimeConfig.runWithTemporaryOverride(
                new ChannelTransportLayerRuntimeConfig.RuntimeOverride(true, true, true), () -> {
                    mustFail(() -> layer.encodePacketBatch(List.of(new byte[0])));
                    mustFail(() -> layer.encodePacketBatch(List.of(new byte[]{(byte) 0x80})));
                    List<byte[]> signed = List.of(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f, 1});
                    assertPackets(signed, layer.decodePacketBatch(layer.encodePacketBatch(signed)));
                    List<byte[]> table = new ArrayList<>();
                    for (int id = 0; id < 4097; id++) {
                        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(5));
                        try {
                            buffer.writeVarInt(id);
                            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); table.add(bytes);
                        } finally { buffer.release(); }
                    }
                    assertPackets(table.subList(0, 4096), layer.decodePacketBatch(layer.encodePacketBatch(table.subList(0, 4096))));
                    mustFail(() -> layer.decodePacketBatch(layer.encodePacketBatch(table)));
                    List<byte[]> expanding = new ArrayList<>(table.subList(128, 256));
                    expanding.add(new byte[8 * 1024 * 1024]);
                    mustFail(() -> layer.encodePacketBatch(expanding));
                });
        mustFail(() -> layer.encodePacketBatch(List.of(new byte[8 * 1024 * 1024 + 1])));
        mustFail(() -> layer.encodePacketBatch(Collections.nCopies(40961, new byte[]{1})));
        mustFail(() -> layer.decodePacketBatch(new byte[]{1, 0, 1, 127}));
    }

    private static void mustFail(Runnable action) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Invalid input accepted");
    }

    private static void assertPackets(List<byte[]> expected, List<byte[]> actual) {
        if (expected.size() != actual.size()) throw new AssertionError("Packet count changed");
        for (int i = 0; i < expected.size(); i++) if (!Arrays.equals(expected.get(i), actual.get(i)))
            throw new AssertionError("Packet bytes changed at " + i);
    }

    private static void compareBaseline(Path sources) throws Exception {
        Path absolute = sources.toAbsolutePath().normalize();
        Path classes = absolute.resolve("classes");
        Files.createDirectories(classes);
        List<String> options = new ArrayList<>(List.of("-proc:none", "-cp", System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var files = Files.walk(absolute)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> options.add(p.toString()));
        }
        if (options.size() != 9) throw new AssertionError("Expected exactly four baseline source files");
        int status = ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err, options.toArray(String[]::new));
        if (status != 0) throw new AssertionError("Baseline compilation failed");
        String before = runChild(classes.toString() + java.io.File.pathSeparator + System.getProperty("java.class.path"));
        String after = runChild(System.getProperty("java.class.path"));
        System.out.println("=== BASELINE ===\n" + before + "=== OPTIMIZED ===\n" + after);
        String beforeHash = before.lines().filter(s -> s.startsWith("ENCODING_HASH ")).findFirst().orElseThrow();
        String afterHash = after.lines().filter(s -> s.startsWith("ENCODING_HASH ")).findFirst().orElseThrow();
        if (!beforeHash.equals(afterHash)) throw new AssertionError("Baseline wire encoding differs");
        Map<String, Long> oldAlloc = new HashMap<>();
        before.lines().filter(s -> s.startsWith("METRIC ")).forEach(s -> {
            String[] fields = s.split(" "); oldAlloc.put(fields[1], Long.parseLong(fields[2]));
        });
        after.lines().filter(s -> s.startsWith("METRIC ")).forEach(s -> {
            String[] fields = s.split(" "); long now = Long.parseLong(fields[2]), old = oldAlloc.get(fields[1]);
            if (now >= old) throw new AssertionError("No allocation improvement: " + fields[1]);
            System.out.printf(Locale.ROOT, "SAVING %s %.2f%%%n", fields[1], 100.0 * (old - now) / old);
        });
        System.out.println("Baseline byte equality and allocation reduction passed.");
    }

    private static String runChild(String classpath) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-Xmx768m", "-cp", classpath,
                EncodingAllocationRegressionMain.class.getName()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError("Encoder child failed:\n" + output);
        return output;
    }
}
