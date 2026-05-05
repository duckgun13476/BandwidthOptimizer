package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExperientServerCommandController {

    private static final Map<UUID, PendingServerCommand> PENDING_SERVER_COMMANDS = new ConcurrentHashMap<>();

    private ExperientServerCommandController() {}

    public static void onPlayerLoggedIn(ServerPlayer serverPlayer) {
        if (!ExperientRuntimeFlags.isEnabled()
                || !ExperientServerCommandRuntimeConfig.isEnabled()
                || serverPlayer == null) {
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

    public static void onPlayerLoggedOut(ServerPlayer serverPlayer) {
        if (serverPlayer != null) {
            PENDING_SERVER_COMMANDS.remove(serverPlayer.getUUID());
        }
    }


    public static void onServerTick(MinecraftServer server) {
        if (!ExperientRuntimeFlags.isEnabled()
                || !ExperientServerCommandRuntimeConfig.isEnabled()
                || server == null
                || PENDING_SERVER_COMMANDS.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, PendingServerCommand> entry : PENDING_SERVER_COMMANDS.entrySet()) {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(entry.getKey());
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

