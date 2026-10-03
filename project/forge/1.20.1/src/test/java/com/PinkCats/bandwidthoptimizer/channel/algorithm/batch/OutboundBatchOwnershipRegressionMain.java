package com.PinkCats.bandwidthoptimizer.channel.algorithm.batch;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCaptureManager;
import com.PinkCats.bandwidthoptimizer.report.ChunkBoundaryBandwidthRecorder;
import com.PinkCats.bandwidthoptimizer.debug.PacketClassTraceDiagnostic;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Holds real transport promises unfinished while testing payload ownership and completion. */
public final class OutboundBatchOwnershipRegressionMain {
    private static final Class<?> PENDING = nested("PendingOutboundPacket");
    private static final Class<?> DRAIN = nested("OutboundBatchDrain");

    public static void main(String[] args) throws Exception {
        verifyOwnedFallback();
        verifyDirectFallback();
        verifyUnfinishedWrites(false);
        verifyUnfinishedWrites(true);
        System.out.println("Outbound batch ownership regression passed: owned fallback, 150 unfinished batches, GC, success/failure, carrier refcounts.");
    }

    private static void verifyOwnedFallback() throws Exception {
        byte[] input = {1, 2, 3};
        Object packet = pending(input, input);
        byte[] raw = bytes(packet, "packetBytes");
        byte[] fallback = bytes(packet, "directFallbackPacketBytes");
        require(raw == fallback && raw != input, "Unchanged fallback must share only BO-owned input");
        input[0] = 9;
        require(raw[0] == 1, "Producer mutation changed owned input");
        byte[] transformed = {4, 5};
        Object different = pending(transformed, input);
        require(bytes(different, "packetBytes") != bytes(different, "directFallbackPacketBytes"), "Transformed input lost native fallback");
        input[1] = 8;
        require(bytes(different, "directFallbackPacketBytes")[1] == 2, "Producer mutation changed native fallback");
        Object implicit = pending(transformed, null);
        require(bytes(implicit, "packetBytes") == bytes(implicit, "directFallbackPacketBytes"), "Implicit fallback duplicated owned input");
    }

    private static void verifyUnfinishedWrites(boolean fail) throws Exception {
        var before = ChannelTransportTelemetry.snapshot().outbound();
        HeldEncoder encoder = new HeldEncoder();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("encoder", encoder);
        List<WeakReference<?>> originals = new ArrayList<>();
        int batches = fail ? 1 : 150;
        for (int i = 0; i < batches; i++) originals.addAll(submit(channel));
        require(encoder.promises.size() == batches, "Carrier count changed");
        require(encoder.promises.stream().noneMatch(ChannelPromise::isDone), "Writes completed before pressure test");
        require(ChannelTransportTelemetry.snapshot().outbound().frameCount() == before.frameCount(),
                "Unfinished writes counted as successful frames");
        require(encoder.buffers.stream().allMatch(b -> b.refCnt() == 1), "Carrier released before async consumption");
        for (int attempt = 0; attempt < 20 && originals.stream().anyMatch(r -> r.get() != null); attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        require(originals.stream().allMatch(r -> r.get() == null), "Unfinished write retained original/fallback/drain/frame payload");
        for (ChannelPromise promise : List.copyOf(encoder.promises)) {
            if (fail) promise.tryFailure(new IllegalStateException("Expected simulated write failure"));
            else promise.trySuccess();
        }
        channel.runPendingTasks();
        require(encoder.buffers.stream().allMatch(b -> b.refCnt() == 0), "Completed carrier buffer leaked or double-released");
        require(!fail || !channel.isOpen(), "Failed stateful write must close connection");
        var after = ChannelTransportTelemetry.snapshot().outbound();
        require(after.frameCount() - before.frameCount() == (fail ? 0 : batches), "Completion frame count changed");
        require(after.baselineBytes() - before.baselineBytes() == (fail ? 0 : batches * 1024L * 1024L), "Raw byte totals changed");
        require(after.transportFrameBytes() - before.transportFrameBytes() == (fail ? 0 : batches * 128L * 1024L), "Encoded byte totals changed");
        channel.finishAndReleaseAll();
    }

    private static void verifyDirectFallback() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        Object packet = pending(new byte[]{8, 9}, new byte[]{1, 2, 3});
        Constructor<?> constructor = DRAIN.getDeclaredConstructor(ChannelHandlerContext.class, List.class);
        constructor.setAccessible(true);
        Object drain = constructor.newInstance(channel.pipeline().context("encoder"), List.of(packet));
        Method direct = ChannelTransportBatchManager.class.getDeclaredMethod("writePendingPacketsDirectly", DRAIN, String.class, boolean.class);
        direct.setAccessible(true);
        direct.invoke(null, drain, "ownership_regression", false);
        io.netty.buffer.ByteBuf written = channel.readOutbound();
        byte[] restored = new byte[written.readableBytes()];
        written.readBytes(restored);
        written.release();
        require(Arrays.equals(restored, new byte[]{1, 2, 3}), "Direct fallback sent transformed bytes");
        channel.finishAndReleaseAll();
    }

