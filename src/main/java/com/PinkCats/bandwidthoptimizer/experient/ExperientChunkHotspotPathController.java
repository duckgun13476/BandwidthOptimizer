package com.PinkCats.bandwidthoptimizer.experient;

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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExperientChunkHotspotPathController {

    private static final int INITIAL_DELAY_TICKS = 80;
    private static final int LIGHT_PULSE_DELAY_TICKS = 20;
    private static final int SECTION_SETTLE_DELAY_TICKS = 200;
    private static final int SECTION_PULSE_DELAY_TICKS = 20;
    private static final int POST_SECTION_PULSE_DELAY_TICKS = 60;
    private static final int BLOCK_ENTITY_PULSE_DELAY_TICKS = 20;
    private static final int POST_BLOCK_ENTITY_PULSE_DELAY_TICKS = 60;
    private static final int FINAL_RETURN_SETTLE_TICKS = 200;
    private static final int TWO_POINT_REUSE_SETTLE_TICKS = 6;
    private static final int TWO_POINT_REUSE_FINAL_SETTLE_TICKS = 100;
    private static final int TWO_POINT_REUSE_TOTAL_TELEPORTS = 24;
    private static final double TWO_POINT_REUSE_OFFSET_BLOCKS = 320.0D;
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
            new TeleportWaypoint(320.0D, 0.0D, 12, true),
            new TeleportWaypoint(0.0D, 0.0D, 8, false),
            new TeleportWaypoint(320.0D, 0.0D, 8, false),
            new TeleportWaypoint(0.0D, 0.0D, 12, false),
            new TeleportWaypoint(0.0D, 320.0D, 12, true),
            new TeleportWaypoint(0.0D, 0.0D, 8, false),
            new TeleportWaypoint(0.0D, 320.0D, 8, false),
            new TeleportWaypoint(0.0D, 0.0D, FINAL_RETURN_SETTLE_TICKS, false)
    };
    private static final TeleportWaypoint[] TWO_POINT_REUSE_SEQUENCE = createTwoPointReuseSequence();

    private static final Map<UUID, PathState> PLAYER_PATH_STATES = new ConcurrentHashMap<>();

    private ExperientChunkHotspotPathController() {
    }

    @SubscribeEvent
    // Move tool
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ExperientRuntimeFlags.isEnabled()
                || !ExperientChunkHotspotPathRuntimeConfig.isEnabled()
                || !(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        PLAYER_PATH_STATES.put(
                serverPlayer.getUUID(),
                new PathState(
                        serverPlayer.getX(),
                        serverPlayer.getY(),
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
                formatDouble(serverPlayer.getX()),
                formatDouble(serverPlayer.getY()),
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
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !ExperientRuntimeFlags.isEnabled()
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
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientChunkPath] Completed scripted path for player={}",
                    serverPlayer.getGameProfile().getName()
            );
            disconnectPlayerAfterPathCompletion(serverPlayer);
            return null;
        }

        TeleportWaypoint waypoint = TELEPORT_SEQUENCE[state.nextWaypointIndex()];
        double targetX = state.originX() + waypoint.offsetX();
        double targetY = state.originY();
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
        double targetY = state.originY();
        double targetZ = state.originZ() + waypoint.offsetZ();
        boolean commandAccepted = teleportPlayer(serverPlayer, targetX, targetY, targetZ);
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


    private static void maybeEmitDelayedBeforeAckProbeBurst(ServerPlayer serverPlayer, PathState state) {
        if (ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()) {
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
        double targetY = state.originY();
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
        return serverPlayer.getServer().getCommands().performPrefixedCommand(commandSource, command) > 0;
    }


    private static void disconnectPlayerAfterPathCompletion(ServerPlayer serverPlayer) {
        if (serverPlayer == null || serverPlayer.connection == null) {
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
                    : TWO_POINT_REUSE_SETTLE_TICKS;
            sequence[index] = new TeleportWaypoint(offsetX, 0.0D, settleTicks, false);
        }
        return sequence;
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
            double offsetZ,
            int settleTicks,
            boolean triggerBeforeAckProbeBurst
    ) {
    }
}
