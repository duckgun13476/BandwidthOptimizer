package com.PinkCats.bandwidthoptimizer.recipe;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentServerScope;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RecipeSyncTransport {

    private static final String PACKET_CLASS = "bandwidthoptimizer.recipe.PersistentRecipeSync";
    private static final int MAX_ADVERTISED_BASES = 5;

    private RecipeSyncTransport() {}

    public static EncodeResult tryEncode(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] originalPacketBytes
    ) {
        String semanticHash = originalPacketBytes == null || originalPacketBytes.length == 0
                ? ""
                : RecipeSyncSemanticIdentity.resolve(packet, originalPacketBytes);
        return tryEncode(context, protocolName, packet, originalPacketBytes, semanticHash, null);
    }

    static EncodeResult tryEncode(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] originalPacketBytes,
            String semanticHash
    ) {
        return tryEncode(context, protocolName, packet, originalPacketBytes, semanticHash, null);
    }

    static EncodeResult tryEncode(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] originalPacketBytes,
            String semanticHash,
            RecipeSyncStructuralView structuralView
    ) {
        if (!RecipeSyncRuntimeConfig.isEnabled()
                || context == null
                || !"PLAY".equalsIgnoreCase(protocolName)
                || !RecipeSyncNegotiationGate.isRecipePacket(packet)
                || originalPacketBytes == null
                || originalPacketBytes.length == 0
                || !RecipeSyncDeltaCodec.isHash(semanticHash)) {
            return EncodeResult.bypass();
        }
        String scopeHash = ChunkPersistentServerScope.currentScopeHash();
        if (!RecipeSyncDeltaCodec.isHash(scopeHash)) {
            return EncodeResult.bypass();
        }
        String targetHash = RecipeSyncDeltaCodec.sha256(originalPacketBytes);
        ChannelTransportSession session = ChannelTransportStateManager.getOrCreateSession(context.channel());
        if (!session.isOutboundRecipeNegotiated()) {
            return EncodeResult.bypass();
        }
        ChannelTransportSession.RecipeBaseReference semanticBase =
                session.outboundRecipeReference(scopeHash, semanticHash);
        if (structuralView == null && (semanticBase == null || ExperientRuntimeFlags.isEnabled())) {
            structuralView = RecipeSyncSemanticIdentity.structuralView(packet, originalPacketBytes);
        }
        ChannelTransportSession.RecipeBase base = session.outboundRecipeBase();
        ChunkHotspotFrameOp operation = ChunkHotspotFrameOp.RECIPE_FULL;
        String baseHash = "";
        String transmittedHash = targetHash;
        int transmittedBytes = originalPacketBytes.length;
        byte[] payload = originalPacketBytes.clone();
        RecipeSyncTrafficStats.Mode transferMode = RecipeSyncTrafficStats.Mode.FULL;
        if (semanticBase != null && RecipeSyncDeltaCodec.isHash(semanticBase.recipeHash())
                && semanticBase.packetBytes() > 0
                && semanticBase.packetBytes() <= ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES) {
            byte[] delta = RecipeSyncDeltaCodec.encodeIdentity(semanticBase.packetBytes());
            operation = ChunkHotspotFrameOp.RECIPE_DELTA;
            baseHash = semanticBase.recipeHash();
            transmittedHash = semanticBase.recipeHash();
            transmittedBytes = semanticBase.packetBytes();
            payload = delta;
            transferMode = RecipeSyncTrafficStats.Mode.IDENTITY;
        } else {
            byte[] delta = base == null || !scopeHash.equals(base.scopeHash())
                    ? null
                    : RecipeSyncDeltaCodec.encodeStructured(base.copyPacketBytes(), structuralView);
            boolean structuralDelta = delta != null;
            if (delta == null && base != null && scopeHash.equals(base.scopeHash())) {
                delta = RecipeSyncDeltaCodec.encode(base.copyPacketBytes(), originalPacketBytes);
            }
            if (delta != null && delta.length + 128 < originalPacketBytes.length) {
                operation = ChunkHotspotFrameOp.RECIPE_DELTA;
                baseHash = base.recipeHash();
                payload = delta;
                transferMode = structuralDelta
                        ? RecipeSyncTrafficStats.Mode.STRUCTURAL_DELTA
                        : RecipeSyncTrafficStats.Mode.BYTE_DELTA;
            }
        }
        ChunkHotspotFrame frame = recipeFrame(
                operation,
                packet.getClass().getName(),
                transmittedBytes,
                baseHash,
                transmittedHash,
                scopeHash,
                semanticHash
        );
        byte[] encoded = ChunkTransportEnvelopeCodec.encodeEnvelope(new ChunkTransportEnvelope(frame, payload));
        RecipeSyncTrafficStats.record(
                transferMode,
                originalPacketBytes.length,
                payload.length,
                encoded.length
        );
        if (semanticBase == null) {
            session.setOutboundRecipeCandidate(scopeHash, targetHash, semanticHash, originalPacketBytes);
            RecipeSyncPersistentStore.storeServerAsync(scopeHash, semanticHash, originalPacketBytes);
        }
        RecipeSyncRunAllProbe.record(
                context.channel(), operation, semanticBase != null,
                originalPacketBytes.length, payload.length, targetHash, semanticHash,
                originalPacketBytes, structuralView);
        return new EncodeResult(true, operation, encoded, payload.length);
    }

    public static byte[] decodeDataFrame(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] payloadBytes
    ) throws IOException {
        if (context == null || frame == null || payloadBytes == null
                || (frame.operation() != ChunkHotspotFrameOp.RECIPE_FULL
                && frame.operation() != ChunkHotspotFrameOp.RECIPE_DELTA)) {
            throw new IOException("Invalid recipe sync frame");
        }
        ScopeIdentity identity = decodeScopeIdentity(frame.reason());
        String scopeHash = identity.scopeHash();
        String semanticHash = identity.semanticHash();
        String targetHash = frame.payloadHash();
        if (!RecipeSyncDeltaCodec.isHash(scopeHash) || !RecipeSyncDeltaCodec.isHash(semanticHash)
                || !RecipeSyncDeltaCodec.isHash(targetHash)
                || frame.originalEncodedBytes() <= 0
                || frame.originalEncodedBytes() > ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES) {
            throw new IOException("Invalid recipe sync frame metadata");
        }
        ChannelTransportSession session = ChannelTransportStateManager.getOrCreateSession(context.channel());
        byte[] restored;
        if (frame.operation() == ChunkHotspotFrameOp.RECIPE_FULL) {
            restored = payloadBytes.clone();
        } else {
            ChannelTransportSession.RecipeBase base = session.inboundRecipeBase(
                    scopeHash, frame.baseSnapshotHash());
            if (base == null) {
                throw new IOException("Recipe delta base is unavailable or does not match");
            }
            restored = RecipeSyncDeltaCodec.decode(
                    base.copyPacketBytes(),
                    payloadBytes,
                    ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES
            );
        }
        if (restored.length != frame.originalEncodedBytes()
                || !targetHash.equals(RecipeSyncDeltaCodec.sha256(restored))) {
            throw new IOException("Recipe sync result failed exact integrity validation");
        }
        session.setInboundRecipeBase(scopeHash, targetHash, semanticHash, restored);
        byte[] durableBytes = restored.clone();
        RecipeSyncPersistentStore.storeClientAsync(scopeHash, semanticHash, durableBytes).thenAccept(stored -> {
            if (stored) {
                runOnEventLoop(context.channel(), () -> ChunkTransportControlFrameSender.sendRecipeBaseReady(
                        context.channel(), scopeHash, targetHash));
            }
        });
        return restored;
    }

    public static void onServerScope(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null) {
            return;
        }
        String scopeHash = frame.payloadHash();
        long generation = frame.observedPacketCount();
        Channel channel = context.channel();
        ChannelTransportSession session = ChannelTransportStateManager.getOrCreateSession(channel);
        session.clearInboundRecipeBase();
        if (!RecipeSyncRuntimeConfig.isEnabled()) {
            ChunkTransportControlFrameSender.sendClientRecipeBase(channel, scopeHash, generation, "disabled");
            return;
        }
        RecipeSyncPersistentStore.loadRecentClientAsync(scopeHash).thenAccept(bases -> runOnEventLoop(channel, () -> {
            session.clearInboundRecipeBase();
            for (int index = bases.size() - 1; index >= 0; index--) {
                RecipeSyncPersistentStore.StoredBase base = bases.get(index);
                session.setInboundRecipeBase(
                        scopeHash, base.hash(), base.semanticHash(), base.copyPacketBytes());
            }
            ChunkTransportControlFrameSender.sendClientRecipeBase(
                    channel,
                    scopeHash,
                    generation,
                    encodeBaseHashes(bases)
            );
        }));
    }

    public static void onClientBaseAdvertisement(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (!RecipeSyncRuntimeConfig.isEnabled() || context == null || frame == null) {
            return;
        }
        Channel channel = context.channel();
        String scopeHash = frame.baseSnapshotHash();
        String advertisedHashes = frame.payloadHash();
        long generation = frame.fullSnapshotVersion();
        ChannelTransportSession session = ChannelTransportStateManager.getOrCreateSession(channel);
        if ("disabled".equals(advertisedHashes)) {
            session.setOutboundRecipeNegotiated(false);
            RecipeSyncNegotiationGate.complete(channel, generation);
            return;
        }
        session.setOutboundRecipeNegotiated(true);
        session.clearOutboundRecipeBase();
        List<ChannelTransportSession.RecipeBaseReference> references = decodeBaseReferences(advertisedHashes);
        session.setOutboundRecipeReferences(scopeHash, references);
        if (references.isEmpty()) {
            RecipeSyncNegotiationGate.complete(channel, generation);
            return;
        }
        RecipeSyncPersistentStore.loadServerAsync(scopeHash, references.get(0).recipeHash()).thenAccept(base ->
                runOnEventLoop(channel, () -> {
            if (base != null) {
                session.setOutboundRecipeBase(
                        scopeHash, base.hash(), base.semanticHash(), base.copyPacketBytes());
            }
            RecipeSyncNegotiationGate.complete(channel, generation);
        }));
    }

    public static void onRecipeBaseReady(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (!RecipeSyncRuntimeConfig.isEnabled() || context == null || frame == null) {
            return;
        }
        ChannelTransportStateManager.getOrCreateSession(context.channel())
                .promoteOutboundRecipeCandidate(frame.baseSnapshotHash(), frame.payloadHash());
    }

    public static boolean isRecipeOperation(ChunkHotspotFrameOp operation) {
        return operation == ChunkHotspotFrameOp.RECIPE_FULL
                || operation == ChunkHotspotFrameOp.RECIPE_DELTA
                || operation == ChunkHotspotFrameOp.CLIENT_RECIPE_BASE
                || operation == ChunkHotspotFrameOp.RECIPE_BASE_READY;
    }

    private static String encodeBaseHashes(List<RecipeSyncPersistentStore.StoredBase> bases) {
        if (bases == null || bases.isEmpty()) {
            return "";
        }
        ArrayList<String> hashes = new ArrayList<>();
        for (RecipeSyncPersistentStore.StoredBase base : bases) {
            if (base != null && RecipeSyncDeltaCodec.isHash(base.hash()) && hashes.size() < MAX_ADVERTISED_BASES) {
                hashes.add(base.semanticHash() + ":" + base.hash() + ":" + base.packetBytes().length);
            }
        }
        return String.join(",", hashes);
    }

    private static List<ChannelTransportSession.RecipeBaseReference> decodeBaseReferences(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        String[] values = encoded.split(",", -1);
        if (values.length > MAX_ADVERTISED_BASES) {
            return List.of();
        }
        ArrayList<ChannelTransportSession.RecipeBaseReference> references = new ArrayList<>(values.length);
        for (String value : values) {
            String[] parts = value.split(":", -1);
            if (parts.length != 3 || !RecipeSyncDeltaCodec.isHash(parts[0])
                    || !RecipeSyncDeltaCodec.isHash(parts[1])) {
                return List.of();
            }
            int packetBytes;
            try {
                packetBytes = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
                return List.of();
            }
            if (packetBytes <= 0 || packetBytes > ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES
                    || references.stream().anyMatch(reference -> reference.semanticHash().equals(parts[0]))) {
                return List.of();
            }
            references.add(new ChannelTransportSession.RecipeBaseReference(parts[0], parts[1], packetBytes));
        }
        return List.copyOf(references);
    }

    private static ChunkHotspotFrame recipeFrame(
            ChunkHotspotFrameOp operation,
            String packetClassName,
            int originalBytes,
            String baseHash,
            String targetHash,
            String scopeHash,
            String semanticHash
    ) {
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                operation,
                0L,
                0L,
                "PLAY",
                packetClassName == null ? PACKET_CLASS : packetClassName,
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                ChunkPacketCoordinate.unknown(),
                originalBytes,
                0L,
                0L,
                baseHash == null ? "" : baseHash,
                targetHash,
                0L,
                scopeHash + ":" + semanticHash
        );
    }

    private static ScopeIdentity decodeScopeIdentity(String encoded) {
        if (encoded == null) {
            return ScopeIdentity.invalid();
        }
        int separator = encoded.indexOf(':');
        if (separator <= 0 || separator == encoded.length() - 1) {
            return ScopeIdentity.invalid();
        }
        return new ScopeIdentity(encoded.substring(0, separator), encoded.substring(separator + 1));
    }

    private static void runOnEventLoop(Channel channel, Runnable action) {
        if (channel == null || !channel.isOpen()) {
            return;
        }
        if (channel.eventLoop().inEventLoop()) {
            action.run();
        } else {
            channel.eventLoop().execute(action);
        }
    }

    public record EncodeResult(boolean applied, ChunkHotspotFrameOp operation, byte[] encodedBytes, int payloadBytes) {
        public EncodeResult {
            encodedBytes = encodedBytes == null ? null : encodedBytes.clone();
        }

        static EncodeResult bypass() {
            return new EncodeResult(false, null, null, 0);
        }

        public byte[] copyEncodedBytes() {
            return this.encodedBytes == null ? null : this.encodedBytes.clone();
        }
    }

    private record ScopeIdentity(String scopeHash, String semanticHash) {
        private static ScopeIdentity invalid() {
            return new ScopeIdentity("", "");
        }
    }
}
