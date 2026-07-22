package com.PinkCats.bandwidthoptimizer.experimental.hotspot;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID)
public final class ExperientChunkHotspotPathController {

    private static final int INITIAL_DELAY_TICKS = 80;
    private static final int LIGHT_PULSE_DELAY_TICKS = 20;
    private static final int SECTION_SETTLE_DELAY_TICKS = 200;
    private static final int SECTION_PULSE_DELAY_TICKS = 20;
    private static final int POST_SECTION_PULSE_DELAY_TICKS = 60;
    private static final int BLOCK_ENTITY_PULSE_DELAY_TICKS = 20;
    private static final int POST_BLOCK_ENTITY_PULSE_DELAY_TICKS = 60;
    private static final int FINAL_RETURN_SETTLE_TICKS = 200;
    private static final int TWO_POINT_REUSE_MIN_SETTLE_TICKS = 10;
    private static final int TWO_POINT_REUSE_FINAL_SETTLE_TICKS = 100;
    private static final int TWO_POINT_REUSE_TOTAL_TELEPORTS = 8;
    private static final long TWO_POINT_REUSE_QUIET_WINDOW_MILLIS = 500L;
    private static final long DEFAULT_PATH_QUIET_WINDOW_MILLIS = 750L;
    private static final double TWO_POINT_REUSE_OFFSET_BLOCKS = 352.0D;
    private static final int BOUNDARY_HOP_EXTRA_OFFSET_CHUNKS = 1;
    private static final int BOUNDARY_HOP_MIN_OFFSET_CHUNKS = 3;
    private static final int BOUNDARY_HOP_MIN_SETTLE_TICKS = 1;
    private static final int BOUNDARY_HOP_FINAL_SETTLE_TICKS = 100;
    private static final int BOUNDARY_HOP_TOTAL_TELEPORTS = 24;
    private static final int DIMENSION_HOP_TOTAL_ROUND_TRIPS = 10;
    private static final int DIMENSION_HOP_TOTAL_SWITCHES = DIMENSION_HOP_TOTAL_ROUND_TRIPS * 2;
    private static final int DIMENSION_HOP_MIN_SETTLE_TICKS = 40;
    private static final int DIMENSION_HOP_FINAL_SETTLE_TICKS = 120;
    private static final long DIMENSION_HOP_QUIET_WINDOW_MILLIS = 750L;
    private static final int RANGE_BOUNCE_MIN_SETTLE_TICKS = 10;
    private static final int RANGE_BOUNCE_FINAL_SETTLE_TICKS = 100;
    private static final long RANGE_BOUNCE_QUIET_WINDOW_MILLIS = 500L;
    private static final double TWO_POINT_REUSE_SAFE_Y = 200.0D;
    private static final double TWO_POINT_REUSE_SAFE_Y_MARGIN = 32.0D;
    private static final int LIGHT_PROBE_Y_OFFSET = 4;
    private static final int BEFORE_ACK_LIGHT_PROBE_Y_OFFSET = -18;
    private static final int BEFORE_ACK_PROBE_EMIT_REMAINING_TICKS = 4;
    private static final int SECTION_PROBE_Y_OFFSET = -8;
    private static final int BLOCK_ENTITY_PROBE_X_OFFSET = 2;
    private static final int BLOCK_ENTITY_PROBE_Y_OFFSET = 1;
    private static final int BLOCK_ENTITY_PROBE_Z_OFFSET = 2;
    private static final int LIGHT_CHAMBER_RADIUS = 2;
    private static final int MAX_LIGHT_ENGINE_UPDATE_PASSES = 8192;
    private static final boolean[] LIGHT_PULSE_SEQUENCE = {true, false, true, false};
    private static final boolean[] SECTION_PULSE_SEQUENCE = {true, false, true, false};
    private static final String[] BLOCK_ENTITY_VARIANT_SEQUENCE = {"0000", "0001", "0002", "0003"};
    private static final String[] BEFORE_ACK_BLOCK_ENTITY_VARIANT_SEQUENCE = {"1000", "1001"};
    private static final String BLOCK_ENTITY_FRONT_LINE_ONE_PREFIX =
            "BO-BLOCK-ENTITY-PATCH-FRONT-LINE-ONE-STATIC-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX-";
    private static final String BLOCK_ENTITY_FRONT_LINE_TWO =
            "BO-BLOCK-ENTITY-PATCH-FRONT-LINE-TWO-STATIC-YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY";
    private static final String BLOCK_ENTITY_FRONT_LINE_THREE =
            "BO-BLOCK-ENTITY-PATCH-FRONT-LINE-THREE-STATIC-ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";
    private static final String BLOCK_ENTITY_FRONT_LINE_FOUR =
            "BO-BLOCK-ENTITY-PATCH-FRONT-LINE-FOUR-STATIC-QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ";
    private static final String BLOCK_ENTITY_BACK_LINE_ONE =
            "BO-BLOCK-ENTITY-PATCH-BACK-LINE-ONE-STATIC-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String BLOCK_ENTITY_BACK_LINE_TWO =
            "BO-BLOCK-ENTITY-PATCH-BACK-LINE-TWO-STATIC-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static final String BLOCK_ENTITY_BACK_LINE_THREE =
            "BO-BLOCK-ENTITY-PATCH-BACK-LINE-THREE-STATIC-CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
    private static final String BLOCK_ENTITY_BACK_LINE_FOUR =
            "BO-BLOCK-ENTITY-PATCH-BACK-LINE-FOUR-STATIC-DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";
    private static final int SECTION_PROBE_WIDTH = 6;
    private static final int SECTION_PROBE_HEIGHT = 4;
    private static final int SECTION_PROBE_DEPTH = 6;

    private static final TeleportWaypoint[] TELEPORT_SEQUENCE = {
            new TeleportWaypoint(320.0D, 0.0D, 0.0D, 12, true),
            new TeleportWaypoint(0.0D, 0.0D, 0.0D, 8, false),
            new TeleportWaypoint(320.0D, 0.0D, 0.0D, 8, false),
            new TeleportWaypoint(0.0D, 0.0D, 0.0D, 12, false),
            new TeleportWaypoint(0.0D, 0.0D, 320.0D, 12, true),
            new TeleportWaypoint(0.0D, 0.0D, 0.0D, 8, false),
            new TeleportWaypoint(0.0D, 0.0D, 320.0D, 8, false),
            new TeleportWaypoint(0.0D, 0.0D, 0.0D, FINAL_RETURN_SETTLE_TICKS, false)
    };
    private static final TeleportWaypoint[] TWO_POINT_REUSE_SEQUENCE = createTwoPointReuseSequence();
    private static final TeleportWaypoint[] RANGE_BOUNCE_SEQUENCE = createRangeBounceSequence();

