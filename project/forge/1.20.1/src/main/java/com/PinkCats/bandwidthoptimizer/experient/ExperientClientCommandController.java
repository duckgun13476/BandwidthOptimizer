package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExperientClientCommandController {
    private static boolean commandSent;
    private static int delayTicksRemaining = -1;
    private static String activeConnectionKey = "";

    private ExperientClientCommandController() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || commandSent
                || !ExperientRuntimeFlags.isEnabled()
                || !ExperientClientCommandRuntimeConfig.isEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            resetCommandState();
            return;
        }
        ClientPacketListener connection = minecraft.player.connection;
        if (connection == null) {
            resetCommandState();
            return;
        }
        refreshCommandStateForConnection(minecraft, connection);

        if (delayTicksRemaining < 0) {
            delayTicksRemaining = ExperientClientCommandRuntimeConfig.readDelayTicks();
        }
        if (delayTicksRemaining > 0) {
            delayTicksRemaining--;
            return;
        }

        String command = normalizeCommand(ExperientClientCommandRuntimeConfig.readCommand());
        if (command.isBlank()) {
            commandSent = true;
            return;
        }

        long sentMillis = System.currentTimeMillis();
        int sequence = ExperientClientCommandTiming.recordSent(command, sentMillis);
        sendClientCommand(connection, command);
        commandSent = true;
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientClientCommand] sent sequence={}, command={}, sentMillis={}",
                sequence,
                command,
                sentMillis
        );
    }

    // Reconnects reuse the client process, so command state must reopen per connection.
    private static void refreshCommandStateForConnection(Minecraft minecraft, ClientPacketListener connection) {
        String connectionKey = minecraft.player.getUUID() + "@" + System.identityHashCode(connection);
        if (connectionKey.equals(activeConnectionKey)) {
            return;
        }
        activeConnectionKey = connectionKey;
        commandSent = false;
        delayTicksRemaining = -1;
    }

    private static void resetCommandState() {
        activeConnectionKey = "";
        commandSent = false;
        delayTicksRemaining = -1;
    }

    private static String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String trimmedCommand = command.trim();
        while (trimmedCommand.startsWith("/")) {
            trimmedCommand = trimmedCommand.substring(1).trim();
        }
        return trimmedCommand;
    }

    private static void sendClientCommand(ClientPacketListener connection, String command) {
        if (trySendCommand(connection, command, false)) {
            return;
        }
        trySendCommand(connection, command, true);
    }

    private static boolean trySendCommand(ClientPacketListener connection, String command, boolean withPreview) {
        try {
            Method method = withPreview
                    ? ClientPacketListener.class.getMethod("sendCommand", String.class, Component.class)
                    : ClientPacketListener.class.getMethod("sendCommand", String.class);
            if (withPreview) {
                method.invoke(connection, command, null);
            } else {
                method.invoke(connection, command);
            }
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (ReflectiveOperationException exception) {
            Bandwidthoptimizer.LOGGER.warn("[ExperientClientCommand] failed to send command={}", command, exception);
            return true;
        }
    }
}
