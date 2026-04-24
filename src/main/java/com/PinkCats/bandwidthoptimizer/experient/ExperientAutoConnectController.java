package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
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
    public static final String AUTO_CONNECT_MAX_ATTEMPTS_PROPERTY = "bandwidthoptimizer.experient.autoConnectMaxAttempts";
    public static final String AUTO_CONNECT_RETRY_DELAY_TICKS_PROPERTY = "bandwidthoptimizer.experient.autoConnectRetryDelayTicks";

    private static final int DEFAULT_CONNECT_DELAY_TICKS = 20;
    private static final int DEFAULT_MAX_ATTEMPTS = 4;
    private static final int DEFAULT_RETRY_DELAY_TICKS = 40;
    private static final int SERVER_READY_RETRY_TICKS = 20;
    private static final int SERVER_READY_CONNECT_TIMEOUT_MILLIS = 500;

    private static boolean waitingForServerLogged;
    private static boolean connectedOnce;
    private static int startedAttemptCount;
    private static int lastRetryScheduledAttemptCount;
    private static int delayTicksRemaining = -1;
    private static int retryDelayTicksRemaining;
    private static int serverReadyRetryTicksRemaining;
    private static AutoConnectPhase phase = AutoConnectPhase.IDLE;

    private ExperientAutoConnectController() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        String autoConnectAddress = readAutoConnectAddress();
        if (autoConnectAddress == null || connectedOnce) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.player != null) {
            connectedOnce = true;
            phase = AutoConnectPhase.CONNECTED;
            return;
        }

        Screen currentScreen = minecraft.screen;
        if (minecraft.getConnection() != null) {
            if (phase != AutoConnectPhase.CONNECTING) {
                Bandwidthoptimizer.LOGGER.info(
                        "[ExperientAutoConnect] Connection object is present, entering connecting phase. attemptsStarted={}",
                        startedAttemptCount
                );
            }
            phase = AutoConnectPhase.CONNECTING;
            return;
        }

        if (!isRetryEligibleScreen(currentScreen)) {
            return;
        }

        updatePhaseAfterScreenReturn(currentScreen);
        if (!consumeDelays()) {
            return;
        }

        int maxAttempts = readMaxAttempts();
        if (startedAttemptCount >= maxAttempts) {
            if (phase != AutoConnectPhase.GAVE_UP) {
                phase = AutoConnectPhase.GAVE_UP;
                Bandwidthoptimizer.LOGGER.warn(
                        "[ExperientAutoConnect] Reached max attempts {} for {}, stop retrying.",
                        maxAttempts,
                        autoConnectAddress
                );
            }
            return;
        }

        ConnectOnceResult connectResult = connectOnceWhenServerReady(minecraft, currentScreen, autoConnectAddress, maxAttempts);
        if (connectResult == ConnectOnceResult.STARTED) {
            return;
        }
        if (connectResult == ConnectOnceResult.INVALID_ADDRESS) {
            phase = AutoConnectPhase.GAVE_UP;
            return;
        }

        serverReadyRetryTicksRemaining = SERVER_READY_RETRY_TICKS;
    }


    private static void updatePhaseAfterScreenReturn(Screen currentScreen) {
        if (phase == AutoConnectPhase.CONNECTING && startedAttemptCount > lastRetryScheduledAttemptCount) {
            int nextAttemptCount = startedAttemptCount + 1;
            ExperientCaptureResetCoordinator.requestResetForRetry(nextAttemptCount);
            retryDelayTicksRemaining = readRetryDelayTicks();
            lastRetryScheduledAttemptCount = startedAttemptCount;
            phase = AutoConnectPhase.IDLE;
            Bandwidthoptimizer.LOGGER.warn(
                    "[ExperientAutoConnect] Attempt {} returned to {} before login completed, preparing attempt {} in {} ticks.",
                    startedAttemptCount,
                    currentScreen.getClass().getSimpleName(),
                    nextAttemptCount,
                    retryDelayTicksRemaining
            );
        }
    }


    private static boolean consumeDelays() {
        if (delayTicksRemaining < 0) {
            delayTicksRemaining = readAutoConnectDelayTicks();
        }
        if (delayTicksRemaining > 0) {
            delayTicksRemaining--;
            return false;
        }
        if (retryDelayTicksRemaining > 0) {
            retryDelayTicksRemaining--;
            return false;
        }
        if (serverReadyRetryTicksRemaining > 0) {
            serverReadyRetryTicksRemaining--;
            return false;
        }
        return true;
    }

    private static boolean isRetryEligibleScreen(Screen currentScreen) {
        return currentScreen instanceof TitleScreen || currentScreen instanceof DisconnectedScreen;
    }

    private static ConnectOnceResult connectOnceWhenServerReady(
            Minecraft minecraft,
            Screen currentScreen,
            String autoConnectAddress,
            int maxAttempts
    ) {
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
        startedAttemptCount++;
        phase = AutoConnectPhase.CONNECTING;
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientAutoConnect] Starting attempt {}/{} to {}",
                startedAttemptCount,
                maxAttempts,
                autoConnectAddress
        );
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
        return readNonNegativeIntProperty(
                AUTO_CONNECT_DELAY_TICKS_PROPERTY,
                DEFAULT_CONNECT_DELAY_TICKS,
                "connect delay ticks"
        );
    }

    private static int readMaxAttempts() {
        return Math.max(
                readNonNegativeIntProperty(
                        AUTO_CONNECT_MAX_ATTEMPTS_PROPERTY,
                        DEFAULT_MAX_ATTEMPTS,
                        "max attempts"
                ),
                1
        );
    }

    private static int readRetryDelayTicks() {
        return readNonNegativeIntProperty(
                AUTO_CONNECT_RETRY_DELAY_TICKS_PROPERTY,
                DEFAULT_RETRY_DELAY_TICKS,
                "retry delay ticks"
        );
    }

    private static int readNonNegativeIntProperty(String propertyName, int fallbackValue, String label) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            return fallbackValue;
        }

        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ExperientAutoConnect] Invalid {} '{}', fallback to {}",
                    label,
                    value,
                    fallbackValue
            );
            return fallbackValue;
        }
    }

    private enum ConnectOnceResult {
        INVALID_ADDRESS,
        WAITING_FOR_SERVER,
        STARTED
    }

    private enum AutoConnectPhase {
        IDLE,
        CONNECTING,
        CONNECTED,
        GAVE_UP
    }
}
