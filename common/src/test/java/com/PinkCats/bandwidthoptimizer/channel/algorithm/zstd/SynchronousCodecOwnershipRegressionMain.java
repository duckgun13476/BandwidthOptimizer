package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.header.KineticPacketIdMappingLayer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class SynchronousCodecOwnershipRegressionMain {
    public static void main(String[] args) throws Exception {
        verifyFraming();
        verifyHeaders();
        byte[] fixture = pipelineFixture();
        long allocated = literalAllocation();
        if (args.length == 2 && "--write-baseline".equals(args[0])) {
            Path target = Path.of(args[1]).toAbsolutePath();
            Files.createDirectories(target.getParent());
            try (var out = new DataOutputStream(Files.newOutputStream(target))) {
                out.writeInt(fixture.length); out.write(fixture); out.writeLong(allocated);
            }
            System.out.println("baseline bytes=" + fixture.length + " allocated=" + allocated);
        } else if (args.length == 2 && "--compare-baseline".equals(args[0])) {
            try (var input = new DataInputStream(Files.newInputStream(Path.of(args[1])))) {
                byte[] baseline = input.readNBytes(input.readInt());
                require(Arrays.equals(baseline, fixture), "encoded pipeline bytes/telemetry changed from baseline");
                long before = input.readLong();
                require(input.read() == -1, "baseline trailing bytes");
                require(allocated < before, "literal synchronous allocation did not decrease");
                System.out.printf(java.util.Locale.ROOT, "baseline-equivalence: bytes=%d, allocation before=%d after=%d reduced=%.2f%%%n",
                        fixture.length, before, allocated, 100d * (before - allocated) / before);
            }
        }
        System.out.println("Synchronous codec ownership regression passed.");
    }

    private static void verifyFraming() {
        // The old two-buffer implementation is the wire-format reference.
        for (int length : new int[]{0, 1, 125, 126, 127, 128, 16379, 16380, 16383, 16384, 2097147, 2097148, 2097151, 2097152, 8 * 1024 * 1024}) {
            byte[] packet = payload(length, 13);
            byte[] expected = oldFrame(packet);
            byte[] framed = ChannelStreamingPacketCodec.encodeFramedPacket(packet);
            require(Arrays.equals(expected, framed), "framing wire bytes at length " + length);
            var outer = ChannelStreamingPacketCodec.tryReadVarInt(framed);
            require(outer.value() == framed.length - outer.nextIndex(), "outer length");
            require(Arrays.equals(packet, ChannelStreamingPacketCodec.decodeSinglePacketBatch(Arrays.copyOfRange(framed, outer.nextIndex(), framed.length))), "frame roundtrip");
            Arrays.fill(packet, (byte) 0);
            require(Arrays.equals(expected, framed), "framed output aliases caller input");
        }
        expectFailure(NullPointerException.class, () -> ChannelStreamingPacketCodec.encodeFramedPacket(null));
        // Capacity arithmetic is testable without allocating multi-gigabyte arrays.
        require(ChannelStreamingPacketCodec.framedPacketLength(Integer.MAX_VALUE - 11) == Integer.MAX_VALUE, "largest representable frame");
        expectFailure(IndexOutOfBoundsException.class, () -> ChannelStreamingPacketCodec.framedPacketLength(Integer.MAX_VALUE - 10));
        expectFailure(IndexOutOfBoundsException.class, () -> ChannelStreamingPacketCodec.framedPacketLength(Integer.MAX_VALUE));
    }

    private static byte[] oldFrame(byte[] packet) {
        FriendlyByteBuf batch = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
        try {
            batch.writeVarInt(1); batch.writeVarInt(packet.length); batch.writeBytes(packet);
            byte[] body = new byte[batch.readableBytes()]; batch.getBytes(0, body);
            frame.writeVarInt(body.length); frame.writeBytes(body);
            byte[] result = new byte[frame.readableBytes()]; frame.getBytes(0, result);
            return result;
        } finally { frame.release(); batch.release(); }
    }

    private static void verifyHeaders() {
        var header = new KineticPacketIdMappingLayer();
        byte[] a = packet(128, payload(16384, 4));
        byte[] b = packet(2, payload(7, 5));
        var encoded = header.encodeBatchPacketHeaders(List.of(a, b, a));
        require(Arrays.equals(encoded.packetIdTable(), new int[]{128, 2}), "first-seen packet table order");
        require(Arrays.equals(encoded.mappedEntryBytesList().get(0), packet(0, Arrays.copyOfRange(a, 2, a.length))), "mapped header wire bytes");
        byte[] saved = a.clone(); Arrays.fill(a, (byte) 0);
        var restored = header.decodeBatchPacketHeaders(encoded.packetIdTable(), encoded.mappedEntryBytesList());
        require(Arrays.equals(restored.get(0), saved) && Arrays.equals(restored.get(1), b), "header source isolation");
        byte[] savedMapped = encoded.mappedEntryBytesList().get(0).clone();
        Arrays.fill(restored.get(0), (byte) 0);
        require(Arrays.equals(savedMapped, encoded.mappedEntryBytesList().get(0)), "decoded output isolation");
        int[] table = {5}; byte[] mapped = {0, 9};
        var publicResult = new KineticPacketIdMappingLayer.EncodedPacketIdBatch(table, List.of(mapped));
        table[0] = 6; mapped[1] = 0;
        require(publicResult.packetIdTable()[0] == 5 && publicResult.mappedEntryBytesList().get(0)[1] == 9, "public result constructor defenses");
        require(header.encodeBatchPacketHeaders(null).mappedEntryBytesList().isEmpty(), "null batch");
        expectFailure(IndexOutOfBoundsException.class, () -> header.encodeBatchPacketHeaders(Arrays.asList((byte[]) null)));
        expectFailure(RuntimeException.class, () -> header.encodeBatchPacketHeaders(List.of(new byte[]{-128, -128, -128, -128, -128, 0})));
        expectFailure(IllegalStateException.class, () -> header.decodeBatchPacketHeaders(new int[]{1}, List.of(new byte[]{1})));
    }

    private static byte[] pipelineFixture() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        for (boolean mapping : new boolean[]{false, true}) for (boolean zstd : new boolean[]{false, true}) {
            ChannelTransportLayerRuntimeConfig.runWithTemporaryOverride(
                    new ChannelTransportLayerRuntimeConfig.RuntimeOverride(true, mapping, zstd), () -> {
                try (var sender = new ChannelTransportSession(); var receiver = new ChannelTransportSession()) {
                    for (int round = 0; round < 2; round++) {
                        sender.reset(); receiver.reset();
                        for (int i = 0; i < 20; i++) {
                            byte[] packet = payload(i == 0 ? 0 : 32768, i < 8 ? 3 : 5);
                            if (i >= 12) packet[i * 7] ^= (byte) i;
                            byte[] expected = packet.clone();
                            var encoded = i % 4 == 0 ? sender.encodeSinglePacketWithLiteralMappingTelemetry(packet)
                                    : sender.encodeSinglePacketWithTelemetry(packet);
                            require(Arrays.equals(expected, packet), "encoder mutated input");
                            writeResult(output, encoded);
                            Arrays.fill(packet, (byte) 33);
                            byte[] transport = encoded.bytes().clone();
                            var decoded = receiver.decodeSinglePacketWithTelemetry(transport);
                            require(Arrays.equals(expected, decoded.bytes()), "stateful mapping roundtrip");
                            Arrays.fill(transport, (byte) 44); Arrays.fill(decoded.bytes(), (byte) 55);
                            Arrays.fill(encoded.bytes(), (byte) 66);
                        }
                    }
                    sender.setCrossFrameZstdEnabled(true); receiver.setCrossFrameZstdEnabled(true);
                    for (int i = 0; i < 16; i++) {
                        byte[] packet = payload(32768, 7);
                        var frame = sender.encodeBatchWithStreamingZstd(packet);
                        writeResult(output, frame.packetResult());
                        Arrays.fill(packet, (byte) 1);
                        require(Arrays.equals(payload(32768, 7), receiver.decodeBatchWithStreamingZstd(frame.packetResult().bytes()).bytes()), "FLUSH borrow isolation");
                    }
                    byte[] independent = payload(16384, 9);
                    var recovered = sender.encodeStreamingFallbackBatch(independent);
                    writeResult(output, recovered);
                    Arrays.fill(independent, (byte) 2);
                    require(Arrays.equals(payload(16384, 9), receiver.decodeStreamingFallbackBatch(recovered.bytes()).bytes()), "recovery/reset ownership");
                }
            });
        }
        return bytes.toByteArray();
    }

    private static void writeResult(DataOutputStream output, ChannelTransportSession.PacketResult result) {
        try { output.writeInt(result.bytes().length); output.write(result.bytes()); output.writeUTF(result.telemetry().toString()); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static long literalAllocation() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        require(bean.isThreadAllocatedMemorySupported(), "allocation measurement unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long[] result = {0};
        ChannelTransportLayerRuntimeConfig.runWithTemporaryOverride(
                new ChannelTransportLayerRuntimeConfig.RuntimeOverride(true, true, true), () -> {
            try (var session = new ChannelTransportSession()) {
                byte[] packet = payload(32768, 19);
                for (int i = 0; i < 64; i++) session.encodeSinglePacketWithLiteralMappingTelemetry(packet);
                long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                for (int i = 0; i < 256; i++) require(session.encodeSinglePacketWithLiteralMappingTelemetry(packet).bytes().length > 0, "empty encode");
                result[0] = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            }
        });
        return result[0];
    }

    private static byte[] packet(int id, byte[] body) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try { buffer.writeVarInt(id); buffer.writeBytes(body); byte[] result = new byte[buffer.readableBytes()]; buffer.getBytes(0, result); return result; }
        finally { buffer.release(); }
    }
    private static byte[] payload(int length, int salt) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + (i >>> 7) + salt);
        return bytes;
    }
    private static void expectFailure(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) { require(type.isInstance(failure), "wrong exception " + failure); return; }
        throw new AssertionError("missing " + type.getName());
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
