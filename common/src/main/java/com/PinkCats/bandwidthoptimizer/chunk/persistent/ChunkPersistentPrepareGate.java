package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPersistentPrepareGate {

    public static final String WAIT_REASON_PREFIX = "await_persistent_cache_prepare:";
    private static final AttributeKey<PrepareGateState> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:persistent_prepare_gate");
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final int MAX_PENDING_COORDINATES = 4;
    private static final int MAX_QUEUED_PACKETS = 128;
    private static final long MAX_PENDING_BYTES = 32L * 1024L * 1024L;
    private static final long TIMEOUT_MILLIS = 400L;

    private ChunkPersistentPrepareGate() {}

    public static boolean tryQueuePendingCoordinatePacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            int encodedBytes
    ) {
        if (context == null
                || context.channel() == null
                || packet == null
                || protocolName == null
                || !"PLAY".equalsIgnoreCase(protocolName)) {
            return false;
        }
        PrepareGateState state = context.channel().attr(STATE_KEY).get();
        if (state == null || !state.hasPending()) {
            return false;
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket forgetPacket) {
            ChunkPos chunkPos = ChunkCoordinateCompat.forgetPosition(forgetPacket);
            if (chunkPos != null) {
                state.cancel(ChunkPacketCoordinate.ofChunk(
                        ChunkCoordinateCompat.x(chunkPos),
                        ChunkCoordinateCompat.z(chunkPos)
                ));
            }
            return false;
        }

        ChunkPacketDescriptor descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket(protocolName, packet);
        if (descriptor == null || descriptor.coordinate() == null || !descriptor.coordinate().present()) {
            return false;
        }
        QueueOutcome outcome = state.queueIfPending(
                descriptor.coordinate(),
                packet,
                Math.max(encodedBytes, 0),
                descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
        );
        if (!outcome.queued()) {
            return false;
        }
        if (outcome.releaseNow() != null) {
            replay(context.channel(), outcome.releaseNow());
        }
        return true;
    }

    public static String reserveIfUseful(
            ChannelHandlerContext context,
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerStateSnapshot peerSnapshot,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot,
            int originalEncodedBytes
    ) {
        if (context == null
                || context.channel() == null
                || descriptor == null
                || descriptor.hotspotKind() != ChunkHotspotKind.FULL_CHUNK
                || descriptor.coordinate() == null
                || !descriptor.coordinate().present()
                || fingerprint == null
                || fingerprint.hashHex() == null
                || fingerprint.hashHex().isBlank()
                || peerSnapshot == null
                || peerSnapshot.epoch() <= 0L
                || knownChunkSnapshot != null) {
            return "";
        }

        PrepareGateState state = context.channel().attr(STATE_KEY).get();
        if (state == null || !state.mightContain(descriptor.coordinate())) {
            return "";
        }
        if (state.consumeResumePermit(descriptor.coordinate())) {
            return "";
        }

        long token = state.reserve(
                peerSnapshot.epoch(),
                descriptor,
                fingerprint.hashHex(),
                Math.max(originalEncodedBytes, 0)
        );
        return token <= 0L ? "" : WAIT_REASON_PREFIX + Long.toUnsignedString(token);
    }

    public static boolean tryQueueWaitingPacket(
            ChannelHandlerContext context,
            Packet<?> packet,
            String traceReason
    ) {
        long token = parseToken(traceReason);
        if (token <= 0L || context == null || context.channel() == null || packet == null) {
            return false;
        }
        Channel channel = context.channel();
        PrepareGateState state = channel.attr(STATE_KEY).get();
        if (state == null) {
            return false;
        }
        PrepareRequest request = state.attach(token, packet);
        if (request == null) {
            return false;
        }
        if (request.markPrepareSent()) {
            boolean sent = ChunkTransportControlFrameSender.sendPersistentCachePrepare(
                    channel,
                    request.epoch,
                    request.token,
                    request.descriptor,
                    request.expectedHash,
                    request.encodedBytes,
                    state.scopeHash()
            );
            if (!sent) {
                channel.eventLoop().execute(() -> release(channel, token));
                return true;
            }
            channel.eventLoop().schedule(
                    () -> release(channel, token),
                    TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        }
        return true;
    }

    public static void installBloom(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] payloadBytes
    ) {
        if (context == null
                || context.channel() == null
                || frame == null
                || frame.operation() != ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM
                || !ChunkPersistentServerScope.isSafeScopeHash(frame.payloadHash())
                || !frame.payloadHash().equalsIgnoreCase(ChunkPersistentServerScope.currentScopeHash())) {
            return;
        }
        try {
            ChunkPersistentBloomCatalog catalog = ChunkPersistentBloomCatalog.decode(payloadBytes);
            getOrCreateState(context.channel()).installBloom(frame.payloadHash(), catalog);
        } catch (RuntimeException ignored) {
            getOrCreateState(context.channel()).clearBloom();
        }
    }

    public static void handleReady(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (!isValidResponse(context, frame, ChunkHotspotFrameOp.CACHE_READY)) {
            return;
        }
        PrepareGateState state = context.channel().attr(STATE_KEY).get();
        PrepareRequest request = state == null ? null : state.find(frame.observedPacketCount());
        if (!matches(request, state, frame)) {
            return;
        }
        ChunkPeerStateManager.recordPersistentClientManifest(context, frame);
        release(context.channel(), request.token);
    }

    public static void handleMiss(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (!isValidResponse(context, frame, ChunkHotspotFrameOp.CACHE_MISS)) {
            return;
        }
        PrepareGateState state = context.channel().attr(STATE_KEY).get();
        PrepareRequest request = state == null ? null : state.find(frame.observedPacketCount());
        if (!matches(request, state, frame)) {
            return;
        }
        release(context.channel(), request.token);
    }

    public static void clear(Channel channel) {
        if (channel != null) {
            channel.attr(STATE_KEY).set(null);
        }
    }

    private static boolean isValidResponse(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            ChunkHotspotFrameOp operation
    ) {
        return context != null
                && context.channel() != null
                && frame != null
                && frame.operation() == operation
                && frame.coordinate() != null
                && frame.coordinate().present()
                && frame.epoch() > 0L
                && frame.observedPacketCount() > 0L;
    }

    private static boolean matches(PrepareRequest request, PrepareGateState state, ChunkHotspotFrame frame) {
        return request != null
                && state != null
                && request.epoch == frame.epoch()
                && request.coordinate().equals(frame.coordinate())
                && state.scopeHash().equalsIgnoreCase(frame.baseSnapshotHash());
    }

    private static void release(Channel channel, long token) {
        if (channel == null) {
            return;
        }
        PrepareGateState state = channel.attr(STATE_KEY).get();
        PrepareRequest request = state == null ? null : state.release(token);
        replay(channel, request);
    }

    private static void replay(Channel channel, PrepareRequest request) {
        if (channel == null || request == null || request.queuedPackets.isEmpty()) {
            return;
        }
        Runnable task = () -> {
            for (Packet<?> queuedPacket : request.queuedPackets) {
                channel.write(queuedPacket);
            }
            channel.flush();
        };
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }

    private static PrepareGateState getOrCreateState(Channel channel) {
        PrepareGateState existing = channel.attr(STATE_KEY).get();
        if (existing != null) {
            return existing;
        }
        PrepareGateState created = new PrepareGateState();
        PrepareGateState raced = channel.attr(STATE_KEY).setIfAbsent(created);
        return raced == null ? created : raced;
    }

    private static long parseToken(String traceReason) {
        if (traceReason == null || !traceReason.startsWith(WAIT_REASON_PREFIX)) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(traceReason.substring(WAIT_REASON_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static final class PrepareGateState {

        private final LinkedHashMap<Long, PrepareRequest> pendingByToken = new LinkedHashMap<>();
        private final Map<ChunkPacketCoordinate, Long> tokenByCoordinate = new HashMap<>();
        private final Map<ChunkPacketCoordinate, Integer> resumePermits = new HashMap<>();
        private ChunkPersistentBloomCatalog bloomCatalog;
        private String scopeHash = "";
        private volatile int pendingCoordinateCount;
        private int queuedPackets;
        private long pendingBytes;

        private synchronized void installBloom(String scopeHash, ChunkPersistentBloomCatalog bloomCatalog) {
            this.scopeHash = scopeHash == null ? "" : scopeHash.toLowerCase(java.util.Locale.ROOT);
            this.bloomCatalog = bloomCatalog;
        }

        private synchronized void clearBloom() {
            this.bloomCatalog = null;
            this.scopeHash = "";
        }

        private synchronized String scopeHash() {
            return this.scopeHash;
        }

        private synchronized boolean mightContain(ChunkPacketCoordinate coordinate) {
            return this.bloomCatalog != null && this.bloomCatalog.mightContain(coordinate);
        }

        private boolean hasPending() {
            return this.pendingCoordinateCount > 0;
        }

        private synchronized boolean consumeResumePermit(ChunkPacketCoordinate coordinate) {
            Integer permits = this.resumePermits.get(coordinate);
            if (permits == null || permits <= 0) {
                return false;
            }
            if (permits == 1) {
                this.resumePermits.remove(coordinate);
            } else {
                this.resumePermits.put(coordinate, permits - 1);
            }
            return true;
        }

        private synchronized long reserve(
                long epoch,
                ChunkPacketDescriptor descriptor,
                String expectedHash,
                int encodedBytes
        ) {
            Long existingToken = this.tokenByCoordinate.get(descriptor.coordinate());
            if (existingToken != null && this.pendingByToken.containsKey(existingToken)) {
                PrepareRequest existing = this.pendingByToken.get(existingToken);
                if (this.queuedPackets >= MAX_QUEUED_PACKETS
                        || this.pendingBytes + encodedBytes > MAX_PENDING_BYTES) {
                    return 0L;
                }
                existing.addPendingBytes(encodedBytes);
                this.pendingBytes += encodedBytes;
                return existingToken;
            }
            if (this.pendingByToken.size() >= MAX_PENDING_COORDINATES
                    || this.queuedPackets >= MAX_QUEUED_PACKETS
                    || this.pendingBytes + encodedBytes > MAX_PENDING_BYTES) {
                return 0L;
            }
            long token = nextToken();
            PrepareRequest request = new PrepareRequest(token, epoch, descriptor, expectedHash, encodedBytes);
            this.pendingByToken.put(token, request);
            this.tokenByCoordinate.put(descriptor.coordinate(), token);
            this.pendingCoordinateCount = this.pendingByToken.size();
            this.pendingBytes += encodedBytes;
            return token;
        }

        private synchronized PrepareRequest attach(long token, Packet<?> packet) {
            PrepareRequest request = this.pendingByToken.get(token);
            if (request == null || this.queuedPackets >= MAX_QUEUED_PACKETS) {
                return null;
            }
            request.queuedPackets.add(packet);
            request.queuedFullPackets++;
            this.queuedPackets++;
            return request;
        }

        private synchronized QueueOutcome queueIfPending(
                ChunkPacketCoordinate coordinate,
                Packet<?> packet,
                int encodedBytes,
                boolean fullChunk
        ) {
            Long token = this.tokenByCoordinate.get(coordinate);
            PrepareRequest request = token == null ? null : this.pendingByToken.get(token);
            if (request == null) {
                return QueueOutcome.notPending();
            }
            int safeEncodedBytes = Math.max(encodedBytes, 0);
            if (this.queuedPackets < MAX_QUEUED_PACKETS
                    && this.pendingBytes + safeEncodedBytes <= MAX_PENDING_BYTES) {
                request.addQueuedPacket(packet, safeEncodedBytes, fullChunk);
                this.queuedPackets++;
                this.pendingBytes += safeEncodedBytes;
                return QueueOutcome.accepted();
            }

            detach(request);
            request.addQueuedPacket(packet, safeEncodedBytes, fullChunk);
            if (request.queuedFullPackets > 0) {
                this.resumePermits.merge(coordinate, request.queuedFullPackets, Integer::sum);
            }
            return QueueOutcome.release(request);
        }

        private synchronized void cancel(ChunkPacketCoordinate coordinate) {
            Long token = this.tokenByCoordinate.get(coordinate);
            PrepareRequest request = token == null ? null : this.pendingByToken.get(token);
            if (request != null) {
                detach(request);
            }
            this.resumePermits.remove(coordinate);
        }

        private synchronized PrepareRequest find(long token) {
            return this.pendingByToken.get(token);
        }

        private synchronized PrepareRequest release(long token) {
            PrepareRequest request = this.pendingByToken.get(token);
            if (request == null) {
                return null;
            }
            detach(request);
            if (request.queuedFullPackets > 0) {
                this.resumePermits.merge(request.coordinate(), request.queuedFullPackets, Integer::sum);
            }
            return request;
        }

        private void detach(PrepareRequest request) {
            this.pendingByToken.remove(request.token);
            this.tokenByCoordinate.remove(request.coordinate(), request.token);
            this.pendingCoordinateCount = this.pendingByToken.size();
            this.pendingBytes = Math.max(0L, this.pendingBytes - request.pendingBytes());
            this.queuedPackets = Math.max(0, this.queuedPackets - request.queuedPackets.size());
        }
    }

    private static final class PrepareRequest {

        private final long token;
        private final long epoch;
        private final ChunkPacketDescriptor descriptor;
        private final String expectedHash;
        private final int encodedBytes;
        private final ArrayDeque<Packet<?>> queuedPackets = new ArrayDeque<>();
        private long pendingBytes;
        private int queuedFullPackets;
        private boolean prepareSent;

        private PrepareRequest(
                long token,
                long epoch,
                ChunkPacketDescriptor descriptor,
                String expectedHash,
                int encodedBytes
        ) {
            this.token = token;
            this.epoch = epoch;
            this.descriptor = descriptor;
            this.expectedHash = expectedHash;
            this.encodedBytes = encodedBytes;
            this.pendingBytes = encodedBytes;
        }

        private ChunkPacketCoordinate coordinate() {
            return this.descriptor.coordinate();
        }

        private synchronized boolean markPrepareSent() {
            if (this.prepareSent) {
                return false;
            }
            this.prepareSent = true;
            return true;
        }

        private synchronized void addPendingBytes(int encodedBytes) {
            this.pendingBytes += Math.max(encodedBytes, 0);
        }

        private synchronized void addQueuedPacket(Packet<?> packet, int encodedBytes, boolean fullChunk) {
            this.queuedPackets.add(packet);
            this.pendingBytes += Math.max(encodedBytes, 0);
            if (fullChunk) {
                this.queuedFullPackets++;
            }
        }

        private synchronized long pendingBytes() {
            return this.pendingBytes;
        }
    }

    private record QueueOutcome(boolean queued, PrepareRequest releaseNow) {

        private static QueueOutcome notPending() {
            return new QueueOutcome(false, null);
        }

        private static QueueOutcome accepted() {
            return new QueueOutcome(true, null);
        }

        private static QueueOutcome release(PrepareRequest request) {
            return new QueueOutcome(true, request);
        }
    }

    private static long nextToken() {
        long token = NEXT_TOKEN.getAndIncrement();
        return token > 0L ? token : NEXT_TOKEN.updateAndGet(current -> current <= 0L ? 1L : current);
    }
}
