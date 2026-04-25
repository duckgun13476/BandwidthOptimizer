package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
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
    private static final int STEP_DELAY_TICKS = 60;
    private static final int LIGHT_PROBE_Y_OFFSET = 4;
    private static final int SECTION_PROBE_Y_OFFSET = -8;
    private static final boolean[] LIGHT_PULSE_SEQUENCE = {true, false, true, false};
    private static final boolean[] SECTION_PULSE_SEQUENCE = {true, false, true, false};
    private static final int SECTION_PROBE_WIDTH = 6;
    private static final int SECTION_PROBE_HEIGHT = 4;
    private static final int SECTION_PROBE_DEPTH = 6;
    private static final double[][] WAYPOINT_OFFSETS = {
            {320.0D, 0.0D},
            {320.0D, 320.0D},
            {0.0D, 320.0D},
            {0.0D, 0.0D}
    };

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

    private static PathState advancePath(ServerPlayer serverPlayer, PathState state) {
        if (state.delayTicksRemaining() > 0) {
            return state.withDelayTicksRemaining(state.delayTicksRemaining() - 1);
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

        if (state.nextWaypointIndex() >= WAYPOINT_OFFSETS.length) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientChunkPath] Completed scripted path for player={}",
                    serverPlayer.getGameProfile().getName()
            );
            return null;
        }

        double[] waypointOffset = WAYPOINT_OFFSETS[state.nextWaypointIndex()];
        double targetX = state.originX() + waypointOffset[0];
        double targetY = state.originY();
        double targetZ = state.originZ() + waypointOffset[1];
        boolean commandAccepted = teleportPlayer(serverPlayer, targetX, targetY, targetZ);
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientChunkPath] player={}, step={}/{}, target=({}, {}, {}), accepted={}",
                serverPlayer.getGameProfile().getName(),
                state.nextWaypointIndex() + 1,
                WAYPOINT_OFFSETS.length,
                formatDouble(targetX),
                formatDouble(targetY),
                formatDouble(targetZ),
                commandAccepted
        );
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex(),
                state.nextWaypointIndex() + 1,
                STEP_DELAY_TICKS
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
                state.nextWaypointIndex(),
                nextDelayTicks
        );
    }

    private static PathState applySectionPulse(ServerPlayer serverPlayer, PathState state) {
        BlockPos anchorPos = resolveSectionProbeAnchor(state);
        boolean useStonePattern = SECTION_PULSE_SEQUENCE[state.nextSectionPulseIndex()];
        for (int offsetX = 0; offsetX < SECTION_PROBE_WIDTH; offsetX++) {
            for (int offsetY = 0; offsetY < SECTION_PROBE_HEIGHT; offsetY++) {
                for (int offsetZ = 0; offsetZ < SECTION_PROBE_DEPTH; offsetZ++) {
                    serverPlayer.serverLevel().setBlockAndUpdate(
                            anchorPos.offset(offsetX, offsetY, offsetZ),
                            useStonePattern ? Blocks.STONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState()
                    );
                }
            }
        }
        int nextDelayTicks = state.nextSectionPulseIndex() + 1 >= SECTION_PULSE_SEQUENCE.length
                ? POST_SECTION_PULSE_DELAY_TICKS
                : SECTION_PULSE_DELAY_TICKS;
        return new PathState(
                state.originX(),
                state.originY(),
                state.originZ(),
                state.nextLightPulseIndex(),
                state.nextSectionPulseIndex() + 1,
                state.nextWaypointIndex(),
                nextDelayTicks
        );
    }

    private static BlockPos resolveSectionProbeAnchor(PathState state) {
        int baseX = floorToBlock(state.originX());
        int baseY = floorToBlock(state.originY()) + SECTION_PROBE_Y_OFFSET;
        int baseZ = floorToBlock(state.originZ());
        int sectionMinX = (baseX >> 4) << 4;
        int sectionMinY = (baseY >> 4) << 4;
        int sectionMinZ = (baseZ >> 4) << 4;
        return new BlockPos(sectionMinX + 4, sectionMinY + 4, sectionMinZ + 4);
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

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record PathState(
            double originX,
            double originY,
            double originZ,
            int nextLightPulseIndex,
            int nextSectionPulseIndex,
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
                    this.nextWaypointIndex,
                    Math.max(delayTicksRemaining, 0)
            );
        }
    }
}
