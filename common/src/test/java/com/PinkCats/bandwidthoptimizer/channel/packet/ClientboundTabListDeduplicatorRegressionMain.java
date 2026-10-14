package com.PinkCats.bandwidthoptimizer.channel.packet;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingEpochGate;
import com.PinkCats.bandwidthoptimizer.report.unified.UnifiedBandwidthReportCollector;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.PacketFlow;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientboundTabListDeduplicatorRegressionMain {
    private static final String TAB = "net.minecraft.network.protocol.game.ClientboundTabListPacket";
    private static final String PREFIX = "net.minecraft.network.protocol.game.";
    public static void main(String[] args) throws Exception {
        String old = System.getProperty(ClientboundTabListDeduplicator.ENABLED_PROPERTY);
        try {
            System.setProperty(ClientboundTabListDeduplicator.ENABLED_PROPERTY, "true");
            verifyState();
            verifyQueue();
            verifyBoundsAndBypass();
            // Other sections require a running loader; invoke the real TAB section alone.
            var sectionMethod = UnifiedBandwidthReportCollector.class.getDeclaredMethod("tabListSection");
            sectionMethod.setAccessible(true);
            var section = (com.PinkCats.bandwidthoptimizer.report.unified.UnifiedBandwidthReport.Section) sectionMethod.invoke(null);
            check(section.metrics().size() == 5, "five report metrics");
            var report = new com.PinkCats.bandwidthoptimizer.report.unified.UnifiedBandwidthReport(
                    1, "tab-regression", 1, "test", "server", "anonymous-aggregate", java.util.List.of(section), null, null);
            byte[] json = com.PinkCats.bandwidthoptimizer.report.unified.UnifiedBandwidthReportJson.encode(report);
            var metrics = com.google.gson.JsonParser.parseString(new String(json, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("sections").get(0).getAsJsonObject().getAsJsonArray("metrics");
            check(metrics.size() == 5 && metrics.get(1).getAsJsonObject().get("value").getAsLong()
                    == ClientboundTabListDeduplicator.snapshot().savedRawBytes(), "actual report JSON exact counter");
            System.out.println("TAB dedup regression passed: exact states, epochs, ownership, limits, refresh and report");
        } finally {
            if (old == null) System.clearProperty(ClientboundTabListDeduplicator.ENABLED_PROPERTY);
            else System.setProperty(ClientboundTabListDeduplicator.ENABLED_PROPERTY, old);
        }
    }

    private static void verifyState() {
        EmbeddedChannel channel = new EmbeddedChannel();
        EmbeddedChannel other = new EmbeddedChannel();
        byte[] a = {7, 1, 2}, b = {7, 1, 3};
        try {
            var pending = inspect(channel, TAB, a, 0);
            check(!inspect(channel, TAB, a, 1).dropped(), "uncommitted retry passes");
            ClientboundTabListDeduplicator.commit(pending);
            long before = ClientboundTabListDeduplicator.snapshot().acceptedPackets();
            ClientboundTabListDeduplicator.commit(pending);
            check(ClientboundTabListDeduplicator.snapshot().acceptedPackets() == before, "commit once");
            a[1] = 9;
            check(inspect(channel, TAB, new byte[]{7, 1, 2}, 1).dropped(), "owned baseline");
            check(!inspect(other, TAB, new byte[]{7, 1, 2}, 1).dropped(), "connection isolation");
            ClientboundTabListDeduplicator.commit(inspect(channel, TAB, b, 2));
            check(!inspect(channel, TAB, new byte[]{7, 1, 2}, 3).dropped(), "A-B-A passes");
            check(inspect(channel, TAB, b, 3).dropped(), "unchanged state suppressed");
            check(!inspect(channel, TAB, b, 2 + ClientboundTabListDeduplicator.REFRESH_NANOS).dropped(), "refresh passes");
            for (String boundary : new String[]{"ClientboundLoginPacket", "ClientboundRespawnPacket", "ClientboundStartConfigurationPacket"}) {
                var stale = inspect(channel, TAB, a, 4);
                inspect(channel, PREFIX + boundary, new byte[]{1}, 5);
                ClientboundTabListDeduplicator.commit(stale);
                check(!inspect(channel, TAB, a, 6).dropped(), "boundary invalidates candidate: " + boundary);
                ClientboundTabListDeduplicator.commit(inspect(channel, TAB, a, 6));
            }
            long saved = ClientboundTabListDeduplicator.snapshot().savedRawBytes();
            for (int i = 0; i < 200; i++) check(inspect(channel, TAB, a, 7).dropped(), "repeated suppression");
            check(ClientboundTabListDeduplicator.snapshot().savedRawBytes() - saved == 200L * a.length, "raw byte accounting");
        } finally { channel.finishAndReleaseAll(); other.finishAndReleaseAll(); }
    }

    private static void verifyQueue() {
        AtomicReference<ChannelHandlerContext> ref = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext context) { ref.set(context); }
        });
        byte[] a = {7, 1, 2}, b = {7, 2, 3};
        try {
            ChannelTransportStreamingEpochGate.closeForEpoch(channel, 1, 1);
            for (byte[] bytes : new byte[][]{a, a, b, a}) {
                ByteBuf out = Unpooled.wrappedBuffer(bytes.clone());
                try {
                    var candidate = ClientboundTabListDeduplicator.inspect(channel, TAB, PacketFlow.CLIENTBOUND, out, 0, 0);
                    if (!candidate.dropped()) {
                        check(ChannelTransportStreamingEpochGate.deferIfClosed(ref.get(), false, out, 0), "ordered queue admission");
                        ClientboundTabListDeduplicator.commit(candidate);
                    }
                } finally { out.release(); }
            }
            ChannelTransportStreamingEpochGate.release(channel);
            for (byte[] expected : new byte[][]{a, b, a}) {
                ByteBuf result = channel.readOutbound();
                check(result != null, "queue replay entry");
                try { byte[] actual = new byte[result.readableBytes()]; result.readBytes(actual); check(Arrays.equals(expected, actual), "queue exact order"); }
                finally { result.release(); }
            }
            check(channel.readOutbound() == null, "only repeated identical update removed");
        } finally { ChannelTransportStreamingEpochGate.clear(channel); channel.finishAndReleaseAll(); }
    }

    private static void verifyBoundsAndBypass() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            byte[] a = {1, 2};
            ClientboundTabListDeduplicator.commit(inspect(channel, TAB, a, 0));
            check(!inspect(channel, PREFIX + "ClientboundPlayerInfoUpdatePacket", a, 1).dropped(), "player info passes");
            byte[] large = new byte[ClientboundTabListDeduplicator.MAX_BASELINE_BYTES + 1];
            ClientboundTabListDeduplicator.commit(inspect(channel, TAB, large, 1));
            check(!inspect(channel, TAB, large, 2).dropped(), "oversized repeat passes");
            check(!inspect(channel, TAB, a, 3).dropped(), "oversized invalidates previous baseline");
            ClientboundTabListDeduplicator.commit(inspect(channel, TAB, a, 3));
            System.setProperty(ClientboundTabListDeduplicator.ENABLED_PROPERTY, "false");
            check(!inspect(channel, TAB, a, 4).dropped(), "disabled passes");
            System.setProperty(ClientboundTabListDeduplicator.ENABLED_PROPERTY, "true");
            check(!inspect(channel, TAB, a, 5).dropped(), "reenable full state");
            ByteBuf prefixed = Unpooled.directBuffer();
            try {
                prefixed.writeByte(99); prefixed.writeBytes(a);
                var candidate = ClientboundTabListDeduplicator.inspect(channel, TAB, PacketFlow.CLIENTBOUND, prefixed, 1, 6);
                ClientboundTabListDeduplicator.commit(candidate);
                prefixed.writerIndex(1); prefixed.writeBytes(a);
                check(ClientboundTabListDeduplicator.inspect(channel, TAB, PacketFlow.CLIENTBOUND, prefixed, 1, 7).dropped(), "direct offset match");
                check(prefixed.writerIndex() == 1 && prefixed.getByte(0) == 99, "only encoded range removed");
                prefixed.writeBytes(a);
                check(!ClientboundTabListDeduplicator.inspect(channel, TAB, PacketFlow.SERVERBOUND, prefixed, 1, 8).dropped(), "direction bypass");
            } finally { prefixed.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }

    private static ClientboundTabListDeduplicator.Candidate inspect(EmbeddedChannel channel, String type, byte[] bytes, long now) {
        ByteBuf out = Unpooled.wrappedBuffer(bytes.clone());
        try { return ClientboundTabListDeduplicator.inspect(channel, type, PacketFlow.CLIENTBOUND, out, 0, now); }
        finally { out.release(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
