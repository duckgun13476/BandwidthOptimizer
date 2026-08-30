package com.PinkCats.bandwidthoptimizer.channel.packet;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingEpochGate;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.PacketFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientboundCommandTreeDeduplicatorRegressionMain {

    private static final String COMMANDS_PACKET =
            "net.minecraft.network.protocol.game.ClientboundCommandsPacket";
    private static final String LOGIN_PACKET =
            "net.minecraft.network.protocol.game.ClientboundLoginPacket";
    private static final String START_CONFIGURATION_PACKET =
            "net.minecraft.network.protocol.configuration.ClientboundStartConfigurationPacket";
    private static final int PREFIX_BYTES = 3;
    private static final int COMMAND_TREE_BYTES = 130 * 1024;

    private ClientboundCommandTreeDeduplicatorRegressionMain() {}

    public static void main(String[] args) throws Exception {
        String propertyName = Config.RuntimeProperty.Transport.COMMAND_TREE_DEDUP_ENABLED;
        String previousProperty = System.getProperty(propertyName);
        try {
            System.clearProperty(propertyName);
            verifyContentAndEpochSemantics(propertyName);
            verifyConcurrentFirstWriterWins();
            verifyKeepAliveCanPassBoOwnedQueues();
            System.out.println("Clientbound command-tree deduplication and KeepAlive priority regression passed");
        } finally {
            restoreProperty(propertyName, previousProperty);
        }
    }

    private static void verifyContentAndEpochSemantics(String propertyName) {
        ClientboundCommandTreeDeduplicator.resetTelemetryForTests();
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();
        byte[] treeA = commandTreeBytes(0x31);
        byte[] treeB = commandTreeBytes(0x57);

        try {
            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, false, "first tree");
            assertDropsComposite(firstChannel, treeA, "byte-identical tree");
            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeB, false, "same-length changed tree");
            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, false, "changed-back tree");
            assertDrops(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, "second identical tree");

            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.SERVERBOUND, treeA, false, "wrong packet flow");
            assertDrops(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, "wrong flow must not reset state");

            assertPasses(firstChannel, LOGIN_PACKET, PacketFlow.CLIENTBOUND, new byte[] {1}, false, "login boundary");
            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, false, "first tree after login");
            assertPasses(
                    firstChannel,
                    START_CONFIGURATION_PACKET,
                    PacketFlow.CLIENTBOUND,
                    new byte[] {2},
                    false,
                    "configuration boundary"
            );
            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, false, "first tree after reconfiguration");

            System.setProperty(propertyName, "false");
            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, false, "disabled first tree");
            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, false, "disabled duplicate tree");
            System.setProperty(propertyName, "true");
            assertPasses(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, false, "re-enabled first tree");
            assertDrops(firstChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, "re-enabled duplicate tree");

            assertPasses(secondChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, false, "independent channel first tree");
            assertDrops(secondChannel, COMMANDS_PACKET, PacketFlow.CLIENTBOUND, treeA, "independent channel duplicate tree");

            ClientboundCommandTreeDeduplicator.TelemetrySnapshot snapshot =
                    ClientboundCommandTreeDeduplicator.telemetrySnapshot();
            assertEquals(12L, snapshot.hashedTrees(), "hashed tree count");
            assertEquals(12L * treeA.length, snapshot.hashedBytes(), "hashed byte count");
            assertEquals(7L, snapshot.sentTrees(), "sent tree count");
            assertEquals(5L, snapshot.deduplicatedTrees(), "deduplicated tree count");
            assertEquals(5L * treeA.length, snapshot.deduplicatedBytes(), "deduplicated byte count");
            assertEquals(2L, snapshot.resetEpochs(), "connection epoch reset count");
            assertEquals(0L, snapshot.hashFailures(), "hash failure count");
        } finally {
            firstChannel.finishAndReleaseAll();
            secondChannel.finishAndReleaseAll();
        }
    }

    private static void verifyConcurrentFirstWriterWins() throws Exception {
        ClientboundCommandTreeDeduplicator.resetTelemetryForTests();
        EmbeddedChannel channel = new EmbeddedChannel();
        byte[] tree = commandTreeBytes(0x6d);
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger dropped = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    ByteBuf buffer = encodedBuffer(tree);
                    try {
                        if (ClientboundCommandTreeDeduplicator.tryDropDuplicate(
                                channel,
                                COMMANDS_PACKET,
                                PacketFlow.CLIENTBOUND,
                                buffer,
                                PREFIX_BYTES
                        )) {
                            dropped.incrementAndGet();
                        } else {
                            sent.incrementAndGet();
                        }
                    } finally {
                        buffer.release();
                    }
                }));
            }
            await(ready);
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10L, TimeUnit.SECONDS);
            }
            assertEquals(1L, sent.get(), "concurrent first send count");
            assertEquals(workers - 1L, dropped.get(), "concurrent duplicate count");
        } finally {
            start.countDown();
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("concurrency regression workers did not terminate");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while stopping concurrency regression workers", exception);
            }
            channel.finishAndReleaseAll();
        }
    }

    private static void verifyKeepAliveCanPassBoOwnedQueues() {
        assertTrue(
                ChannelTransportBypassPacketList.mayOvertakePendingTransportBatch(
                        "net.minecraft.network.protocol.common.ClientboundKeepAlivePacket"
                ),
                "modern KeepAlive must overtake a pending BO batch"
        );
        assertTrue(
                ChannelTransportBypassPacketList.mayOvertakePendingTransportBatch(
                        "net.minecraft.network.protocol.game.ClientboundKeepAlivePacket"
                ),
                "legacy KeepAlive must overtake a pending BO batch"
        );
        assertFalse(
                ChannelTransportBypassPacketList.mayOvertakePendingTransportBatch(COMMANDS_PACKET),
                "command trees must retain normal packet ordering"
        );

        AtomicReference<ChannelHandlerContext> contextReference = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext context) {
                contextReference.set(context);
            }
        });
        ChannelHandlerContext context = contextReference.get();
        if (context == null) {
            throw new AssertionError("embedded channel did not expose an outbound context");
        }

        ByteBuf keepAlive = Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
        ByteBuf orderedState = Unpooled.wrappedBuffer(new byte[] {4, 5, 6});
        try {
            ChannelTransportStreamingEpochGate.closeForEpoch(channel, 9, 3);
            assertFalse(
                    ChannelTransportStreamingEpochGate.deferIfClosed(context, true, keepAlive, 0),
                    "KeepAlive must not wait for a streaming epoch acknowledgement"
            );
            assertEquals(3L, keepAlive.readableBytes(), "KeepAlive bytes must remain available to Netty");
            assertTrue(
                    ChannelTransportStreamingEpochGate.deferIfClosed(context, false, orderedState, 0),
                    "ordered state must remain behind the streaming epoch gate"
            );
            assertEquals(0L, orderedState.readableBytes(), "deferred state bytes must leave the encoder buffer");
            ChannelTransportStreamingEpochGate.release(channel);
            ByteBuf replayed = channel.readOutbound();
            if (replayed == null) {
                throw new AssertionError("streaming epoch gate did not replay ordered state");
            }
            try {
                assertEquals(3L, replayed.readableBytes(), "replayed ordered state byte count");
                assertEquals(4L, replayed.readUnsignedByte(), "replayed ordered state first byte");
            } finally {
                replayed.release();
            }
        } finally {
            keepAlive.release();
            orderedState.release();
            ChannelTransportStreamingEpochGate.clear(channel);
            channel.finishAndReleaseAll();
        }
    }

    private static void assertDropsComposite(EmbeddedChannel channel, byte[] tree, String label) {
        byte[] prefix = new byte[] {0x11, 0x22, 0x33};
        int split = tree.length / 2;
        CompositeByteBuf buffer = Unpooled.compositeBuffer();
        buffer.addComponents(
                true,
                Unpooled.wrappedBuffer(prefix),
                Unpooled.wrappedBuffer(tree, 0, split),
                Unpooled.wrappedBuffer(tree, split, tree.length - split)
        );
        try {
            boolean dropped = ClientboundCommandTreeDeduplicator.tryDropDuplicate(
                    channel,
                    COMMANDS_PACKET,
                    PacketFlow.CLIENTBOUND,
                    buffer,
                    PREFIX_BYTES
            );
            assertTrue(dropped, label + " must be dropped");
            assertEquals(PREFIX_BYTES, buffer.writerIndex(), label + " writer index");
        } finally {
            buffer.release();
        }
    }

    private static void assertDrops(
            EmbeddedChannel channel,
            String packetClassName,
            PacketFlow packetFlow,
            byte[] payload,
            String label
    ) {
        assertPasses(channel, packetClassName, packetFlow, payload, true, label);
    }

    private static void assertPasses(
            EmbeddedChannel channel,
            String packetClassName,
            PacketFlow packetFlow,
            byte[] payload,
            boolean expectedDrop,
            String label
    ) {
        ByteBuf buffer = encodedBuffer(payload);
        int expectedWriterIndex = expectedDrop ? PREFIX_BYTES : PREFIX_BYTES + payload.length;
        try {
            boolean dropped = ClientboundCommandTreeDeduplicator.tryDropDuplicate(
                    channel,
                    packetClassName,
                    packetFlow,
                    buffer,
                    PREFIX_BYTES
            );
            if (dropped != expectedDrop) {
                throw new AssertionError(label + ": expectedDrop=" + expectedDrop + ", actualDrop=" + dropped);
            }
            assertEquals(expectedWriterIndex, buffer.writerIndex(), label + " writer index");
            assertEquals(0x11L, buffer.getUnsignedByte(0), label + " prefix must remain intact");
        } finally {
            buffer.release();
        }
    }

    private static ByteBuf encodedBuffer(byte[] payload) {
        ByteBuf buffer = Unpooled.directBuffer(PREFIX_BYTES + payload.length);
        buffer.writeByte(0x11);
        buffer.writeByte(0x22);
        buffer.writeByte(0x33);
        buffer.writeBytes(payload);
        return buffer;
    }

    private static byte[] commandTreeBytes(int seed) {
        byte[] bytes = new byte[COMMAND_TREE_BYTES];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index * 31);
        }
        return bytes;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10L, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out while waiting for regression barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for regression barrier", exception);
        }
    }

    private static void restoreProperty(String propertyName, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, previousValue);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
