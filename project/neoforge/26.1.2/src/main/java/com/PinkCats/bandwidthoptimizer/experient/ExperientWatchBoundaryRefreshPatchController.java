package com.PinkCats.bandwidthoptimizer.experient;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID)
public final class ExperientWatchBoundaryRefreshPatchController {

    private static final int INITIAL_DELAY_TICKS = 40;
    private static final int POST_TELEPORT_SETTLE_TICKS = 20;
    private static final int POST_MUTATION_SETTLE_TICKS = 20;
    private static final int MAX_STAGE_WAIT_TICKS = 20 * 60;
    private static final int RETURNED_CHUNK_SEND_RETRY_INTERVAL_TICKS = 20;
    private static final long FULL_CHUNK_QUIET_WINDOW_MILLIS = 750L;
    private static final int AWAY_EXTRA_OFFSET_CHUNKS = 4;
    private static final int MIN_AWAY_OFFSET_CHUNKS = 3;
    private static final double SAFE_PATH_Y = 200.0D;
    private static final double SAFE_Y_MARGIN = 32.0D;
    private static final int TARGET_BLOCK_X_OFFSET = 4;
    private static final int TARGET_BLOCK_Z_OFFSET = 4;
    private static final int ACK_TARGET_SEARCH_RADIUS_CHUNKS = 6;
    private static final Map<UUID, ScenarioState> PLAYER_SCENARIOS = new ConcurrentHashMap<>();

