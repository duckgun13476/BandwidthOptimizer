package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExperientServerCommandController {

    private static final Map<UUID, PendingServerCommand> PENDING_SERVER_COMMANDS = new ConcurrentHashMap<>();

    private ExperientServerCommandController() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ExperientRuntimeFlags.isEnabled()
                || !ExperientServerCommandRuntimeConfig.isEnabled()
                || !(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        PENDING_SERVER_COMMANDS.put(
                serverPlayer.getUUID(),
                new PendingServerCommand(
                        ExperientServerCommandRuntimeConfig.readCommand(),
                        ExperientServerCommandRuntimeConfig.readDelayTicks()
                )
        );
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PENDING_SERVER_COMMANDS.remove(serverPlayer.getUUID());
        }
    }


    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !ExperientRuntimeFlags.isEnabled()
                || !ExperientServerCommandRuntimeConfig.isEnabled()
                || PENDING_SERVER_COMMANDS.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, PendingServerCommand> entry : PENDING_SERVER_COMMANDS.entrySet()) {
            ServerPlayer serverPlayer = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (serverPlayer == null) {
                PENDING_SERVER_COMMANDS.remove(entry.getKey());
                continue;
            }

            PendingServerCommand pendingServerCommand = entry.getValue();
            if (pendingServerCommand.delayTicksRemaining() > 0) {
                PENDING_SERVER_COMMANDS.put(
                        entry.getKey(),
                        pendingServerCommand.withDelayTicksRemaining(pendingServerCommand.delayTicksRemaining() - 1)
                );
                continue;
            }

            boolean accepted = executeServerCommand(serverPlayer, pendingServerCommand.command());
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientServerCommand] player={}, accepted={}, command={}",
                    serverPlayer.getGameProfile().getName(),
                    accepted,
                    pendingServerCommand.command()
            );
            PENDING_SERVER_COMMANDS.remove(entry.getKey());
        }
    }

    private static boolean executeServerCommand(ServerPlayer serverPlayer, String command) {
        if (serverPlayer == null || serverPlayer.getServer() == null || command == null || command.isBlank()) {
            return false;
        }

        CommandSourceStack commandSource = serverPlayer.getServer()
                .createCommandSourceStack()
                .withSuppressedOutput()
                .withPermission(4);
        return serverPlayer.getServer().getCommands().performPrefixedCommand(commandSource, command) > 0;
    }

    private record PendingServerCommand(String command, int delayTicksRemaining) {
        private PendingServerCommand withDelayTicksRemaining(int delayTicksRemaining) {
            return new PendingServerCommand(this.command, Math.max(delayTicksRemaining, 0));
        }
    }
}
