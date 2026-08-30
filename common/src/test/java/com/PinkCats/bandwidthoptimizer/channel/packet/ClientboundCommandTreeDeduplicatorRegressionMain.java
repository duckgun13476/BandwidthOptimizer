package com.PinkCats.bandwidthoptimizer.channel.packet;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingEpochGate;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.PacketFlow;

import java.util.concurrent.atomic.AtomicReference;

public final class ClientboundCommandTreeDeduplicatorRegressionMain {
    private static final String COMMANDS = "net.minecraft.network.protocol.game.ClientboundCommandsPacket";
    private static final String LOGIN = "net.minecraft.network.protocol.game.ClientboundLoginPacket";

    private ClientboundCommandTreeDeduplicatorRegressionMain() {
    }

    public static void main(String[] args) {
        String property = Config.RuntimeProperty.Transport.COMMAND_TREE_DEDUP_ENABLED;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "true");
            verifyCommitAndContentSemantics();
            verifyDeferredQueueSemantics();
            verifyDisabledFailOpen(property);
            System.out.println("Clientbound command tree deduplication regression passed");
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    private static void verifyCommitAndContentSemantics() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            byte[] first = bytes(0x31);
            ClientboundCommandTreeDeduplicator.Candidate pending = inspect(channel, first);
            assertFalse(pending.dropped(), "first packet must pass");
            assertFalse(inspect(channel, first).dropped(), "uncommitted packet must not suppress a retry");
            ClientboundCommandTreeDeduplicator.commit(pending);
            assertTrue(inspect(channel, first).dropped(), "committed identical packet must be suppressed");
            ClientboundCommandTreeDeduplicator.Candidate changed = inspect(channel, bytes(0x57));
            assertFalse(changed.dropped(), "same-length changed packet must pass");
            ClientboundCommandTreeDeduplicator.commit(changed);
            assertFalse(inspect(channel, first).dropped(), "A after committed B must pass");
            ByteBuf boundary = buffer(new byte[] {1});
            try {
                ClientboundCommandTreeDeduplicator.inspect(channel, LOGIN, PacketFlow.CLIENTBOUND, boundary, 0);
            } finally {
                boundary.release();
            }
            assertFalse(inspect(channel, first).dropped(), "login boundary must reset the command tree state");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void verifyDeferredQueueSemantics() {
        AtomicReference<ChannelHandlerContext> contextRef = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext context) { contextRef.set(context); }
        });
        ChannelHandlerContext context = contextRef.get();
        ByteBuf first = buffer(bytes(0x44));
        try {
            ChannelTransportStreamingEpochGate.closeForEpoch(channel, 1, 1);
            ClientboundCommandTreeDeduplicator.Candidate candidate = ClientboundCommandTreeDeduplicator.inspect(
                    channel, COMMANDS, PacketFlow.CLIENTBOUND, first, 0);
            assertTrue(ChannelTransportStreamingEpochGate.deferIfClosed(context, false, first, 0), "first tree must enter the deferred queue");
            ClientboundCommandTreeDeduplicator.commit(candidate);
            assertTrue(inspect(channel, bytes(0x44)).dropped(), "accepted deferred tree must deduplicate later repeats");
            ChannelTransportStreamingEpochGate.release(channel);
            ByteBuf replayed = channel.readOutbound();
            assertTrue(replayed != null && replayed.readableBytes() > 0, "deferred tree must replay");
            if (replayed != null) replayed.release();
        } finally {
            first.release();
            ChannelTransportStreamingEpochGate.clear(channel);
            channel.finishAndReleaseAll();
        }
    }

    private static void verifyDisabledFailOpen(String property) {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            System.setProperty(property, "false");
            assertFalse(inspect(channel, bytes(0x66)).dropped(), "disabled first packet must pass");
            assertFalse(inspect(channel, bytes(0x66)).dropped(), "disabled repeat must pass");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ClientboundCommandTreeDeduplicator.Candidate inspect(EmbeddedChannel channel, byte[] bytes) {
        ByteBuf buffer = buffer(bytes);
        try { return ClientboundCommandTreeDeduplicator.inspect(channel, COMMANDS, PacketFlow.CLIENTBOUND, buffer, 0); }
        finally { buffer.release(); }
    }

    private static ByteBuf buffer(byte[] bytes) { return Unpooled.wrappedBuffer(bytes.clone()); }
    private static byte[] bytes(int fill) { byte[] bytes = new byte[128 * 1024]; java.util.Arrays.fill(bytes, (byte) fill); return bytes; }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertFalse(boolean value, String message) { assertTrue(!value, message); }
}
