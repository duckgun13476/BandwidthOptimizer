package com.PinkCats.bandwidthoptimizer.recipe;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportNetworkChannel;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.stats.RecipeBookSettings;

import java.util.List;

/** Exercises real packet identities and encoder re-entry, not just the queue's internal state. */
public final class RecipeSyncOrderingRegressionMain {
    private static final Packet<?> TABLE = new ClientboundUpdateRecipesPacket(List.of());
    private static final Packet<?> BOOK = new ClientboundRecipePacket(
            ClientboundRecipePacket.State.INIT, List.of(), List.of(), new RecipeBookSettings());
    private static final Packet<?> AFTER = new ClientboundKeepAlivePacket(3L);

    public static void main(String[] args) throws Exception {
        Config.enablePersistentRecipeDelta = true;
        require(!RecipeSyncNegotiationGate.isRecipePacket(BOOK), "book must not become delta-eligible");
        verifyNormalAndRearm();
        verifyBounds(1024, 1);
        verifyBounds(1, 64 * 1024 * 1024);
        verifyDisabled();
        verifyImmutableReplay();
        verifyTimeoutAndClose();
        System.out.println("RECIPE_SYNC_ORDERING_REGRESSION_OK normal rearm stale capacity bytes disabled timeout close control");
    }

    private static EmbeddedChannel channel(int charge) {
        return new EmbeddedChannel(new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (msg instanceof Packet<?> packet && RecipeSyncNegotiationGate.tryQueue(ctx, packet, charge)) {
                    // Match the existing encoder gate contract (empty encoded output completes original write).
                    promise.trySuccess();
                } else {
                    super.write(ctx, msg, promise);
                }
            }
        });
    }

    private static void verifyNormalAndRearm() {
        EmbeddedChannel c = channel(128);
        long generation = RecipeSyncNegotiationGate.arm(c);
        c.writeOutbound(AFTER);
        require(c.readOutbound() == AFTER, "predecessor must pass before the first recipe");
        c.writeOutbound(TABLE, BOOK, AFTER);
        require(c.readOutbound() == null, "all successors must wait");
        var carrier = new ClientboundCustomPayloadPacket(ChannelTransportNetworkChannel.TRANSPORT_PAYLOAD_ID,
                new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer()));
        c.writeOutbound(carrier);
        require(c.readOutbound() == carrier, "BO control carrier must progress while held");
        carrier.getData().release();
        RecipeSyncNegotiationGate.complete(c, generation + 1);
        require(c.readOutbound() == null, "stale completion must not open gate");
        long next = RecipeSyncNegotiationGate.arm(c);
        expect(c, TABLE, BOOK, AFTER);
        c.writeOutbound(TABLE, BOOK, AFTER);
        require(c.readOutbound() == null, "new generation must hold its own sequence");
        RecipeSyncNegotiationGate.complete(c, generation);
        require(c.readOutbound() == null, "old completion must not release new generation");
        RecipeSyncNegotiationGate.complete(c, next);
        expect(c, TABLE, BOOK, AFTER);
        RecipeSyncNegotiationGate.complete(c, next);
        require(c.readOutbound() == null, "release must happen exactly once");
        c.finishAndReleaseAll();
    }

    private static void verifyBounds(int count, int charge) {
        EmbeddedChannel c = channel(charge);
        long generation = RecipeSyncNegotiationGate.arm(c);
        c.writeOutbound(TABLE);
        for (int i = 1; i < count; i++) c.writeOutbound(BOOK);
        require(c.readOutbound() == null, "at-limit queue must remain held");
        c.writeOutbound(AFTER);
        require(c.readOutbound() == TABLE, "capacity release must start with table");
        for (int i = 1; i < count; i++) require(c.readOutbound() == BOOK, "capacity FIFO successor");
        require(c.readOutbound() == AFTER && c.readOutbound() == null, "triggering packet must follow queue once");
        RecipeSyncNegotiationGate.complete(c, generation);
        require(c.readOutbound() == null, "late completion must not duplicate capacity release");
        c.finishAndReleaseAll();
    }

    private static void verifyDisabled() {
        EmbeddedChannel c = channel(128);
        RecipeSyncNegotiationGate.arm(c);
        c.writeOutbound(TABLE, BOOK);
        Config.enablePersistentRecipeDelta = false;
        c.writeOutbound(AFTER);
        expect(c, TABLE, BOOK, AFTER);
        Config.enablePersistentRecipeDelta = true;
        c.finishAndReleaseAll();
    }

    private static void verifyImmutableReplay() {
        int[] encodes = {0};
        EmbeddedChannel c = new EmbeddedChannel(new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (!(msg instanceof Packet<?> packet)) { super.write(ctx, msg, promise); return; }
                byte[] replay = RecipeSyncNegotiationGate.replayBytes(ctx, packet);
                io.netty.buffer.ByteBuf out = Unpooled.buffer();
                if (replay == null) {
                    encodes[0]++;
                    out.writeByte(packet == TABLE ? 7 : 9);
                } else {
                    out.writeBytes(replay);
                }
                if (RecipeSyncNegotiationGate.tryQueue(ctx, packet, out, 0)) {
                    require(out.readableBytes() == 0, "held bytes must be removed from encoder output");
                    out.writeByte(99); // Original buffer may be reused/mutated immediately.
                    out.release();
                    promise.trySuccess();
                } else {
                    ctx.write(out, promise);
                }
            }
        });
        long generation = RecipeSyncNegotiationGate.arm(c);
        c.writeOutbound(TABLE, BOOK);
        RecipeSyncNegotiationGate.complete(c, generation);
        var table = (io.netty.buffer.ByteBuf)c.readOutbound();
        var book = (io.netty.buffer.ByteBuf)c.readOutbound();
        require(table.readUnsignedByte() == 7 && book.readUnsignedByte() == 9, "snapshot contents/order must be exact");
        require(encodes[0] == 2, "held packets must not be serialized twice");
        table.release(); book.release();
        c.finishAndReleaseAll();
    }

    private static void verifyTimeoutAndClose() throws Exception {
        EmbeddedChannel c = channel(128);
        long generation = RecipeSyncNegotiationGate.arm(c);
        c.writeOutbound(TABLE, BOOK, AFTER);
        Thread.sleep(2050L);
        c.runScheduledPendingTasks();
        expect(c, TABLE, BOOK, AFTER);
        RecipeSyncNegotiationGate.complete(c, generation);
        require(c.readOutbound() == null, "timeout release exactly once");
        c.finishAndReleaseAll();
        EmbeddedChannel closed = channel(128);
        long closedGeneration = RecipeSyncNegotiationGate.arm(closed);
        closed.writeOutbound(TABLE, BOOK);
        closed.close();
        RecipeSyncNegotiationGate.complete(closed, closedGeneration);
        require(closed.readOutbound() == null, "closed connection must discard retained sequence");
        closed.finishAndReleaseAll();
    }

    private static void expect(EmbeddedChannel c, Packet<?>... packets) {
        c.runPendingTasks();
        for (Packet<?> packet : packets) require(c.readOutbound() == packet, "native packet identity/order changed");
        require(c.readOutbound() == null, "unexpected duplicate output");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