    private static List<WeakReference<?>> submit(EmbeddedChannel channel) throws Exception {
        byte[] input = new byte[1024 * 1024];
        input[0] = 1;
        byte[] fallback = new byte[input.length];
        fallback[0] = 2;
        Object packet = pending(input, fallback);
        Constructor<?> constructor = DRAIN.getDeclaredConstructor(ChannelHandlerContext.class, List.class);
        constructor.setAccessible(true);
        Object drain = constructor.newInstance(channel.pipeline().context("encoder"), List.of(packet));
        byte[] encoded = new byte[128 * 1024];
        encoded[0] = 7;
        var frame = new ChannelTransportPacketCodec.WrappedTransportFrame(
                ChannelTransportPacketCodec.FrameKind.BATCH, encoded, input.length, 1, encoded.length, null);
        List<WeakReference<?>> refs = List.of(new WeakReference<>(bytes(packet, "packetBytes")),
                new WeakReference<>(bytes(packet, "directFallbackPacketBytes")), new WeakReference<>(drain),
                new WeakReference<>(frame), new WeakReference<>(encoded));
        Method write = ChannelTransportBatchManager.class.getDeclaredMethod("writeWrappedBatchCarrier", Channel.class,
                DRAIN, PacketFlow.class, ChannelTransportSession.class, ChannelTransportPacketCodec.WrappedTransportFrame.class);
        write.setAccessible(true);
        write.invoke(null, channel, drain, PacketFlow.CLIENTBOUND, null, frame);
        return refs;
    }

    private static Object pending(byte[] input, byte[] fallback) throws Exception {
        Constructor<?> constructor = PENDING.getDeclaredConstructor(byte[].class, byte[].class, PacketFlow.class,
                ChannelTransportPacketRankCaptureManager.OutboundPacketCapture.class,
                ChunkBoundaryBandwidthRecorder.OutboundPacketTrace.class, PacketClassTraceDiagnostic.OutboundBatchToken.class);
        constructor.setAccessible(true);
        return constructor.newInstance(input, fallback, PacketFlow.CLIENTBOUND, null, null, null);
    }

    private static byte[] bytes(Object packet, String accessor) throws Exception {
        Method method = PENDING.getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (byte[]) method.invoke(packet);
    }

    private static Class<?> nested(String name) {
        return Arrays.stream(ChannelTransportBatchManager.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals(name)).findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class HeldEncoder extends ChannelOutboundHandlerAdapter {
        final List<ChannelPromise> promises = new ArrayList<>();
        final List<io.netty.buffer.ByteBuf> buffers = new ArrayList<>();
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            ClientboundCustomPayloadPacket packet = (ClientboundCustomPayloadPacket) message;
            // getData() returns a fresh copy; inspect the actual buffer owned by the sender instead.
            Field data = ClientboundCustomPayloadPacket.class.getDeclaredField("data");
            data.setAccessible(true);
            buffers.add((io.netty.buffer.ByteBuf) data.get(packet));
            promises.add(promise);
        }
    }
}
