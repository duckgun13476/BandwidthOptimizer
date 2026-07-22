package com.PinkCats.bandwidthoptimizer.gate.recovery;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundSectionBlocksUpdatePacketAccessor;
import io.netty.channel.Channel;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Coalesces background vanilla block-state updates into a final-position ledger. */
public final class VanillaBlockStateRecoveryPolicy extends IdleGateRecoveryPolicy {

    private static final int MAX_PENDING_POSITIONS_PER_PLAYER = 8_192;
    private final ConcurrentHashMap<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final AtomicLong capturedPackets = new AtomicLong();
    private final AtomicLong capturedPositions = new AtomicLong();
    private final AtomicLong restoredPositions = new AtomicLong();
    private final AtomicLong skippedPositions = new AtomicLong();

    public boolean tryCaptureBlockUpdate(Channel channel, ClientboundBlockUpdatePacket packet) {
        if (packet == null) {
            return false;
        }
        return capture(channel, state -> state.remember(packet.getPos().asLong()));
    }

    public boolean tryCaptureSectionBlocksUpdate(Channel channel, ClientboundSectionBlocksUpdatePacket packet) {
        if (packet == null) {
            return false;
        }
        ClientboundSectionBlocksUpdatePacketAccessor accessor = (ClientboundSectionBlocksUpdatePacketAccessor) packet;
        SectionPos sectionPos = accessor.bandwidthoptimizer$getSectionPos();
        short[] positions = accessor.bandwidthoptimizer$getPositions();
        if (sectionPos == null || positions == null || positions.length == 0) {
            return false;
        }
        return capture(channel, state -> state.remember(sectionPos, positions));
    }

    public boolean tryCaptureBlockEntityData(Channel channel, ClientboundBlockEntityDataPacket packet) {
        if (channel == null || packet == null) {
            return false;
        }
        ServerPlayer player = IdleGateServerState.resolvePlayer(channel);
        PlayerState state = player == null ? null : states.get(player.getUUID());
        return state != null && state.contains(packet.getPos().asLong());
    }

    public void discardChunk(ServerPlayer player, ChunkPos chunkPos) {
        if (player == null || chunkPos == null) {
            return;
        }
        PlayerState state = states.get(player.getUUID());
        if (state != null) {
            state.discardChunk(chunkPos);
        }
    }

    @Override
    public void restore(ServerPlayer player) {
        if (player != null) {
            restore(states.remove(player.getUUID()));
        }
    }

