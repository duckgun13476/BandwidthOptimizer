package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;


@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExperientAutoConnectController {

    public static final String AUTO_CONNECT_ADDRESS_PROPERTY = "bandwidthoptimizer.experient.autoConnectAddress";
    public static final String AUTO_CONNECT_NAME_PROPERTY = "bandwidthoptimizer.experient.autoConnectName";
    public static final String AUTO_CONNECT_DELAY_TICKS_PROPERTY = "bandwidthoptimizer.experient.autoConnectDelayTicks";

    private static final int DEFAULT_CONNECT_DELAY_TICKS = 20;
    private static final int SERVER_READY_RETRY_TICKS = 20;
    private static final int SERVER_READY_CONNECT_TIMEOUT_MILLIS = 500;

    private static boolean attempted;
    private static boolean waitingForServerLogged;
    private static int delayTicksRemaining = -1;
    private static int serverReadyRetryTicksRemaining;

    private ExperientAutoConnectController() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || attempted || !ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        String autoConnectAddress = readAutoConnectAddress();
        if (autoConnectAddress == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.player != null || minecraft.getConnection() != null) {
            return;
        }

        Screen currentScreen = minecraft.screen;
        if (!(currentScreen instanceof TitleScreen)) {
            return;
        }

        if (delayTicksRemaining < 0) {
            delayTicksRemaining = readAutoConnectDelayTicks();
        }

        if (delayTicksRemaining > 0) {
            delayTicksRemaining--;
            return;
        }

        if (serverReadyRetryTicksRemaining > 0) {
            serverReadyRetryTicksRemaining--;
            return;
        }

        ConnectOnceResult connectResult = connectOnceWhenServerReady(minecraft, currentScreen, autoConnectAddress);
        if (connectResult == ConnectOnceResult.STARTED) {
            attempted = true;
            return;
        }

        if (connectResult == ConnectOnceResult.INVALID_ADDRESS) {
            attempted = true;
            return;
        }

        serverReadyRetryTicksRemaining = SERVER_READY_RETRY_TICKS;
    }


    private static ConnectOnceResult connectOnceWhenServerReady(Minecraft minecraft, Screen currentScreen, String autoConnectAddress) {
        if (!ServerAddress.isValidAddress(autoConnectAddress)) {
            Bandwidthoptimizer.LOGGER.warn("[ExperientAutoConnect] Invalid server address: {}", autoConnectAddress);
            return ConnectOnceResult.INVALID_ADDRESS;
        }

        ServerAddress serverAddress = ServerAddress.parseString(autoConnectAddress);
        if (!isServerAcceptingTcpConnections(serverAddress)) {
            if (!waitingForServerLogged) {
                Bandwidthoptimizer.LOGGER.info("[ExperientAutoConnect] Waiting for server readiness at {}", autoConnectAddress);
                waitingForServerLogged = true;
            }
            return ConnectOnceResult.WAITING_FOR_SERVER;
        }

        waitingForServerLogged = false;
        String serverName = readAutoConnectName();
        ServerData serverData = new ServerData(serverName, autoConnectAddress, false);
        Bandwidthoptimizer.LOGGER.info("[ExperientAutoConnect] Connecting once to {}", autoConnectAddress);
        ConnectScreen.startConnecting(currentScreen, minecraft, serverAddress, serverData, false);
        return ConnectOnceResult.STARTED;
    }

    private static boolean isServerAcceptingTcpConnections(ServerAddress serverAddress) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(serverAddress.getHost(), serverAddress.getPort()),
                    SERVER_READY_CONNECT_TIMEOUT_MILLIS
            );
            return true;
        } catch (IOException exception) {
            return false;
        }
    }


    private static String readAutoConnectAddress() {
        String value = System.getProperty(AUTO_CONNECT_ADDRESS_PROPERTY);
        if (value == null) {
            return null;
        }

        String trimmedValue = value.trim();
        return trimmedValue.isEmpty() ? null : trimmedValue;
    }


    private static String readAutoConnectName() {
        String value = System.getProperty(AUTO_CONNECT_NAME_PROPERTY);
        if (value == null) {
            return "BandwidthOptimizer Experient";
        }

        String trimmedValue = value.trim();
        return trimmedValue.isEmpty() ? "BandwidthOptimizer Experient" : trimmedValue;
    }

    private static int readAutoConnectDelayTicks() {
        String value = System.getProperty(AUTO_CONNECT_DELAY_TICKS_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_CONNECT_DELAY_TICKS;
        }

        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ExperientAutoConnect] Invalid delay ticks '{}', fallback to {}",
                    value,
                    DEFAULT_CONNECT_DELAY_TICKS
            );
            return DEFAULT_CONNECT_DELAY_TICKS;
        }
    }


    private enum ConnectOnceResult {
        INVALID_ADDRESS,
        WAITING_FOR_SERVER,
        STARTED
    }
}