    private static final Map<UUID, PathState> PLAYER_PATH_STATES = new ConcurrentHashMap<>();

    private ExperientChunkHotspotPathController() {
    }

    @SubscribeEvent
    // Move tool
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags.isEnabled()
                || !ExperientChunkHotspotPathRuntimeConfig.isEnabled()
                || !(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (ExperientChunkHotspotPathRuntimeConfig.isDimensionHopMode()) {
            prepareDimensionHopPlayer(serverPlayer);
        } else if (ExperientChunkHotspotPathRuntimeConfig.isBoundaryHopMode()) {
            prepareBoundaryHopPlayer(serverPlayer);
        } else if (ExperientChunkHotspotPathRuntimeConfig.isRangeBounceMode()) {
            prepareRangeBouncePlayer(serverPlayer);
        } else if (ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()) {
            prepareTwoPointReusePlayer(serverPlayer);
        }

        double originX = resolvePathOriginX(serverPlayer);
        double originY = resolvePathOriginY(serverPlayer);

        PLAYER_PATH_STATES.put(
                serverPlayer.getUUID(),
                new PathState(
                        originX,
                        originY,
                        serverPlayer.getZ(),
                        0,
                        0,
                        0,
                        0,
                        INITIAL_DELAY_TICKS
                )
        );
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] Registered scripted path for player={}, start=({}, {}, {})",
                serverPlayer.getGameProfile().getName(),
                formatDouble(originX),
                formatDouble(originY),
                formatDouble(serverPlayer.getZ())
        );
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        PLAYER_PATH_STATES.remove(serverPlayer.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags.isEnabled()
                || !ExperientChunkHotspotPathRuntimeConfig.isEnabled()
                || PLAYER_PATH_STATES.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, PathState> entry : PLAYER_PATH_STATES.entrySet()) {
            ServerPlayer serverPlayer = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (serverPlayer == null) {
                PLAYER_PATH_STATES.remove(entry.getKey());
                continue;
            }

            PathState nextState = advancePath(serverPlayer, entry.getValue());
            if (nextState == null) {
                PLAYER_PATH_STATES.remove(entry.getKey());
            } else {
                PLAYER_PATH_STATES.put(entry.getKey(), nextState);
            }
        }
    }

    //  light -> section -> block entity -> waypoint test
    private static PathState advancePath(ServerPlayer serverPlayer, PathState state) {
        if (state.delayTicksRemaining() > 0) {
            maybeEmitDelayedBeforeAckProbeBurst(serverPlayer, state);
            return state.withDelayTicksRemaining(state.delayTicksRemaining() - 1);
        }

        if (ExperientChunkHotspotPathRuntimeConfig.isDimensionHopMode()) {
            return advanceDimensionHopPath(serverPlayer, state);
        }

        if (ExperientChunkHotspotPathRuntimeConfig.isBoundaryHopMode()) {
            return advanceBoundaryHopPath(serverPlayer, state);
        }

        if (ExperientChunkHotspotPathRuntimeConfig.isRangeBounceMode()) {
            return advanceRangeBouncePath(serverPlayer, state);
        }

        if (ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()) {
            return advanceTwoPointReusePath(serverPlayer, state);
        }

        if (state.nextLightPulseIndex() < LIGHT_PULSE_SEQUENCE.length) {
            return applyLightPulse(serverPlayer, state);
        }

        if (state.nextSectionPulseIndex() < SECTION_PULSE_SEQUENCE.length) {
            return applySectionPulse(serverPlayer, state);
        }

        if (ExperientChunkHotspotPathRuntimeConfig.shouldStopAfterSection()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientChunkPath] Completed section-only scripted path for player={}",
                    serverPlayer.getGameProfile().getName()
            );
            return null;
        }

        if (state.nextBlockEntityPulseIndex() < BLOCK_ENTITY_VARIANT_SEQUENCE.length) {
            return applyBlockEntityPulse(serverPlayer, state);
        }

        if (ExperientChunkHotspotPathRuntimeConfig.shouldStopAfterBlockEntity()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientChunkPath] Completed block-entity-only scripted path for player={}",
                    serverPlayer.getGameProfile().getName()
            );
            return null;
        }

        if (state.nextWaypointIndex() >= TELEPORT_SEQUENCE.length) {
            if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                    serverPlayer,
                    DEFAULT_PATH_QUIET_WINDOW_MILLIS
            )) {
                return state.withDelayTicksRemaining(1);
            }
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientChunkPath] Completed scripted path for player={}",
                    serverPlayer.getGameProfile().getName()
            );
            disconnectPlayerAfterPathCompletion(serverPlayer);
            return null;
        }

        TeleportWaypoint waypoint = TELEPORT_SEQUENCE[state.nextWaypointIndex()];
        double targetX = state.originX() + waypoint.offsetX();
        double targetY = state.originY() + waypoint.offsetY();
        double targetZ = state.originZ() + waypoint.offsetZ();
        boolean commandAccepted = teleportPlayer(serverPlayer, targetX, targetY, targetZ);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] player={}, step={}/{}, target=({}, {}, {}), settleTicks={}, accepted={}",
                serverPlayer.getGameProfile().getName(),
                state.nextWaypointIndex() + 1,
                TELEPORT_SEQUENCE.length,
                formatDouble(targetX),
                formatDouble(targetY),
                formatDouble(targetZ),
                waypoint.settleTicks(),
                commandAccepted
        );
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex(),
                state.nextBlockEntityPulseIndex(),
                state.nextWaypointIndex() + 1,
                waypoint.settleTicks()
        );
    }

    // chunk tp
    private static PathState advanceTwoPointReusePath(ServerPlayer serverPlayer, PathState state) {
        if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                serverPlayer,
                TWO_POINT_REUSE_QUIET_WINDOW_MILLIS
        )) {
            return state.withDelayTicksRemaining(1);
        }

        if (state.nextWaypointIndex() >= TWO_POINT_REUSE_SEQUENCE.length) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientChunkPath] Completed two-point reuse path for player={}",
                    serverPlayer.getGameProfile().getName()
            );
            disconnectPlayerAfterPathCompletion(serverPlayer);
            return null;
        }

        TeleportWaypoint waypoint = TWO_POINT_REUSE_SEQUENCE[state.nextWaypointIndex()];
        double targetX = state.originX() + waypoint.offsetX();
        double targetY = state.originY() + waypoint.offsetY();
        double targetZ = state.originZ() + waypoint.offsetZ();
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        boolean commandAccepted = teleportPlayer(serverPlayer, targetX, targetY, targetZ);
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] two-point-reuse player={}, step={}/{}, target=({}, {}, {}), settleTicks={}, accepted={}",
                serverPlayer.getGameProfile().getName(),
                state.nextWaypointIndex() + 1,
                TWO_POINT_REUSE_SEQUENCE.length,
                formatDouble(targetX),
                formatDouble(targetY),
                formatDouble(targetZ),
                waypoint.settleTicks(),
                commandAccepted
        );
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex(),
                state.nextBlockEntityPulseIndex(),
                state.nextWaypointIndex() + 1,
                waypoint.settleTicks()
        );
    }

    private static PathState advanceRangeBouncePath(ServerPlayer serverPlayer, PathState state) {
        if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                serverPlayer,
                RANGE_BOUNCE_QUIET_WINDOW_MILLIS
        )) {
            return state.withDelayTicksRemaining(1);
        }

        if (state.nextWaypointIndex() >= RANGE_BOUNCE_SEQUENCE.length) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientChunkPath] Completed range-bounce reuse path for player={}, roundTrips={}",
                    serverPlayer.getGameProfile().getName(),
                    ExperientChunkHotspotPathRuntimeConfig.rangeRoundTrips()
            );
            disconnectPlayerAfterPathCompletion(serverPlayer);
            return null;
        }

        TeleportWaypoint waypoint = RANGE_BOUNCE_SEQUENCE[state.nextWaypointIndex()];
        double targetX = state.originX() + waypoint.offsetX();
        double targetY = state.originY() + waypoint.offsetY();
        double targetZ = state.originZ() + waypoint.offsetZ();
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        boolean commandAccepted = teleportPlayer(serverPlayer, targetX, targetY, targetZ);
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] range-bounce player={}, step={}/{}, target=({}, {}, {}), targetChunk=({}, {}), settleTicks={}, accepted={}",
                serverPlayer.getGameProfile().getName(),
                state.nextWaypointIndex() + 1,
                RANGE_BOUNCE_SEQUENCE.length,
                formatDouble(targetX),
                formatDouble(targetY),
                formatDouble(targetZ),
                resolveChunkXForPosition(targetX),
                floorToBlock(targetZ) >> 4,
                waypoint.settleTicks(),
                commandAccepted
        );
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex(),
                state.nextBlockEntityPulseIndex(),
                state.nextWaypointIndex() + 1,
                waypoint.settleTicks()
        );
    }


    private static PathState advanceBoundaryHopPath(ServerPlayer serverPlayer, PathState state) {
        if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                serverPlayer,
                TWO_POINT_REUSE_QUIET_WINDOW_MILLIS
        )) {
            return state.withDelayTicksRemaining(1);
        }

        if (!hasBoundaryHopReachedPreviousChunk(serverPlayer, state)) {
            return state.withDelayTicksRemaining(1);
        }

        if (state.nextWaypointIndex() >= BOUNDARY_HOP_TOTAL_TELEPORTS) {
            Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] Completed boundary-hop reuse path for player={}",
                serverPlayer.getGameProfile().getName()
            );
            disconnectPlayerAfterPathCompletion(serverPlayer);
            return null;
        }

        double leftX = state.originX();
        double rightX = resolveBoundaryHopRightX(serverPlayer, leftX);
        double targetX = state.nextWaypointIndex() % 2 == 0 ? rightX : leftX;
        double targetY = state.originY();
        double targetZ = state.originZ();
        int settleTicks = state.nextWaypointIndex() + 1 >= BOUNDARY_HOP_TOTAL_TELEPORTS
                ? BOUNDARY_HOP_FINAL_SETTLE_TICKS
                : BOUNDARY_HOP_MIN_SETTLE_TICKS;
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        boolean commandAccepted = teleportPlayer(serverPlayer, targetX, targetY, targetZ);
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] boundary-hop player={}, step={}/{}, viewDistance={}, targetChunkX={}, currentChunkX={}, target=({}, {}, {}), settleTicks={}, accepted={}",
                serverPlayer.getGameProfile().getName(),
                state.nextWaypointIndex() + 1,
                BOUNDARY_HOP_TOTAL_TELEPORTS,
                resolveBoundaryHopViewDistance(serverPlayer),
                resolveChunkXForPosition(targetX),
                serverPlayer.chunkPosition().x,
                formatDouble(targetX),
                formatDouble(targetY),
                formatDouble(targetZ),
                settleTicks,
                commandAccepted
        );
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex(),
                state.nextBlockEntityPulseIndex(),
                state.nextWaypointIndex() + 1,
                settleTicks
        );
    }

    private static PathState advanceDimensionHopPath(ServerPlayer serverPlayer, PathState state) {
        if (!ExperientChunkHotspotFullChunkTracker.hasPlayerBeenQuietFor(
                serverPlayer,
                DIMENSION_HOP_QUIET_WINDOW_MILLIS
        )) {
            return state.withDelayTicksRemaining(1);
        }

        if (state.nextWaypointIndex() >= DIMENSION_HOP_TOTAL_SWITCHES) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientChunkPath] Completed dimension-hop reuse path for player={}, roundTrips={}",
                    serverPlayer.getGameProfile().getName(),
                    DIMENSION_HOP_TOTAL_ROUND_TRIPS
            );
            disconnectPlayerAfterPathCompletion(serverPlayer);
            return null;
        }

        ServerLevel currentLevel = serverPlayer.serverLevel();
        ServerLevel targetLevel = resolveDimensionHopAlternateLevel(serverPlayer);
        if (targetLevel == null) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ExperientChunkPath] Dimension-hop target level is missing for player={}, currentDimension={}",
                    serverPlayer.getGameProfile().getName(),
                    resolveDimensionName(currentLevel)
            );
            disconnectPlayerAfterPathCompletion(serverPlayer);
            return null;
        }

        double targetX = state.originX();
        double targetY = resolveSafePathY(targetLevel);
        double targetZ = state.originZ();
        int settleTicks = state.nextWaypointIndex() + 1 >= DIMENSION_HOP_TOTAL_SWITCHES
                ? DIMENSION_HOP_FINAL_SETTLE_TICKS
                : DIMENSION_HOP_MIN_SETTLE_TICKS;
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        boolean commandAccepted = teleportPlayerToLevel(serverPlayer, targetLevel, targetX, targetY, targetZ);
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] dimension-hop player={}, step={}/{}, roundTrip={}/{}, from={}, to={}, target=({}, {}, {}), settleTicks={}, accepted={}",
                serverPlayer.getGameProfile().getName(),
                state.nextWaypointIndex() + 1,
                DIMENSION_HOP_TOTAL_SWITCHES,
                (state.nextWaypointIndex() / 2) + 1,
                DIMENSION_HOP_TOTAL_ROUND_TRIPS,
                resolveDimensionName(currentLevel),
                resolveDimensionName(targetLevel),
                formatDouble(targetX),
                formatDouble(targetY),
                formatDouble(targetZ),
                settleTicks,
                commandAccepted
        );
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex(),
                state.nextBlockEntityPulseIndex(),
                state.nextWaypointIndex() + 1,
                settleTicks
        );
    }

    private static void maybeEmitDelayedBeforeAckProbeBurst(ServerPlayer serverPlayer, PathState state) {
        if (ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()
                || ExperientChunkHotspotPathRuntimeConfig.isBoundaryHopMode()
                || ExperientChunkHotspotPathRuntimeConfig.isDimensionHopMode()
                || ExperientChunkHotspotPathRuntimeConfig.isRangeBounceMode()) {
            return;
        }
        if (state.nextWaypointIndex() <= 0) {
            return;
        }

        TeleportWaypoint previousWaypoint = TELEPORT_SEQUENCE[state.nextWaypointIndex() - 1];
        if (!previousWaypoint.triggerBeforeAckProbeBurst()
                || state.delayTicksRemaining() != BEFORE_ACK_PROBE_EMIT_REMAINING_TICKS) {
            return;
        }

        double targetX = state.originX() + previousWaypoint.offsetX();
        double targetY = state.originY() + previousWaypoint.offsetY();
        double targetZ = state.originZ() + previousWaypoint.offsetZ();
        emitBeforeAckProbeBurst(serverPlayer, targetX, targetY, targetZ);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] Emitted delayed before-ack probes for player={}, target=({}, {}, {}), remainingTicks={}",
                serverPlayer.getGameProfile().getName(),
                formatDouble(targetX),
                formatDouble(targetY),
                formatDouble(targetZ),
                state.delayTicksRemaining()
        );
    }

    private static PathState applyLightPulse(ServerPlayer serverPlayer, PathState state) {
        BlockPos probePos = BlockPos.containing(
                state.originX(),
                state.originY() + LIGHT_PROBE_Y_OFFSET,
                state.originZ()
        );
        boolean placeLightBlock = LIGHT_PULSE_SEQUENCE[state.nextLightPulseIndex()];
        serverPlayer.serverLevel().setBlockAndUpdate(
                probePos,
                placeLightBlock ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.AIR.defaultBlockState()
        );
        int nextDelayTicks = state.nextLightPulseIndex() + 1 >= LIGHT_PULSE_SEQUENCE.length
                ? SECTION_SETTLE_DELAY_TICKS
                : LIGHT_PULSE_DELAY_TICKS;
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex() + 1,
                state.nextSectionPulseIndex(),
                state.nextBlockEntityPulseIndex(),
                state.nextWaypointIndex(),
                nextDelayTicks
        );
    }

    //  section block update，
    private static PathState applySectionPulse(ServerPlayer serverPlayer, PathState state) {
        BlockPos anchorPos = resolveSectionProbeAnchor(state.originX(), state.originY(), state.originZ());
        boolean useStonePattern = SECTION_PULSE_SEQUENCE[state.nextSectionPulseIndex()];
        applySectionPattern(serverPlayer, anchorPos, useStonePattern);
        int nextDelayTicks = state.nextSectionPulseIndex() + 1 >= SECTION_PULSE_SEQUENCE.length
                ? POST_SECTION_PULSE_DELAY_TICKS
                : SECTION_PULSE_DELAY_TICKS;
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex() + 1,
                state.nextBlockEntityPulseIndex(),
                state.nextWaypointIndex(),
                nextDelayTicks
        );
    }

    private static PathState applyBlockEntityPulse(ServerPlayer serverPlayer, PathState state) {
        BlockPos probePos = resolveBlockEntityProbePos(state.originX(), state.originY(), state.originZ());
        SignBlockEntity signBlockEntity = ensureBlockEntityProbeInstalled(serverPlayer, probePos);
        if (signBlockEntity == null) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ExperientChunkPath] Failed to prepare block entity probe for player={}, pos={}",
                    serverPlayer.getGameProfile().getName(),
                    probePos
            );
            return new PathState(
                    state.originX(),
                    state.originY(),
                    state.originZ(),
                    state.nextLightPulseIndex(),
                    state.nextSectionPulseIndex(),
                    state.nextBlockEntityPulseIndex() + 1,
                    state.nextWaypointIndex(),
                    BLOCK_ENTITY_PULSE_DELAY_TICKS
            );
        }

        applyBlockEntityProbeText(
                signBlockEntity,
                BLOCK_ENTITY_VARIANT_SEQUENCE[state.nextBlockEntityPulseIndex()]
        );
        signBlockEntity.setChanged();
        broadcastBlockEntityProbeUpdate(serverPlayer, signBlockEntity);

        int nextDelayTicks = state.nextBlockEntityPulseIndex() + 1 >= BLOCK_ENTITY_VARIANT_SEQUENCE.length
                ? POST_BLOCK_ENTITY_PULSE_DELAY_TICKS
                : BLOCK_ENTITY_PULSE_DELAY_TICKS;
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex(),
                state.nextBlockEntityPulseIndex() + 1,
                state.nextWaypointIndex(),
                nextDelayTicks
        );
    }

    private static void emitBeforeAckProbeBurst(ServerPlayer serverPlayer, double baseX, double baseY, double baseZ) {
        emitBeforeAckLightBurst(serverPlayer, baseX, baseY, baseZ);
        emitBeforeAckSectionBurst(serverPlayer, baseX, baseY, baseZ);
        emitBeforeAckBlockEntityBurst(serverPlayer, baseX, baseY, baseZ);
    }

    private static void emitBeforeAckLightBurst(ServerPlayer serverPlayer, double baseX, double baseY, double baseZ) {
        BlockPos probeCenter = BlockPos.containing(baseX, baseY + BEFORE_ACK_LIGHT_PROBE_Y_OFFSET, baseZ);
        prepareLightProbeChamber(serverPlayer, probeCenter);
        setLightProbeState(serverPlayer, probeCenter, true);
        sendLightUpdateProbePacket(serverPlayer, probeCenter);
        setLightProbeState(serverPlayer, probeCenter, false);
        sendLightUpdateProbePacket(serverPlayer, probeCenter);
    }

    private static void emitBeforeAckSectionBurst(ServerPlayer serverPlayer, double baseX, double baseY, double baseZ) {
        BlockPos anchorPos = resolveSectionProbeAnchor(baseX, baseY, baseZ);
        BlockState firstPatternState = selectFirstBeforeAckSectionProbeState(
                serverPlayer.serverLevel().getBlockState(anchorPos)
        );
        BlockState secondPatternState = selectSecondBeforeAckSectionProbeState(firstPatternState);
        applyAndSendSectionPattern(serverPlayer, anchorPos, firstPatternState);
        applyAndSendSectionPattern(serverPlayer, anchorPos, secondPatternState);
    }

    private static void emitBeforeAckBlockEntityBurst(ServerPlayer serverPlayer, double baseX, double baseY, double baseZ) {
        BlockPos probePos = resolveBlockEntityProbePos(baseX, baseY, baseZ);
        SignBlockEntity signBlockEntity = ensureBlockEntityProbeInstalled(serverPlayer, probePos);
        if (signBlockEntity == null) {
            return;
        }

        for (String variantSuffix : BEFORE_ACK_BLOCK_ENTITY_VARIANT_SEQUENCE) {
            applyBlockEntityProbeText(signBlockEntity, variantSuffix);
            signBlockEntity.setChanged();
            broadcastBlockEntityProbeUpdate(serverPlayer, signBlockEntity);
        }
    }

    private static void prepareLightProbeChamber(ServerPlayer serverPlayer, BlockPos probeCenter) {
        for (int offsetX = -LIGHT_CHAMBER_RADIUS; offsetX <= LIGHT_CHAMBER_RADIUS; offsetX++) {
            for (int offsetY = -LIGHT_CHAMBER_RADIUS; offsetY <= LIGHT_CHAMBER_RADIUS; offsetY++) {
                for (int offsetZ = -LIGHT_CHAMBER_RADIUS; offsetZ <= LIGHT_CHAMBER_RADIUS; offsetZ++) {
                    BlockPos currentPos = probeCenter.offset(offsetX, offsetY, offsetZ);
                    boolean boundary = Math.abs(offsetX) == LIGHT_CHAMBER_RADIUS
                            || Math.abs(offsetY) == LIGHT_CHAMBER_RADIUS
                            || Math.abs(offsetZ) == LIGHT_CHAMBER_RADIUS;
                    serverPlayer.serverLevel().setBlockAndUpdate(
                            currentPos,
                            boundary ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.AIR.defaultBlockState()
                    );
                }
            }
        }
    }

    private static void setLightProbeState(ServerPlayer serverPlayer, BlockPos probeCenter, boolean enabled) {
        serverPlayer.serverLevel().setBlockAndUpdate(
                probeCenter,
                enabled ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.AIR.defaultBlockState()
        );
    }

    private static void sendLightUpdateProbePacket(ServerPlayer serverPlayer, BlockPos probeCenter) {
        LevelLightEngine lightEngine = serverPlayer.serverLevel().getChunkSource().getLightEngine();
        lightEngine.checkBlock(probeCenter);
        int remainingPasses = MAX_LIGHT_ENGINE_UPDATE_PASSES;
        while (lightEngine.hasLightWork() && remainingPasses-- > 0) {
            lightEngine.runLightUpdates();
        }
        serverPlayer.connection.send(new ClientboundLightUpdatePacket(new ChunkPos(probeCenter), lightEngine, null, null));
    }


    private static void applySectionPattern(ServerPlayer serverPlayer, BlockPos anchorPos, boolean useStonePattern) {
        applySectionPattern(
                serverPlayer,
                anchorPos,
                useStonePattern ? Blocks.STONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState()
        );
    }

    private static void applySectionPattern(ServerPlayer serverPlayer, BlockPos anchorPos, BlockState blockState) {
        for (int offsetX = 0; offsetX < SECTION_PROBE_WIDTH; offsetX++) {
            for (int offsetY = 0; offsetY < SECTION_PROBE_HEIGHT; offsetY++) {
                for (int offsetZ = 0; offsetZ < SECTION_PROBE_DEPTH; offsetZ++) {
                    serverPlayer.serverLevel().setBlockAndUpdate(
                            anchorPos.offset(offsetX, offsetY, offsetZ),
                            blockState
                    );
                }
            }
        }
    }

    private static void applyAndSendSectionPattern(ServerPlayer serverPlayer, BlockPos anchorPos, BlockState blockState) {
        ShortSet sectionRelativePositions = new ShortOpenHashSet();
        for (int offsetX = 0; offsetX < SECTION_PROBE_WIDTH; offsetX++) {
            for (int offsetY = 0; offsetY < SECTION_PROBE_HEIGHT; offsetY++) {
                for (int offsetZ = 0; offsetZ < SECTION_PROBE_DEPTH; offsetZ++) {
                    BlockPos currentPos = anchorPos.offset(offsetX, offsetY, offsetZ);
                    serverPlayer.serverLevel().setBlockAndUpdate(currentPos, blockState);
                    sectionRelativePositions.add(SectionPos.sectionRelativePos(currentPos));
                }
            }
        }

        LevelChunk levelChunk = (LevelChunk) serverPlayer.serverLevel().getChunk(anchorPos);
        LevelChunkSection levelChunkSection = levelChunk.getSection(levelChunk.getSectionIndex(anchorPos.getY()));
        serverPlayer.connection.send(
                new ClientboundSectionBlocksUpdatePacket(SectionPos.of(anchorPos), sectionRelativePositions, levelChunkSection)
        );
    }

    private static BlockState selectFirstBeforeAckSectionProbeState(BlockState currentBlockState) {
        if (currentBlockState.is(Blocks.STONE)) {
            return Blocks.ANDESITE.defaultBlockState();
        }
        if (currentBlockState.is(Blocks.ANDESITE)) {
            return Blocks.DIORITE.defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState selectSecondBeforeAckSectionProbeState(BlockState firstPatternState) {
        if (firstPatternState.is(Blocks.STONE)) {
            return Blocks.ANDESITE.defaultBlockState();
        }
        if (firstPatternState.is(Blocks.ANDESITE)) {
            return Blocks.DIORITE.defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }


    private static BlockPos resolveSectionProbeAnchor(double baseX, double baseY, double baseZ) {
        int baseXBlock = floorToBlock(baseX);
        int baseYBlock = floorToBlock(baseY) + SECTION_PROBE_Y_OFFSET;
        int baseZBlock = floorToBlock(baseZ);
        int sectionMinX = (baseXBlock >> 4) << 4;
        int sectionMinY = (baseYBlock >> 4) << 4;
        int sectionMinZ = (baseZBlock >> 4) << 4;
        return new BlockPos(sectionMinX + 4, sectionMinY + 4, sectionMinZ + 4);
    }


    private static BlockPos resolveBlockEntityProbePos(double baseX, double baseY, double baseZ) {
        return BlockPos.containing(
                baseX + BLOCK_ENTITY_PROBE_X_OFFSET,
                baseY + BLOCK_ENTITY_PROBE_Y_OFFSET,
                baseZ + BLOCK_ENTITY_PROBE_Z_OFFSET
        );
    }

    private static SignBlockEntity ensureBlockEntityProbeInstalled(ServerPlayer serverPlayer, BlockPos probePos) {
        BlockState blockState = serverPlayer.serverLevel().getBlockState(probePos);
        if (!blockState.is(Blocks.OAK_SIGN)) {
            serverPlayer.serverLevel().setBlockAndUpdate(probePos, Blocks.OAK_SIGN.defaultBlockState());
        }

        BlockEntity blockEntity = serverPlayer.serverLevel().getBlockEntity(probePos);
        if (blockEntity instanceof SignBlockEntity signBlockEntity) {
            return signBlockEntity;
        }
        return null;
    }


    private static void applyBlockEntityProbeText(SignBlockEntity signBlockEntity, String variantSuffix) {
        SignText frontText = signBlockEntity.getFrontText()
                .setMessage(0, Component.literal(BLOCK_ENTITY_FRONT_LINE_ONE_PREFIX + variantSuffix))
                .setMessage(1, Component.literal(BLOCK_ENTITY_FRONT_LINE_TWO))
                .setMessage(2, Component.literal(BLOCK_ENTITY_FRONT_LINE_THREE))
                .setMessage(3, Component.literal(BLOCK_ENTITY_FRONT_LINE_FOUR))
                .setHasGlowingText(true);
        SignText backText = signBlockEntity.getBackText()
                .setMessage(0, Component.literal(BLOCK_ENTITY_BACK_LINE_ONE))
                .setMessage(1, Component.literal(BLOCK_ENTITY_BACK_LINE_TWO))
                .setMessage(2, Component.literal(BLOCK_ENTITY_BACK_LINE_THREE))
                .setMessage(3, Component.literal(BLOCK_ENTITY_BACK_LINE_FOUR))
                .setHasGlowingText(true);
        signBlockEntity.setText(frontText, true);
        signBlockEntity.setText(backText, false);
    }

    private static void broadcastBlockEntityProbeUpdate(
            ServerPlayer serverPlayer,
            SignBlockEntity signBlockEntity
    ) {
        Packet<?> updatePacket = signBlockEntity.getUpdatePacket();
        if (updatePacket != null) {
            serverPlayer.connection.send(updatePacket);
        }
    }

    private static int floorToBlock(double value) {
        return (int) Math.floor(value);
    }


    private static void prepareBoundaryHopPlayer(ServerPlayer serverPlayer) {
        if (serverPlayer == null)
            return;

        double safeY = resolveSafePathY(serverPlayer);
        double leftX = resolveBoundaryHopLeftX(serverPlayer.getX());
        prepareSafeChunkPathPlayer(serverPlayer, leftX, safeY, serverPlayer.getZ(), "boundary-hop");
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] Prepared boundary-hop anchor player={}, leftChunkX={}, rightChunkX={}, viewDistance={}",
                serverPlayer.getGameProfile().getName(),
                resolveChunkXForPosition(leftX),
                resolveChunkXForPosition(resolveBoundaryHopRightX(serverPlayer, leftX)),
                resolveBoundaryHopViewDistance(serverPlayer)
        );
    }

    private static void prepareRangeBouncePlayer(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return;
        }

        double startX = ExperientChunkHotspotPathRuntimeConfig.rangeStartX();
        double startY = ExperientChunkHotspotPathRuntimeConfig.rangeStartY();
        double startZ = ExperientChunkHotspotPathRuntimeConfig.rangeStartZ();
        prepareSafeChunkPathPlayer(serverPlayer, startX, startY, startZ, "range-bounce");
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] Prepared range-bounce player={}, start=({}, {}, {}), end=({}, {}, {}), stepBlocks={}, roundTrips={}, waypointCount={}",
                serverPlayer.getGameProfile().getName(),
                formatDouble(startX),
                formatDouble(startY),
                formatDouble(startZ),
                formatDouble(ExperientChunkHotspotPathRuntimeConfig.rangeEndX()),
                formatDouble(ExperientChunkHotspotPathRuntimeConfig.rangeEndY()),
                formatDouble(ExperientChunkHotspotPathRuntimeConfig.rangeEndZ()),
                formatDouble(ExperientChunkHotspotPathRuntimeConfig.rangeStepBlocks()),
                ExperientChunkHotspotPathRuntimeConfig.rangeRoundTrips(),
                RANGE_BOUNCE_SEQUENCE.length
        );
    }

    private static void prepareTwoPointReusePlayer(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return;
        }

        double safeY = resolveSafePathY(serverPlayer);
        prepareSafeChunkPathPlayer(serverPlayer, serverPlayer.getX(), safeY, serverPlayer.getZ(), "two-point-reuse");
    }

    private static void prepareDimensionHopPlayer(ServerPlayer serverPlayer) {
        if (serverPlayer == null || serverPlayer.getServer() == null) {
            return;
        }

        ServerLevel currentLevel = serverPlayer.serverLevel();
        if (!isDimensionHopReusableLevel(currentLevel)) {
            ServerLevel overworld = serverPlayer.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                teleportPlayerToLevel(
                        serverPlayer,
                        overworld,
                        serverPlayer.getX(),
                        resolveSafePathY(overworld),
                        serverPlayer.getZ()
                );
                currentLevel = overworld;
            }
        }

        double safeY = resolveSafePathY(currentLevel);
        prepareSafeChunkPathPlayer(serverPlayer, serverPlayer.getX(), safeY, serverPlayer.getZ(), "dimension-hop");
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] Prepared dimension-hop player={}, primaryDimension={}, alternateDimension={}, roundTrips={}",
                serverPlayer.getGameProfile().getName(),
                resolveDimensionName(serverPlayer.serverLevel()),
                resolveDimensionName(resolveDimensionHopAlternateLevel(serverPlayer)),
                DIMENSION_HOP_TOTAL_ROUND_TRIPS
        );
    }

    private static void prepareSafeChunkPathPlayer(
            ServerPlayer serverPlayer,
            double targetX,
            double targetY,
            double targetZ,
            String pathLabel
    ) {
        if (serverPlayer == null) {
            return;
        }

        serverPlayer.setGameMode(GameType.SPECTATOR);
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        if (isSameTargetPosition(serverPlayer, targetX, targetY, targetZ)) {
            return;
        }

        boolean commandAccepted = teleportPlayer(serverPlayer, targetX, targetY, targetZ);
        stabilizeTwoPointReusePlayerMotion(serverPlayer);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] Prepared {} player={}, target=({}, {}, {}), accepted={}",
                pathLabel,
                serverPlayer.getGameProfile().getName(),
                formatDouble(targetX),
                formatDouble(targetY),
                formatDouble(targetZ),
                commandAccepted
        );
    }


    private static double resolvePathOriginX(ServerPlayer serverPlayer) {
        if (serverPlayer == null)
            return 0.0D;
        if (ExperientChunkHotspotPathRuntimeConfig.isBoundaryHopMode())
            return resolveBoundaryHopLeftX(serverPlayer.getX());
        return serverPlayer.getX();
    }

    private static double resolvePathOriginY(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return 0.0D;
        }

        if (!ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()
                && !ExperientChunkHotspotPathRuntimeConfig.isBoundaryHopMode()
                && !ExperientChunkHotspotPathRuntimeConfig.isDimensionHopMode()) {
            return serverPlayer.getY();
        }
        return resolveSafePathY(serverPlayer);
    }

    private static boolean isDimensionHopReusableLevel(ServerLevel serverLevel) {
        if (serverLevel == null) {
            return false;
        }
        return Level.OVERWORLD.equals(serverLevel.dimension()) || Level.NETHER.equals(serverLevel.dimension());
    }

    private static ServerLevel resolveDimensionHopAlternateLevel(ServerPlayer serverPlayer) {
        if (serverPlayer == null || serverPlayer.getServer() == null) {
            return null;
        }

        if (Level.NETHER.equals(serverPlayer.serverLevel().dimension())) {
            return serverPlayer.getServer().getLevel(Level.OVERWORLD);
        }
        return serverPlayer.getServer().getLevel(Level.NETHER);
    }

    private static double resolveSafePathY(ServerPlayer serverPlayer) {
        return resolveSafePathY(serverPlayer == null ? null : serverPlayer.serverLevel());
    }

    private static double resolveSafePathY(ServerLevel serverLevel) {
        if (serverLevel == null) {
            return TWO_POINT_REUSE_SAFE_Y;
        }

        double minSafeY = serverLevel.getMinBuildHeight() + TWO_POINT_REUSE_SAFE_Y_MARGIN;
        double maxSafeY = serverLevel.getMaxBuildHeight() - TWO_POINT_REUSE_SAFE_Y_MARGIN;
        if (maxSafeY < minSafeY) {
            return TWO_POINT_REUSE_SAFE_Y;
        }
        return Math.max(minSafeY, Math.min(TWO_POINT_REUSE_SAFE_Y, maxSafeY));
    }


    private static double resolveBoundaryHopLeftX(double currentX) {
        int baseBlockX = floorToBlock(currentX);
        int currentChunkMinX = (baseBlockX >> 4) << 4;
        return currentChunkMinX + 8.5D;
    }

    private static double resolveBoundaryHopRightX(ServerPlayer serverPlayer, double leftX) {
        return leftX + (resolveBoundaryHopOffsetChunks(serverPlayer) * 16.0D);
    }

    private static int resolveBoundaryHopOffsetChunks(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return BOUNDARY_HOP_MIN_OFFSET_CHUNKS;
        }

        int requestedViewDistance = Math.max(resolveBoundaryHopViewDistance(serverPlayer), 0);
        return Math.max(requestedViewDistance + BOUNDARY_HOP_EXTRA_OFFSET_CHUNKS, BOUNDARY_HOP_MIN_OFFSET_CHUNKS);
    }

    private static int resolveBoundaryHopViewDistance(ServerPlayer serverPlayer) {
        if (serverPlayer == null || serverPlayer.getServer() == null) {
            return 0;
        }

        return Math.max(serverPlayer.getServer().getPlayerList().getViewDistance(), 0);
    }

    private static boolean hasBoundaryHopReachedPreviousChunk(ServerPlayer serverPlayer, PathState state) {
        if (serverPlayer == null || state == null || state.nextWaypointIndex() <= 0) {
            return true;
        }

        double previousTargetX = resolveBoundaryHopPreviousTargetX(serverPlayer, state);
        return serverPlayer.chunkPosition().x == resolveChunkXForPosition(previousTargetX);
    }

    private static double resolveBoundaryHopPreviousTargetX(ServerPlayer serverPlayer, PathState state) {
        double leftX = state.originX();
        double rightX = resolveBoundaryHopRightX(serverPlayer, leftX);
        int previousStepIndex = Math.max(state.nextWaypointIndex() - 1, 0);
        return previousStepIndex % 2 == 0 ? rightX : leftX;
    }

    private static int resolveChunkXForPosition(double x) {
        return floorToBlock(x) >> 4;
    }

    private static boolean isSameTargetPosition(
            ServerPlayer serverPlayer,
            double targetX,
            double targetY,
            double targetZ
    ) {
        return serverPlayer != null
                && Math.abs(serverPlayer.getX() - targetX) < 0.01D
                && Math.abs(serverPlayer.getY() - targetY) < 0.01D
                && Math.abs(serverPlayer.getZ() - targetZ) < 0.01D;
    }

    private static void stabilizeTwoPointReusePlayerMotion(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return;
        }
        serverPlayer.setDeltaMovement(0.0D, 0.0D, 0.0D);
        serverPlayer.resetFallDistance();
    }

    private static boolean teleportPlayer(ServerPlayer serverPlayer, double targetX, double targetY, double targetZ) {
        if (serverPlayer.getServer() == null) {
            return false;
        }

        CommandSourceStack commandSource = serverPlayer.getServer()
                .createCommandSourceStack()
                .withSuppressedOutput()
                .withPermission(4);
        String command = "tp "
                + serverPlayer.getGameProfile().getName()
                + " "
                + formatDouble(targetX)
                + " "
                + formatDouble(targetY)
                + " "
                + formatDouble(targetZ);
        serverPlayer.getServer().getCommands().performPrefixedCommand(commandSource, command);
        return true;
    }

    private static boolean teleportPlayerToLevel(
            ServerPlayer serverPlayer,
            ServerLevel targetLevel,
            double targetX,
            double targetY,
            double targetZ
    ) {
        if (serverPlayer == null || serverPlayer.getServer() == null || targetLevel == null) {
            return false;
        }
        if (serverPlayer.serverLevel() == targetLevel) {
            return teleportPlayer(serverPlayer, targetX, targetY, targetZ);
        }

        CommandSourceStack commandSource = serverPlayer.getServer()
                .createCommandSourceStack()
                .withSuppressedOutput()
                .withPermission(4);
        String command = "execute in "
                + targetLevel.dimension().location()
                + " run tp "
                + serverPlayer.getGameProfile().getName()
                + " "
                + formatDouble(targetX)
                + " "
                + formatDouble(targetY)
                + " "
                + formatDouble(targetZ);
        serverPlayer.getServer().getCommands().performPrefixedCommand(commandSource, command);
        return true;
    }

    private static String resolveDimensionName(ServerLevel serverLevel) {
        return serverLevel == null ? "<missing>" : serverLevel.dimension().location().toString();
    }


    private static void disconnectPlayerAfterPathCompletion(ServerPlayer serverPlayer) {
        if (serverPlayer == null || serverPlayer.connection == null) {
            return;
        }
        com.PinkCats.bandwidthoptimizer.experimental.runall.RunAllProbeFiles.markChunkHotspotPathCompleted(serverPlayer);
        if (ExperientChunkHotspotPathRuntimeConfig.shouldUseRunAllMarkerExit()) {
            return;
        }
        serverPlayer.connection.disconnect(Component.literal("BandwidthOptimizer experient path completed"));
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static TeleportWaypoint[] createTwoPointReuseSequence() {
        TeleportWaypoint[] sequence = new TeleportWaypoint[TWO_POINT_REUSE_TOTAL_TELEPORTS];
        for (int index = 0; index < sequence.length; index++) {
            double offsetX = index % 2 == 0 ? TWO_POINT_REUSE_OFFSET_BLOCKS : 0.0D;
            int settleTicks = index + 1 >= sequence.length
                    ? TWO_POINT_REUSE_FINAL_SETTLE_TICKS
                    : TWO_POINT_REUSE_MIN_SETTLE_TICKS;
            sequence[index] = new TeleportWaypoint(offsetX, 0.0D, 0.0D, settleTicks, false);
        }
        return sequence;
    }

    private static TeleportWaypoint[] createRangeBounceSequence() {
        double startX = ExperientChunkHotspotPathRuntimeConfig.rangeStartX();
        double startY = ExperientChunkHotspotPathRuntimeConfig.rangeStartY();
        double startZ = ExperientChunkHotspotPathRuntimeConfig.rangeStartZ();
        double endX = ExperientChunkHotspotPathRuntimeConfig.rangeEndX();
        double endY = ExperientChunkHotspotPathRuntimeConfig.rangeEndY();
        double endZ = ExperientChunkHotspotPathRuntimeConfig.rangeEndZ();
        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double deltaZ = endZ - startZ;
        double pathLength = Math.sqrt((deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ));
        if (pathLength <= 0.0001D) {
            return new TeleportWaypoint[0];
        }

        double unitX = deltaX / pathLength;
        double unitY = deltaY / pathLength;
        double unitZ = deltaZ / pathLength;
        double stepBlocks = ExperientChunkHotspotPathRuntimeConfig.rangeStepBlocks();
        int roundTrips = ExperientChunkHotspotPathRuntimeConfig.rangeRoundTrips();
        int totalLegCount = Math.max(roundTrips, 1) * 2;
        List<TeleportWaypoint> sequence = new ArrayList<>();
        boolean forwardLeg = true;
        for (int legIndex = 0; legIndex < totalLegCount; legIndex++) {
            appendRangeBounceLeg(sequence, unitX, unitY, unitZ, pathLength, stepBlocks, forwardLeg);
            forwardLeg = !forwardLeg;
        }
        if (sequence.isEmpty()) {
            return new TeleportWaypoint[0];
        }

        ArrayList<TeleportWaypoint> finalizedSequence = new ArrayList<>(sequence.size());
        for (int index = 0; index < sequence.size(); index++) {
            TeleportWaypoint waypoint = sequence.get(index);
            int settleTicks = index + 1 >= sequence.size()
                    ? RANGE_BOUNCE_FINAL_SETTLE_TICKS
                    : RANGE_BOUNCE_MIN_SETTLE_TICKS;
            finalizedSequence.add(
                    new TeleportWaypoint(
                            waypoint.offsetX(),
                            waypoint.offsetY(),
                            waypoint.offsetZ(),
                            settleTicks,
                            false
                    )
            );
        }
        return finalizedSequence.toArray(TeleportWaypoint[]::new);
    }

    private static void appendRangeBounceLeg(
            List<TeleportWaypoint> sequence,
            double unitX,
            double unitY,
            double unitZ,
            double pathLength,
            double stepBlocks,
            boolean forwardLeg
    ) {
        if (sequence == null || pathLength <= 0.0001D || stepBlocks <= 0.0D) {
            return;
        }

        if (forwardLeg) {
            double travelledBlocks = stepBlocks;
            while (travelledBlocks < pathLength) {
                sequence.add(buildRangeBounceWaypoint(unitX, unitY, unitZ, travelledBlocks));
                travelledBlocks += stepBlocks;
            }
            sequence.add(buildRangeBounceWaypoint(unitX, unitY, unitZ, pathLength));
            return;
        }

        double travelledBlocks = Math.max(pathLength - stepBlocks, 0.0D);
        while (travelledBlocks > 0.0D) {
            sequence.add(buildRangeBounceWaypoint(unitX, unitY, unitZ, travelledBlocks));
            travelledBlocks -= stepBlocks;
        }
        sequence.add(buildRangeBounceWaypoint(unitX, unitY, unitZ, 0.0D));
    }

    private static TeleportWaypoint buildRangeBounceWaypoint(
            double unitX,
            double unitY,
            double unitZ,
            double travelledBlocks
    ) {
        return new TeleportWaypoint(
                unitX * travelledBlocks,
                unitY * travelledBlocks,
                unitZ * travelledBlocks,
                RANGE_BOUNCE_MIN_SETTLE_TICKS,
                false
        );
    }

    private record PathState(
            double originX,
            double originY,
            double originZ,
            int nextLightPulseIndex,
            int nextSectionPulseIndex,
            int nextBlockEntityPulseIndex,
            int nextWaypointIndex,
            int delayTicksRemaining
    ) {

        private PathState withDelayTicksRemaining(int delayTicksRemaining) {
            return new PathState(
                    this.originX,
                    this.originY,
                    this.originZ,
                    this.nextLightPulseIndex,
                    this.nextSectionPulseIndex,
                    this.nextBlockEntityPulseIndex,
                    this.nextWaypointIndex,
                    Math.max(delayTicksRemaining, 0)
            );
        }
    }

    private record TeleportWaypoint(
            double offsetX,
            double offsetY,
            double offsetZ,
            int settleTicks,
            boolean triggerBeforeAckProbeBurst
    ) {
    }
}