    @Override
    public void onServerTick() {
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            PlayerState state = entry.getValue();
            ServerPlayer player = state.player();
            if (player == null
                    || IdleGateServerState.snapshot(player).mode().suppressesWorldPresentation()
                    || !states.remove(entry.getKey(), state)) {
                continue;
            }
            restore(state);
        }
    }

    @Override
    public void discard(ServerPlayer player) {
        if (player != null) {
            states.remove(player.getUUID());
        }
    }

    private boolean capture(Channel channel, CaptureOperation operation) {
        if (channel == null || operation == null) {
            return false;
        }
        ServerPlayer player = IdleGateServerState.resolvePlayer(channel);
        if (player == null) {
            return false;
        }
        PlayerState state = states.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        CaptureResult result = operation.capture(state.bind(player));
        if (!result.captured()) {
            return false;
        }
        capturedPackets.incrementAndGet();
        capturedPositions.addAndGet(result.positions());
        return true;
    }

    private void restore(PlayerState state) {
        if (state == null || state.player() == null) {
            return;
        }
        ServerPlayer player = state.player();
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null) {
            return;
        }

        LongArrayList positions = state.drain();
        Map<Long, SectionRestoreGroup> groups = new HashMap<>();
        LongArrayList restoredBlockEntityPositions = new LongArrayList(positions.size());
        for (LongIterator iterator = positions.iterator(); iterator.hasNext();) {
            BlockPos pos = BlockPos.of(iterator.nextLong());
            if (!level.hasChunkAt(pos)) {
                skippedPositions.incrementAndGet();
                continue;
            }
            SectionPos sectionPos = SectionPos.of(pos);
            SectionRestoreGroup group = groups.computeIfAbsent(sectionPos.asLong(), ignored -> new SectionRestoreGroup(sectionPos));
            group.positions().add(SectionPos.sectionRelativePos(pos));
        }

        long restored = 0L;
        for (SectionRestoreGroup group : groups.values()) {
            SectionPos sectionPos = group.sectionPos();
            BlockPos anchor = sectionPos.origin();
            if (!level.hasChunkAt(anchor)) {
                skippedPositions.addAndGet(group.positions().size());
                continue;
            }
            LevelChunk chunk = level.getChunkAt(anchor);
            int sectionIndex = sectionPos.y() - level.getMinSection();
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                skippedPositions.addAndGet(group.positions().size());
                continue;
            }
            if (group.positions().size() == 1) {
                short relativePos = group.positions().iterator().nextShort();
                BlockPos pos = sectionPos.relativeToBlockPos(relativePos);
                player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
                restoredBlockEntityPositions.add(pos.asLong());
                restored++;
                continue;
            }
            LevelChunkSection section = chunk.getSections()[sectionIndex];
            Packet<?> packet = createSectionPacket(sectionPos, group.positions(), section);
            if (packet == null) {
                for (short relativePos : group.positions()) {
                    BlockPos pos = sectionPos.relativeToBlockPos(relativePos);
                    player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
                    restoredBlockEntityPositions.add(pos.asLong());
                    restored++;
                }
                continue;
            }
            player.connection.send(packet);
            addBlockEntityPositions(restoredBlockEntityPositions, sectionPos, group.positions());
            restored += group.positions().size();
        }

        for (LongIterator iterator = restoredBlockEntityPositions.iterator(); iterator.hasNext();) {
            BlockEntity blockEntity = level.getBlockEntity(BlockPos.of(iterator.nextLong()));
            if (blockEntity == null) {
                continue;
            }
            Packet<?> packet = blockEntity.getUpdatePacket();
            if (packet != null) {
                player.connection.send(packet);
            }
        }
        if (restored > 0L) {
            restoredPositions.addAndGet(restored);
            DiagnosticLog.info(
                    DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES,
                    "event=idle_gate_vanilla_block_restore player={} restored={} skipped={} capturedPackets={} capturedPositions={}",
                    player.getGameProfile().getName(),
                    restored,
                    skippedPositions.get(),
                    capturedPackets.get(),
                    capturedPositions.get());
        }
    }

    private static Packet<?> createSectionPacket(SectionPos sectionPos, ShortSet positions, LevelChunkSection section) {
        try {
            return ClientboundSectionBlocksUpdatePacket.class
                    .getConstructor(SectionPos.class, ShortSet.class, LevelChunkSection.class)
                    .newInstance(sectionPos, positions, section);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            return ClientboundSectionBlocksUpdatePacket.class
                    .getConstructor(SectionPos.class, ShortSet.class, LevelChunkSection.class, boolean.class)
                    .newInstance(sectionPos, positions, section, false);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static void addBlockEntityPositions(LongArrayList target, SectionPos sectionPos, ShortSet positions) {
        for (short relativePos : positions) {
            target.add(sectionPos.relativeToBlockPos(relativePos).asLong());
        }
    }

    @FunctionalInterface
    private interface CaptureOperation {
        CaptureResult capture(PlayerState state);
    }

    private record SectionRestoreGroup(SectionPos sectionPos, ShortOpenHashSet positions) {
        private SectionRestoreGroup(SectionPos sectionPos) {
            this(sectionPos, new ShortOpenHashSet());
        }
    }

    private static final class PlayerState {
        private final LongOpenHashSet positions = new LongOpenHashSet();
        private volatile ServerPlayer player;

        private PlayerState bind(ServerPlayer player) {
            this.player = player;
            return this;
        }

        private ServerPlayer player() {
            return player;
        }

        private synchronized CaptureResult remember(long position) {
            if (positions.contains(position)) {
                return CaptureResult.SUPERSEDED;
            }
            if (positions.size() >= MAX_PENDING_POSITIONS_PER_PLAYER) {
                return CaptureResult.FULL;
            }
            positions.add(position);
            return CaptureResult.NEW;
        }

        private synchronized CaptureResult remember(SectionPos sectionPos, short[] relativePositions) {
            int newPositions = 0;
            for (short relativePosition : relativePositions) {
                if (!positions.contains(sectionPos.relativeToBlockPos(relativePosition).asLong())) {
                    newPositions++;
                }
            }
            if (positions.size() + newPositions > MAX_PENDING_POSITIONS_PER_PLAYER) {
                for (short relativePosition : relativePositions) {
                    positions.remove(sectionPos.relativeToBlockPos(relativePosition).asLong());
                }
                return CaptureResult.FULL;
            }
            for (short relativePosition : relativePositions) {
                positions.add(sectionPos.relativeToBlockPos(relativePosition).asLong());
            }
            return newPositions == 0
                    ? CaptureResult.SUPERSEDED
                    : new CaptureResult(true, relativePositions.length);
        }

        private synchronized boolean contains(long position) {
            return positions.contains(position);
        }

        private synchronized void discardChunk(ChunkPos chunkPos) {
            for (LongIterator iterator = positions.iterator(); iterator.hasNext();) {
                BlockPos pos = BlockPos.of(iterator.nextLong());
                if ((pos.getX() >> 4) == chunkPos.x && (pos.getZ() >> 4) == chunkPos.z) {
                    iterator.remove();
                }
            }
        }

        private synchronized LongArrayList drain() {
            LongArrayList drained = new LongArrayList(positions);
            positions.clear();
            return drained;
        }
    }

    private record CaptureResult(boolean captured, int positions) {
        private static final CaptureResult NEW = new CaptureResult(true, 1);
        private static final CaptureResult SUPERSEDED = new CaptureResult(true, 0);
        private static final CaptureResult FULL = new CaptureResult(false, 0);
    }
}