    private ExperientWatchBoundaryRefreshPatchController() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ExperientRuntimeFlags.isEnabled()
                || !ExperientWatchBoundaryRefreshPatchRuntimeConfig.isEnabled()
                || !(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        ScenarioLayout layout = buildScenarioLayout(serverPlayer);
        prepareScenarioPlayer(serverPlayer, layout.homeX(), layout.homeY(), layout.homeZ());
        PLAYER_SCENARIOS.put(
                serverPlayer.getUUID(),
                new ScenarioState(
                        layout,
                        ScenarioStage.WAIT_INITIAL_FULL_ACK,
                        0L,
                        0L,
                        0L,
                        "",
                        "",
                        "",
                        INITIAL_DELAY_TICKS,
                        MAX_STAGE_WAIT_TICKS
                )
        );
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientWatchBoundary] Registered scenario for player={}, chunk={}, home=({}, {}, {}), away=({}, {}, {}), targetBlock=({}, {}, {})",
                serverPlayer.getGameProfile().name(),
                layout.coordinate().logText(),
                formatDouble(layout.homeX()),
                formatDouble(layout.homeY()),
                formatDouble(layout.homeZ()),
                formatDouble(layout.awayX()),
                formatDouble(layout.awayY()),
                formatDouble(layout.awayZ()),
                layout.targetBlockPos().getX(),
                layout.targetBlockPos().getY(),
                layout.targetBlockPos().getZ()
        );
    }


    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        PLAYER_SCENARIOS.remove(serverPlayer.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ExperientRuntimeFlags.isEnabled()
                || !ExperientWatchBoundaryRefreshPatchRuntimeConfig.isEnabled()
                || PLAYER_SCENARIOS.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, ScenarioState> entry : PLAYER_SCENARIOS.entrySet()) {
            ServerPlayer serverPlayer = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (serverPlayer == null) {
                PLAYER_SCENARIOS.remove(entry.getKey());
                continue;
            }

            ScenarioState nextState = advanceScenario(serverPlayer, entry.getValue());
            if (nextState == null) {
                PLAYER_SCENARIOS.remove(entry.getKey());
            } else {
                PLAYER_SCENARIOS.put(entry.getKey(), nextState);
            }
        }
    }

    private static ScenarioState advanceScenario(ServerPlayer serverPlayer, ScenarioState state) {
        if (state.delayTicksRemaining() > 0) {
            return state.withDelayTicksRemaining(state.delayTicksRemaining() - 1);
        }

        if (state.waitTicksRemaining() <= 0) {
            failScenario(serverPlayer, state, "scenario_timeout");
            return null;
        }

        if (state.stage() == ScenarioStage.WAIT_INITIAL_FULL_ACK) {
            return waitForInitialFullAck(serverPlayer, state);
        }
        if (state.stage() == ScenarioStage.WAIT_TARGET_HOME_READY) {
            return waitForTargetHomeReady(serverPlayer, state);
        }
        if (state.stage() == ScenarioStage.WAIT_WATCH_BOUNDARY_RETAIN) {
            return waitForWatchBoundaryRetain(serverPlayer, state);
        }
        if (state.stage() == ScenarioStage.WAIT_RETURN_HOME) {
            return waitForReturnHome(serverPlayer, state);
        }
        if (state.stage() == ScenarioStage.WAIT_RETURNED_CHUNK_REWATCHED) {
            return waitForReturnedChunkRewatched(serverPlayer, state);
        }
        if (state.stage() == ScenarioStage.WAIT_RETURNED_CHUNK_SENT) {
            return waitForReturnedChunkSent(serverPlayer, state);
        }
        return waitForReturnedFullAck(serverPlayer, state);
    }


    private static ScenarioState waitForInitialFullAck(ServerPlayer serverPlayer, ScenarioState state) {
        if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                serverPlayer,
                FULL_CHUNK_QUIET_WINDOW_MILLIS
        )) {
            return state.tickWait();
        }

        ScenarioLayout acknowledgedLayout = resolveAcknowledgedScenarioLayout(serverPlayer, state.layout());
        if (acknowledgedLayout == null) {
            return state.tickWait();
        }

        ChunkPeerChunkStateSnapshot chunkSnapshot =
                ChunkPeerStateManager.snapshotPlayerChunk(serverPlayer, acknowledgedLayout.coordinate());
        if (!hasAcknowledgedFullSnapshot(chunkSnapshot)) {
            return state.tickWait();
        }

        if (!isPlayerInsideTargetChunk(serverPlayer, acknowledgedLayout.coordinate())) {
            stabilizePlayerMotion(serverPlayer);
            if (!teleportPlayer(serverPlayer, acknowledgedLayout.homeX(), acknowledgedLayout.homeY(), acknowledgedLayout.homeZ())) {
                failScenario(serverPlayer, state, "teleport_to_target_home_failed");
                return null;
            }
            stabilizePlayerMotion(serverPlayer);
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientWatchBoundary] Moving player onto acknowledged target home for player={}, chunk={}, home=({}, {}, {})",
                    serverPlayer.getGameProfile().name(),
                    acknowledgedLayout.coordinate().logText(),
                    formatDouble(acknowledgedLayout.homeX()),
                    formatDouble(acknowledgedLayout.homeY()),
                    formatDouble(acknowledgedLayout.homeZ())
            );
            return state.transitionTo(
                    acknowledgedLayout,
                    ScenarioStage.WAIT_TARGET_HOME_READY,
                    0L,
                    0L,
                    0L,
                    "",
                    "",
                    "",
                    POST_TELEPORT_SETTLE_TICKS,
                    MAX_STAGE_WAIT_TICKS
            );
        }

        return teleportAwayFromTargetHome(serverPlayer, state, acknowledgedLayout, chunkSnapshot);
    }

    private static ScenarioState waitForTargetHomeReady(ServerPlayer serverPlayer, ScenarioState state) {
        if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                serverPlayer,
                FULL_CHUNK_QUIET_WINDOW_MILLIS
        )) {
            return state.tickWait();
        }

        if (!isPlayerInsideTargetChunk(serverPlayer, state.layout().coordinate())) {
            return state.tickWait();
        }

        ChunkPeerChunkStateSnapshot chunkSnapshot =
                ChunkPeerStateManager.snapshotPlayerChunk(serverPlayer, state.layout().coordinate());
        if (!hasAcknowledgedFullSnapshot(chunkSnapshot)) {
            return state.tickWait();
        }

        return teleportAwayFromTargetHome(serverPlayer, state, state.layout(), chunkSnapshot);
    }

    private static ScenarioState waitForWatchBoundaryRetain(ServerPlayer serverPlayer, ScenarioState state) {
        ChunkPeerChunkStateSnapshot chunkSnapshot =
                ChunkPeerStateManager.snapshotPlayerChunk(serverPlayer, state.layout().coordinate());
        if (chunkSnapshot == null || !chunkSnapshot.fullReplayRequiredBeforeDelta()) {
            return state.tickWait();
        }

        MutationResult mutationResult = applySingleBlockMutation(serverPlayer.level(), state.layout().targetBlockPos());
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientWatchBoundary] Watch-boundary retain reached for player={}, chunk={}, mutatedBlock=({}, {}, {}), from={}, to={}, state={}",
                serverPlayer.getGameProfile().name(),
                state.layout().coordinate().logText(),
                state.layout().targetBlockPos().getX(),
                state.layout().targetBlockPos().getY(),
                state.layout().targetBlockPos().getZ(),
                mutationResult.originalBlockStateName(),
                mutationResult.mutatedBlockStateName(),
                chunkSnapshot.summaryText()
        );
        return state.transitionTo(
                state.layout(),
                ScenarioStage.WAIT_RETURN_HOME,
                state.initialFullSnapshotVersion(),
                state.targetChunkWatchCountBaseline(),
                state.targetChunkSentCountBaseline(),
                state.initialFullSnapshotHash(),
                mutationResult.originalBlockStateName(),
                mutationResult.mutatedBlockStateName(),
                POST_MUTATION_SETTLE_TICKS,
                MAX_STAGE_WAIT_TICKS
        );
    }

    private static ScenarioState waitForReturnHome(ServerPlayer serverPlayer, ScenarioState state) {
        if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                serverPlayer,
                FULL_CHUNK_QUIET_WINDOW_MILLIS
        )) {
            return state.tickWait();
        }

        stabilizePlayerMotion(serverPlayer);
        if (!teleportPlayer(serverPlayer, state.layout().homeX(), state.layout().homeY(), state.layout().homeZ())) {
            failScenario(serverPlayer, state, "teleport_back_home_failed");
            return null;
        }
        stabilizePlayerMotion(serverPlayer);
        ExperientChunkWatchEventTracker.ChunkWatchProgressSnapshot progressSnapshot =
                ExperientChunkWatchEventTracker.snapshot(serverPlayer, state.layout().coordinate());
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientWatchBoundary] Returning player home for player={}, chunk={}, home=({}, {}, {}), playerChunkAfterTeleport=({}, {}), watchBaseline={}",
                serverPlayer.getGameProfile().name(),
                state.layout().coordinate().logText(),
                formatDouble(state.layout().homeX()),
                formatDouble(state.layout().homeY()),
                formatDouble(state.layout().homeZ()),
                serverPlayer.chunkPosition().x(),
                serverPlayer.chunkPosition().z(),
                progressSnapshot.watchCount()
        );
        return state.transitionTo(
                state.layout(),
                ScenarioStage.WAIT_RETURNED_CHUNK_REWATCHED,
                state.initialFullSnapshotVersion(),
                progressSnapshot.watchCount(),
                progressSnapshot.sentCount(),
                state.initialFullSnapshotHash(),
                state.originalBlockStateName(),
                state.mutatedBlockStateName(),
                POST_TELEPORT_SETTLE_TICKS,
                MAX_STAGE_WAIT_TICKS
        );
    }

    private static ScenarioState waitForReturnedChunkRewatched(ServerPlayer serverPlayer, ScenarioState state) {
        if (!isPlayerInsideTargetChunk(serverPlayer, state.layout().coordinate())) {
            return state.tickWait();
        }

        ExperientChunkWatchEventTracker.ChunkWatchProgressSnapshot progressSnapshot =
                ExperientChunkWatchEventTracker.snapshot(serverPlayer, state.layout().coordinate());
        if (progressSnapshot.watchCount() <= state.targetChunkWatchCountBaseline()) {
            return state.tickWait();
        }

        Bandwidthoptimizer.LOGGER.info(
                "[ExperientWatchBoundary] Returned chunk rewatched for player={}, chunk={}, watchCount={}, unwatchCount={}",
                serverPlayer.getGameProfile().name(),
                state.layout().coordinate().logText(),
                progressSnapshot.watchCount(),
                progressSnapshot.unwatchCount()
        );
        flushTargetChunkThroughSender(serverPlayer, state.layout().coordinate());
        return state.transitionTo(
                state.layout(),
                ScenarioStage.WAIT_RETURNED_CHUNK_SENT,
                state.initialFullSnapshotVersion(),
                state.targetChunkWatchCountBaseline(),
                state.targetChunkSentCountBaseline(),
                state.initialFullSnapshotHash(),
                state.originalBlockStateName(),
                state.mutatedBlockStateName(),
                0,
                MAX_STAGE_WAIT_TICKS
        );
    }

    private static ScenarioState waitForReturnedChunkSent(ServerPlayer serverPlayer, ScenarioState state) {
        if (!isPlayerInsideTargetChunk(serverPlayer, state.layout().coordinate())) {
            return state.tickWait();
        }

        ChunkPeerChunkStateSnapshot chunkSnapshot =
                ChunkPeerStateManager.snapshotPlayerChunk(serverPlayer, state.layout().coordinate());
        if (hasReturnedFullSnapshotPublished(chunkSnapshot, state)) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientWatchBoundary] Returned chunk republished for player={}, chunk={}, state={}",
                    serverPlayer.getGameProfile().name(),
                    state.layout().coordinate().logText(),
                    chunkSnapshot.summaryText()
            );
            return state.transitionTo(
                    state.layout(),
                    ScenarioStage.WAIT_RETURNED_FULL_ACK,
                    state.initialFullSnapshotVersion(),
                    state.targetChunkWatchCountBaseline(),
                    state.targetChunkSentCountBaseline(),
                    state.initialFullSnapshotHash(),
                    state.originalBlockStateName(),
                    state.mutatedBlockStateName(),
                    0,
                    MAX_STAGE_WAIT_TICKS
            );
        }

        if (state.waitTicksRemaining() % RETURNED_CHUNK_SEND_RETRY_INTERVAL_TICKS == 0) {
            flushTargetChunkThroughSender(serverPlayer, state.layout().coordinate());
        }
        return state.tickWait();
    }

    private static ScenarioState waitForReturnedFullAck(ServerPlayer serverPlayer, ScenarioState state) {
        if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                serverPlayer,
                FULL_CHUNK_QUIET_WINDOW_MILLIS
        )) {
            return state.tickWait();
        }

        ChunkPeerChunkStateSnapshot chunkSnapshot =
                ChunkPeerStateManager.snapshotPlayerChunk(serverPlayer, state.layout().coordinate());
        if (!hasReturnedFullSnapshotAcknowledged(chunkSnapshot, state)) {
            return state.tickWait();
        }

        RunAllProbeFiles.markWatchBoundaryRefreshPatchCompleted(
                serverPlayer,
                state.layout().coordinate(),
                state.layout().targetBlockPos(),
                state.initialFullSnapshotVersion(),
                chunkSnapshot.fullSnapshotVersion(),
                state.originalBlockStateName(),
                state.mutatedBlockStateName(),
                state.initialFullSnapshotHash(),
                chunkSnapshot.knownSnapshotHash()
        );
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientWatchBoundary] Scenario completed for player={}, chunk={}, finalState={}",
                serverPlayer.getGameProfile().name(),
                state.layout().coordinate().logText(),
                chunkSnapshot.summaryText()
        );
        if (!ExperientChunkHotspotPathRuntimeConfig.isEnabled()) {
            disconnectPlayer(serverPlayer, "BandwidthOptimizer watch-boundary regression completed");
        }
        return null;
    }


    private static ScenarioState teleportAwayFromTargetHome(
            ServerPlayer serverPlayer,
            ScenarioState state,
            ScenarioLayout acknowledgedLayout,
            ChunkPeerChunkStateSnapshot chunkSnapshot
    ) {
        stabilizePlayerMotion(serverPlayer);
        if (!teleportPlayer(serverPlayer, acknowledgedLayout.awayX(), acknowledgedLayout.awayY(), acknowledgedLayout.awayZ())) {
            failScenario(serverPlayer, state, "teleport_to_away_failed");
            return null;
        }
        stabilizePlayerMotion(serverPlayer);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientWatchBoundary] Initial full acknowledged for player={}, chunk={}, homeChunk=({}, {}), awayChunk=({}, {}), targetBlock=({}, {}, {}), state={}",
                serverPlayer.getGameProfile().name(),
                acknowledgedLayout.coordinate().logText(),
                resolveChunkXForPosition(acknowledgedLayout.homeX()),
                resolveChunkZForPosition(acknowledgedLayout.homeZ()),
                resolveChunkXForPosition(acknowledgedLayout.awayX()),
                resolveChunkZForPosition(acknowledgedLayout.awayZ()),
                acknowledgedLayout.targetBlockPos().getX(),
                acknowledgedLayout.targetBlockPos().getY(),
                acknowledgedLayout.targetBlockPos().getZ(),
                chunkSnapshot.summaryText()
        );
        return state.transitionTo(
                acknowledgedLayout,
                ScenarioStage.WAIT_WATCH_BOUNDARY_RETAIN,
                chunkSnapshot.fullSnapshotVersion(),
                0L,
                0L,
                chunkSnapshot.knownSnapshotHash(),
                state.originalBlockStateName(),
                state.mutatedBlockStateName(),
                POST_TELEPORT_SETTLE_TICKS,
                MAX_STAGE_WAIT_TICKS
        );
    }

    private static boolean flushTargetChunkThroughSender(ServerPlayer serverPlayer, ChunkPacketCoordinate coordinate) {
        if (serverPlayer == null
                || serverPlayer.connection == null
                || coordinate == null
                || !coordinate.present()) {
            return false;
        }

        LevelChunk levelChunk = serverPlayer.level().getChunk(coordinate.chunkX(), coordinate.chunkZ());
        if (levelChunk == null) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ExperientWatchBoundary] Skip chunk sender flush because level chunk is missing for player={}, chunk={}",
                    serverPlayer.getGameProfile().name(),
                    coordinate.logText()
            );
            return false;
        }

        ClientboundLevelChunkWithLightPacket packet =
                new ClientboundLevelChunkWithLightPacket(levelChunk, serverPlayer.level().getLightEngine(), null, null);
        serverPlayer.connection.send(packet);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientWatchBoundary] Requested immediate chunk resend for player={}, chunk={}, packet={}",
                serverPlayer.getGameProfile().name(),
                coordinate.logText(),
                packet.getClass().getSimpleName()
        );
        return true;
    }

    private static ScenarioLayout buildScenarioLayout(ServerPlayer serverPlayer) {
        ChunkPos chunkPos = serverPlayer.chunkPosition();
        double homeX = (chunkPos.x() << 4) + 8.5D;
        double homeY = resolveSafePathY(serverPlayer.level());
        double homeZ = (chunkPos.z() << 4) + 8.5D;
        int awayOffsetChunks = resolveAwayOffsetChunks(serverPlayer);
        BlockPos targetBlockPos = resolveTargetMutationBlockPos(serverPlayer.level(), chunkPos);
        return new ScenarioLayout(
                homeX,
                homeY,
                homeZ,
                homeX + (awayOffsetChunks * 16.0D),
                homeY,
                homeZ,
                ChunkPacketCoordinate.ofChunk(chunkPos.x(), chunkPos.z()),
                targetBlockPos
        );
    }

    private static ScenarioLayout resolveAcknowledgedScenarioLayout(ServerPlayer serverPlayer, ScenarioLayout currentLayout) {
        if (serverPlayer == null || currentLayout == null) {
            return null;
        }

        ScenarioTargetSelection bestSelection = null;
        int baseChunkX = resolveChunkXForPosition(currentLayout.homeX());
        int baseChunkZ = resolveChunkZForPosition(currentLayout.homeZ());
        for (int offsetX = -ACK_TARGET_SEARCH_RADIUS_CHUNKS; offsetX <= ACK_TARGET_SEARCH_RADIUS_CHUNKS; offsetX++) {
            for (int offsetZ = -ACK_TARGET_SEARCH_RADIUS_CHUNKS; offsetZ <= ACK_TARGET_SEARCH_RADIUS_CHUNKS; offsetZ++) {
                ChunkPacketCoordinate coordinate = ChunkPacketCoordinate.ofChunk(baseChunkX + offsetX, baseChunkZ + offsetZ);
                ChunkPeerChunkStateSnapshot chunkSnapshot = ChunkPeerStateManager.snapshotPlayerChunk(serverPlayer, coordinate);
                if (!hasAcknowledgedFullSnapshot(chunkSnapshot)) {
                    continue;
                }

                int distance = Math.abs(offsetX) + Math.abs(offsetZ);
                if (bestSelection == null || distance < bestSelection.distance()) {
                    bestSelection = new ScenarioTargetSelection(coordinate, chunkSnapshot, distance);
                }
            }
        }

        if (bestSelection == null) {
            return null;
        }

        ChunkPos selectedChunkPos = new ChunkPos(
                bestSelection.coordinate().chunkX(),
                bestSelection.coordinate().chunkZ()
        );
        double homeX = (selectedChunkPos.x() << 4) + 8.5D;
        double homeZ = (selectedChunkPos.z() << 4) + 8.5D;
        int awayOffsetChunks = resolveAwayOffsetChunks(serverPlayer);
        return new ScenarioLayout(
                homeX,
                currentLayout.homeY(),
                homeZ,
                homeX + (awayOffsetChunks * 16.0D),
                currentLayout.awayY(),
                homeZ,
                bestSelection.coordinate(),
                resolveTargetMutationBlockPos(serverPlayer.level(), selectedChunkPos)
        );
    }

    private static void prepareScenarioPlayer(ServerPlayer serverPlayer, double targetX, double targetY, double targetZ) {
        if (serverPlayer == null) {
            return;
        }
        serverPlayer.setGameMode(GameType.SPECTATOR);
        stabilizePlayerMotion(serverPlayer);
        teleportPlayer(serverPlayer, targetX, targetY, targetZ);
        stabilizePlayerMotion(serverPlayer);
    }

    private static BlockPos resolveTargetMutationBlockPos(ServerLevel serverLevel, ChunkPos chunkPos) {
        int blockX = (chunkPos.x() << 4) + TARGET_BLOCK_X_OFFSET;
        int blockZ = (chunkPos.z() << 4) + TARGET_BLOCK_Z_OFFSET;
        int suggestedY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ) - 1;
        int minY = serverLevel.getMinY() + 1;
        int maxY = serverLevel.getMaxY() - 2;
        int clampedY = Math.max(minY, Math.min(suggestedY, maxY));
        for (int candidateY = clampedY; candidateY >= minY; candidateY--) {
            BlockPos candidatePos = new BlockPos(blockX, candidateY, blockZ);
            BlockState candidateBlockState = serverLevel.getBlockState(candidatePos);
            if (!candidateBlockState.isAir()
                    && candidateBlockState.getFluidState().isEmpty()
                    && !candidateBlockState.is(Blocks.BEDROCK)) {
                return candidatePos;
            }
        }
        return new BlockPos(blockX, clampedY, blockZ);
    }

    private static MutationResult applySingleBlockMutation(ServerLevel serverLevel, BlockPos targetBlockPos) {
        BlockState originalBlockState = serverLevel.getBlockState(targetBlockPos);
        BlockState mutatedBlockState = selectReplacementBlockState(originalBlockState);
        serverLevel.setBlockAndUpdate(targetBlockPos, mutatedBlockState);
        return new MutationResult(
                describeBlockState(originalBlockState),
                describeBlockState(mutatedBlockState)
        );
    }


    private static BlockState selectReplacementBlockState(BlockState currentBlockState) {
        if (currentBlockState.is(Blocks.STONE)) {
            return Blocks.ANDESITE.defaultBlockState();
        }
        if (currentBlockState.is(Blocks.ANDESITE)) {
            return Blocks.DIORITE.defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }


    private static boolean hasAcknowledgedFullSnapshot(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        return chunkSnapshot != null
                && chunkSnapshot.knownSnapshotPublished()
                && chunkSnapshot.receiverSnapshotAcknowledged()
                && chunkSnapshot.fullSnapshotVersion() > 0L
                && chunkSnapshot.fullSnapshotVersion() == chunkSnapshot.acknowledgedSnapshotVersion()
                && chunkSnapshot.knownSnapshotHash() != null
                && !chunkSnapshot.knownSnapshotHash().isBlank();
    }


    private static boolean isPlayerInsideTargetChunk(ServerPlayer serverPlayer, ChunkPacketCoordinate coordinate) {
        return serverPlayer != null
                && coordinate != null
                && coordinate.present()
                && serverPlayer.chunkPosition().x() == coordinate.chunkX()
                && serverPlayer.chunkPosition().z() == coordinate.chunkZ();
    }

    private static boolean hasReturnedFullSnapshotAcknowledged(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ScenarioState state
    ) {
        return hasAcknowledgedFullSnapshot(chunkSnapshot)
                && hasReturnedFullSnapshotPublished(chunkSnapshot, state);
    }

    private static boolean hasReturnedFullSnapshotPublished(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ScenarioState state
    ) {
        return chunkSnapshot != null
                && chunkSnapshot.knownSnapshotPublished()
                && chunkSnapshot.fullSnapshotVersion() > state.initialFullSnapshotVersion()
                && chunkSnapshot.knownSnapshotHash() != null
                && !chunkSnapshot.knownSnapshotHash().isBlank()
                && !chunkSnapshot.knownSnapshotHash().equals(state.initialFullSnapshotHash());
    }


    private static int resolveAwayOffsetChunks(ServerPlayer serverPlayer) {
        if (serverPlayer == null || serverPlayer.level().getServer() == null) {
            return MIN_AWAY_OFFSET_CHUNKS;
        }
        int viewDistance = Math.max(serverPlayer.level().getServer().getPlayerList().getViewDistance(), 0);
        return Math.max(viewDistance + AWAY_EXTRA_OFFSET_CHUNKS, MIN_AWAY_OFFSET_CHUNKS);
    }


    private static double resolveSafePathY(ServerLevel serverLevel) {
        if (serverLevel == null) {
            return SAFE_PATH_Y;
        }
        double minSafeY = serverLevel.getMinY() + SAFE_Y_MARGIN;
        double maxSafeY = serverLevel.getMaxY() - SAFE_Y_MARGIN;
        if (maxSafeY < minSafeY) {
            return SAFE_PATH_Y;
        }
        return Math.max(minSafeY, Math.min(SAFE_PATH_Y, maxSafeY));
    }

    private static int resolveChunkXForPosition(double x) {
        return floorToBlock(x) >> 4;
    }


    private static int resolveChunkZForPosition(double z) {
        return floorToBlock(z) >> 4;
    }


    private static int floorToBlock(double value) {
        return (int) Math.floor(value);
    }

    private static void stabilizePlayerMotion(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return;
        }
        serverPlayer.setDeltaMovement(0.0D, 0.0D, 0.0D);
        serverPlayer.resetFallDistance();
    }


    private static boolean teleportPlayer(ServerPlayer serverPlayer, double targetX, double targetY, double targetZ) {
        if (serverPlayer == null || serverPlayer.level().getServer() == null) {
            return false;
        }

        CommandSourceStack commandSource = serverPlayer.level().getServer()
                .createCommandSourceStack()
                .withSuppressedOutput()
                .withPermission(PermissionSet.ALL_PERMISSIONS);
        String command = "tp "
                + serverPlayer.getGameProfile().name()
                + " "
                + formatDouble(targetX)
                + " "
                + formatDouble(targetY)
                + " "
                + formatDouble(targetZ);
        serverPlayer.level().getServer().getCommands().performPrefixedCommand(commandSource, command);
        return true;
    }

    private static void failScenario(ServerPlayer serverPlayer, ScenarioState state, String reason) {
        Bandwidthoptimizer.LOGGER.warn(
                "[ExperientWatchBoundary] Scenario failed for player={}, chunk={}, reason={}, stage={}, waitTicksRemaining={}",
                serverPlayer == null ? "<missing>" : serverPlayer.getGameProfile().name(),
                state == null ? "<missing>" : state.layout().coordinate().logText(),
                reason,
                state == null ? "<missing>" : state.stage().name(),
                state == null ? -1 : state.waitTicksRemaining()
        );
        disconnectPlayer(serverPlayer, "BandwidthOptimizer watch-boundary regression failed: " + reason);
    }


    private static void disconnectPlayer(ServerPlayer serverPlayer, String reason) {
        if (serverPlayer == null || serverPlayer.connection == null) {
            return;
        }
        serverPlayer.connection.disconnect(Component.literal(reason));
    }


    private static String describeBlockState(BlockState blockState) {
        if (blockState == null) {
            return "<null>";
        }
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(blockState.getBlock()));
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private enum ScenarioStage {
        WAIT_INITIAL_FULL_ACK,
        WAIT_TARGET_HOME_READY,
        WAIT_WATCH_BOUNDARY_RETAIN,
        WAIT_RETURN_HOME,
        WAIT_RETURNED_CHUNK_REWATCHED,
        WAIT_RETURNED_CHUNK_SENT,
        WAIT_RETURNED_FULL_ACK
    }

    private record ScenarioLayout(
            double homeX,
            double homeY,
            double homeZ,
            double awayX,
            double awayY,
            double awayZ,
            ChunkPacketCoordinate coordinate,
            BlockPos targetBlockPos
    ) {}

    private record MutationResult(
            String originalBlockStateName,
            String mutatedBlockStateName
    ) {}

    private record ScenarioTargetSelection(
            ChunkPacketCoordinate coordinate,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            int distance
    ) {}

    private record ScenarioState(
            ScenarioLayout layout,
            ScenarioStage stage,
            long initialFullSnapshotVersion,
            long targetChunkWatchCountBaseline,
            long targetChunkSentCountBaseline,
            String initialFullSnapshotHash,
            String originalBlockStateName,
            String mutatedBlockStateName,
            int delayTicksRemaining,
            int waitTicksRemaining
    ) {

        private ScenarioState withDelayTicksRemaining(int nextDelayTicksRemaining) {
            return new ScenarioState(
                    this.layout,
                    this.stage,
                    this.initialFullSnapshotVersion,
                    this.targetChunkWatchCountBaseline,
                    this.targetChunkSentCountBaseline,
                    this.initialFullSnapshotHash,
                    this.originalBlockStateName,
                    this.mutatedBlockStateName,
                    Math.max(nextDelayTicksRemaining, 0),
                    this.waitTicksRemaining
            );
        }

        private ScenarioState tickWait() {
            return new ScenarioState(
                    this.layout,
                    this.stage,
                    this.initialFullSnapshotVersion,
                    this.targetChunkWatchCountBaseline,
                    this.targetChunkSentCountBaseline,
                    this.initialFullSnapshotHash,
                    this.originalBlockStateName,
                    this.mutatedBlockStateName,
                    0,
                    this.waitTicksRemaining - 1
            );
        }

        private ScenarioState transitionTo(
                ScenarioLayout nextLayout,
                ScenarioStage nextStage,
                long nextInitialFullSnapshotVersion,
                long nextTargetChunkWatchCountBaseline,
                long nextTargetChunkSentCountBaseline,
                String nextInitialFullSnapshotHash,
                String nextOriginalBlockStateName,
                String nextMutatedBlockStateName,
                int nextDelayTicksRemaining,
                int nextWaitTicksRemaining
        ) {
            return new ScenarioState(
                    nextLayout == null ? this.layout : nextLayout,
                    nextStage,
                    Math.max(nextInitialFullSnapshotVersion, 0L),
                    Math.max(nextTargetChunkWatchCountBaseline, 0L),
                    Math.max(nextTargetChunkSentCountBaseline, 0L),
                    nextInitialFullSnapshotHash == null ? "" : nextInitialFullSnapshotHash,
                    nextOriginalBlockStateName == null ? "" : nextOriginalBlockStateName,
                    nextMutatedBlockStateName == null ? "" : nextMutatedBlockStateName,
                    Math.max(nextDelayTicksRemaining, 0),
                    Math.max(nextWaitTicksRemaining, 0)
            );
        }
    }
}
