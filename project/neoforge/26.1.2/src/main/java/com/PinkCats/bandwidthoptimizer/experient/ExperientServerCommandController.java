package com.PinkCats.bandwidthoptimizer.experient;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID)
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
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ExperientRuntimeFlags.isEnabled()
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
                    serverPlayer.getGameProfile().name(),
                    accepted,
                    pendingServerCommand.command()
            );
            PENDING_SERVER_COMMANDS.remove(entry.getKey());
        }
    }

    private static boolean executeServerCommand(ServerPlayer serverPlayer, String command) {
        if (serverPlayer == null || serverPlayer.level().getServer() == null || command == null || command.isBlank()) {
            return false;
        }

        CommandSourceStack commandSource = serverPlayer.level().getServer()
                .createCommandSourceStack()
                .withSuppressedOutput()
                .withPermission(PermissionSet.ALL_PERMISSIONS);
        serverPlayer.level().getServer().getCommands().performPrefixedCommand(commandSource, command);
        return true;
    }

    private record PendingServerCommand(String command, int delayTicksRemaining) {
        private PendingServerCommand withDelayTicksRemaining(int delayTicksRemaining) {
            return new PendingServerCommand(this.command, Math.max(delayTicksRemaining, 0));
        }
    }
}
