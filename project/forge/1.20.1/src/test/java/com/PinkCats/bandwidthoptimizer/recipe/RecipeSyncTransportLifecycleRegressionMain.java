package com.PinkCats.bandwidthoptimizer.recipe;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentServerScope;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class RecipeSyncTransportLifecycleRegressionMain {

    private RecipeSyncTransportLifecycleRegressionMain() {}

    public static void main(String[] arguments) throws Exception {
        Path outputRoot = Path.of("build", "recipe-sync-transport-regression").toAbsolutePath();
        System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, outputRoot.toString());
        Config.enablePersistentRecipeDelta = true;

        byte[] baseBytes = sample(2 * 1024 * 1024, 17L);
        byte[] targetBytes = baseBytes.clone();
        byte[] changed = sample(96 * 1024, 31L);
        System.arraycopy(changed, 0, targetBytes, 480 * 1024, changed.length);
        String baseSemantic = RecipeSyncDeltaCodec.sha256("semantic-base".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String targetSemantic = RecipeSyncDeltaCodec.sha256("semantic-target".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(List.of());
        verifyNegotiationGate(packet);
        EmbeddedChannel server = channel();
        EmbeddedChannel client = channel();
        ChannelHandlerContext serverContext = server.pipeline().firstContext();
        ChannelHandlerContext clientContext = client.pipeline().firstContext();
        ChannelTransportSession serverSession = ChannelTransportStateManager.getOrCreateSession(server);
        serverSession.setOutboundRecipeNegotiated(true);

        RecipeSyncTransport.EncodeResult first = RecipeSyncTransport.tryEncode(
                serverContext, "PLAY", packet, baseBytes, baseSemantic);
        require(first.applied() && first.operation() == ChunkHotspotFrameOp.RECIPE_FULL,
                "missing base must send one full recipe frame");
        ChunkTransportEnvelope firstEnvelope = ChunkTransportEnvelopeCodec.decodeEnvelope(first.copyEncodedBytes());
        byte[] firstRestored = RecipeSyncTransport.decodeDataFrame(
                clientContext, firstEnvelope.frame(), firstEnvelope.copyOriginalPacketBytes());
        require(Arrays.equals(baseBytes, firstRestored), "full recipe frame must restore exact bytes");

        String scopeHash = ChunkPersistentServerScope.currentScopeHash();
        String baseHash = RecipeSyncDeltaCodec.sha256(baseBytes);
        require(RecipeSyncPersistentStore.storeServerAsync(scopeHash, baseSemantic, baseBytes).get(),
                "server full base must become durable");
        RecipeSyncTransport.onRecipeBaseReady(serverContext, readyFrame(scopeHash, baseHash));
        ChannelTransportSession.RecipeBase promoted = serverSession.outboundRecipeBase();
        require(promoted != null && baseHash.equals(promoted.recipeHash()),
                "client READY must promote the exact full candidate");

        RecipeSyncTransport.EncodeResult second = RecipeSyncTransport.tryEncode(
                serverContext, "PLAY", packet, targetBytes, targetSemantic);
        require(second.applied() && second.operation() == ChunkHotspotFrameOp.RECIPE_DELTA,
                "a changed recipe sync with a durable base must use delta");
        ChunkTransportEnvelope secondEnvelope = ChunkTransportEnvelopeCodec.decodeEnvelope(second.copyEncodedBytes());
        byte[] secondRestored = RecipeSyncTransport.decodeDataFrame(
                clientContext, secondEnvelope.frame(), secondEnvelope.copyOriginalPacketBytes());
        require(Arrays.equals(targetBytes, secondRestored), "delta recipe frame must restore exact bytes");
        require(second.payloadBytes() < targetBytes.length / 4,
                "localized recipe changes must produce a small delta");
        String targetHash = RecipeSyncDeltaCodec.sha256(targetBytes);
        require(RecipeSyncPersistentStore.storeServerAsync(scopeHash, targetSemantic, targetBytes).get(),
                "changed server base must become durable");
        RecipeSyncTransport.onRecipeBaseReady(serverContext, readyFrame(scopeHash, targetHash));

        RecipeSyncTransport.EncodeResult returned = RecipeSyncTransport.tryEncode(
                serverContext, "PLAY", packet, baseBytes, baseSemantic);
        require(returned.applied() && returned.operation() == ChunkHotspotFrameOp.RECIPE_DELTA,
                "returning to an older retained recipe tree must use an exact reference delta");
        require(returned.payloadBytes() <= 16,
                "an exact retained recipe tree must not be diffed or retransmitted");
        ChunkTransportEnvelope returnedEnvelope = ChunkTransportEnvelopeCodec.decodeEnvelope(returned.copyEncodedBytes());
        byte[] returnedRestored = RecipeSyncTransport.decodeDataFrame(
                clientContext, returnedEnvelope.frame(), returnedEnvelope.copyOriginalPacketBytes());
        require(Arrays.equals(baseBytes, returnedRestored),
                "an older retained recipe tree must restore exact original bytes");

        require(RecipeSyncPersistentStore.storeClientAsync(scopeHash, baseSemantic, baseBytes).get(),
                "client must persist the older recipe tree");
        require(RecipeSyncPersistentStore.storeClientAsync(scopeHash, targetSemantic, targetBytes).get(),
                "client must persist the latest recipe tree");
        EmbeddedChannel reconnectedServer = channel();
        EmbeddedChannel reconnectedClient = channel();
        ChannelHandlerContext reconnectedServerContext = reconnectedServer.pipeline().firstContext();
        ChannelHandlerContext reconnectedClientContext = reconnectedClient.pipeline().firstContext();
        ChannelTransportSession reconnectedServerSession =
                ChannelTransportStateManager.getOrCreateSession(reconnectedServer);
        ChannelTransportSession reconnectedClientSession =
                ChannelTransportStateManager.getOrCreateSession(reconnectedClient);

        RecipeSyncTransport.onServerScope(reconnectedClientContext, serverScopeFrame(scopeHash));
        await(() -> reconnectedClientSession.inboundRecipeBase(scopeHash, baseHash) != null
                        && reconnectedClientSession.inboundRecipeBase(scopeHash, targetHash) != null,
                reconnectedClient, "reconnected client must preload both durable recipe trees");
        RecipeSyncTransport.onClientBaseAdvertisement(reconnectedServerContext,
                clientBaseFrame(scopeHash,
                        targetSemantic + ":" + targetHash + ":" + targetBytes.length + ","
                                + baseSemantic + ":" + baseHash + ":" + baseBytes.length));
        await(() -> reconnectedServerSession.outboundRecipeReference(scopeHash, baseSemantic) != null
                        && reconnectedServerSession.outboundRecipeReference(scopeHash, targetSemantic) != null
                        && reconnectedServerSession.outboundRecipeBase() != null,
                reconnectedServer, "reconnected server must accept both advertised recipe trees");

        RecipeSyncTransport.EncodeResult reconnected = RecipeSyncTransport.tryEncode(
                reconnectedServerContext, "PLAY", packet, targetBytes, baseSemantic);
        require(reconnected.applied() && reconnected.operation() == ChunkHotspotFrameOp.RECIPE_DELTA
                        && reconnected.payloadBytes() <= 16,
                "a reconnected backend must resolve an exact retained tree without retransmission");
        ChunkTransportEnvelope reconnectedEnvelope =
                ChunkTransportEnvelopeCodec.decodeEnvelope(reconnected.copyEncodedBytes());
        require(Arrays.equals(baseBytes, RecipeSyncTransport.decodeDataFrame(
                        reconnectedClientContext, reconnectedEnvelope.frame(),
                        reconnectedEnvelope.copyOriginalPacketBytes())),
                "reconnected exact recipe reference must restore original bytes");

        byte[] unseenBytes = targetBytes.clone();
        byte[] unseenChange = sample(32 * 1024, 47L);
        System.arraycopy(unseenChange, 0, unseenBytes, 960 * 1024, unseenChange.length);
        RecipeSyncTransport.EncodeResult unseen = RecipeSyncTransport.tryEncode(
                reconnectedServerContext, "PLAY", packet, unseenBytes,
                RecipeSyncDeltaCodec.sha256("semantic-unseen".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        require(unseen.applied() && unseen.operation() == ChunkHotspotFrameOp.RECIPE_DELTA,
                "a new recipe tree beyond the advertised set must still use the latest retained base");
        ChunkTransportEnvelope unseenEnvelope = ChunkTransportEnvelopeCodec.decodeEnvelope(unseen.copyEncodedBytes());
        require(Arrays.equals(unseenBytes, RecipeSyncTransport.decodeDataFrame(
                        reconnectedClientContext, unseenEnvelope.frame(), unseenEnvelope.copyOriginalPacketBytes())),
                "a new recipe tree delta must restore exact original bytes");
        require(unseen.payloadBytes() < unseenBytes.length / 4,
                "a new recipe tree must not fall back to a full transfer while a valid base exists");

        EmbeddedChannel rebuiltServer = channel();
        ChannelTransportSession rebuiltSession = ChannelTransportStateManager.getOrCreateSession(rebuiltServer);
        rebuiltSession.setOutboundRecipeNegotiated(true);
        RecipeSyncTransport.EncodeResult rebuilt = RecipeSyncTransport.tryEncode(
                rebuiltServer.pipeline().firstContext(), "PLAY", packet, targetBytes, targetSemantic);
        require(rebuilt.applied() && rebuilt.operation() == ChunkHotspotFrameOp.RECIPE_FULL,
                "a new session without the advertised base must rebuild with one full frame");

        Config.enablePersistentRecipeDelta = false;
        require(!RecipeSyncTransport.tryEncode(serverContext, "PLAY", packet, targetBytes, targetSemantic).applied(),
                "disabled recipe delta must preserve the existing transport path");
        Config.enablePersistentRecipeDelta = true;

        server.close();
        client.close();
        rebuiltServer.close();
        reconnectedServer.close();
        reconnectedClient.close();
        System.out.println("RECIPE_SYNC_TRANSPORT_LIFECYCLE_REGRESSION_OK"
                + " fullBytes=" + first.payloadBytes()
                + " deltaBytes=" + second.payloadBytes()
                + " returnBytes=" + returned.payloadBytes());
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new ChannelDuplexHandler());
    }

    private static void verifyNegotiationGate(ClientboundUpdateRecipesPacket packet) {
        require(!RecipeSyncNegotiationGate.isRecipePacket(new ClientboundRecipePacket()),
                "recipe-book state packets must remain on the normal BO transport path");
        EmbeddedChannel gateChannel = channel();
        ChannelHandlerContext context = gateChannel.pipeline().firstContext();
        long firstGeneration = RecipeSyncNegotiationGate.arm(gateChannel);
        require(RecipeSyncNegotiationGate.tryQueue(context, packet, 128),
                "armed recipe gate must consume the recipe packet");
        require(gateChannel.readOutbound() == null, "queued recipe packet must not be sent before negotiation");
        RecipeSyncNegotiationGate.complete(gateChannel, firstGeneration + 1L);
        gateChannel.runPendingTasks();
        require(gateChannel.readOutbound() == null, "stale generation must not release recipe packets");

        long secondGeneration = RecipeSyncNegotiationGate.arm(gateChannel);
        gateChannel.runPendingTasks();
        require(gateChannel.readOutbound() == packet, "rearming must release the previous generation in order");
        require(RecipeSyncNegotiationGate.tryQueue(context, packet, 128),
                "rearmed recipe gate must consume the next recipe packet");
        RecipeSyncNegotiationGate.complete(gateChannel, secondGeneration);
        gateChannel.runPendingTasks();
        require(gateChannel.readOutbound() == packet, "matching generation must release the recipe packet");

        Config.enablePersistentRecipeDelta = false;
        require(!RecipeSyncNegotiationGate.tryQueue(context, packet, 128),
                "disabled recipe gate must not consume packets");
        Config.enablePersistentRecipeDelta = true;
        gateChannel.close();
    }

    private static byte[] sample(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static ChunkHotspotFrame readyFrame(String scopeHash, String recipeHash) {
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.RECIPE_BASE_READY,
                0L,
                0L,
                "PLAY",
                "bandwidthoptimizer.recipe.RecipeBaseReady",
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                ChunkPacketCoordinate.unknown(),
                0,
                0L,
                0L,
                scopeHash,
                recipeHash,
                0L,
                "recipe_base_ready"
        );
    }

    private static ChunkHotspotFrame serverScopeFrame(String scopeHash) {
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.SERVER_CACHE_SCOPE,
                0L,
                1L,
                "PLAY",
                "bandwidthoptimizer.chunk.transport.ServerCacheScope",
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                ChunkPacketCoordinate.unknown(),
                0,
                0L,
                0L,
                scopeHash,
                scopeHash,
                0L,
                "server_cache_scope"
        );
    }

    private static ChunkHotspotFrame clientBaseFrame(String scopeHash, String recipeHashes) {
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.CLIENT_RECIPE_BASE,
                0L,
                0L,
                "PLAY",
                "bandwidthoptimizer.recipe.ClientRecipeBase",
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                ChunkPacketCoordinate.unknown(),
                0,
                1L,
                0L,
                scopeHash,
                recipeHashes,
                0L,
                "recipe_base_advertisement"
        );
    }

    private static void await(Check check, EmbeddedChannel channel, String message) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            channel.runPendingTasks();
            if (check.test()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class ClientboundRecipePacket {}

    @FunctionalInterface
    private interface Check {
        boolean test();
    }
}
