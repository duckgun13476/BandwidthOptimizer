package com.PinkCats.bandwidthoptimizer.experimental.runall;

import net.neoforged.neoforge.client.event.ClientTickEvent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ClientWindowCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT)
public final class ExperientAutoConnectController {
    private static final int SERVER_READY_RETRY_TICKS = 20;
    private static final int SERVER_READY_CONNECT_TIMEOUT_MILLIS = 500;

    private static boolean waitingForServerLogged;
    private static boolean connectedOnce;
    private static int startedAttemptCount;
    private static int lastRetryScheduledAttemptCount;
    private static int delayTicksRemaining = -1;
    private static int retryDelayTicksRemaining;
    private static int rejoinDelayTicksRemaining;
    private static int serverReadyRetryTicksRemaining;
    private static AutoConnectPhase phase = AutoConnectPhase.IDLE;
    private static boolean shutdownAfterDisconnectLogged;
    private static int connectedTicks;
    private static int completedRejoinCycles;

    private ExperientAutoConnectController() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        String autoConnectAddress = readAutoConnectAddress();
        if (autoConnectAddress == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (maybeStopClientAfterCompletedConnection(minecraft)) {
            return;
        }

        if (minecraft.level != null || minecraft.player != null) {
            connectedOnce = true;
            phase = AutoConnectPhase.CONNECTED;
            if (maybeDisconnectForRejoinCycle(minecraft)) {
                return;
            }
            if (maybeStopClientAfterFinalConnectedSample(minecraft)) {
                return;
            }
            return;
        }

        if (connectedOnce) {
            return;
        }

        Screen currentScreen = ClientWindowCompat.screen(minecraft);
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

        if (!isRetryEligibleScreen(minecraft, currentScreen)) {
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

    private static boolean maybeStopClientAfterCompletedConnection(Minecraft minecraft) {
        if (!connectedOnce || minecraft == null || !(ClientWindowCompat.screen(minecraft) instanceof DisconnectedScreen)) {
            return false;
        }
        if (completedRejoinCycles < readRejoinCycles()) {
            connectedOnce = false;
            phase = AutoConnectPhase.IDLE;
            connectedTicks = 0;
            rejoinDelayTicksRemaining = readRejoinDelayTicks();
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientAutoConnect][RejoinTest] Connection returned to disconnect screen, preparing rejoin {}/{} in {} ticks.",
                    completedRejoinCycles + 1,
                    readRejoinCycles(),
                    rejoinDelayTicksRemaining
            );
            return true;
        }

        if (!shutdownAfterDisconnectLogged) {
            shutdownAfterDisconnectLogged = true;
            Bandwidthoptimizer.LOGGER.info("[ExperientAutoConnect] Completed connection ended, stopping client process.");
        }
        minecraft.stop();
        return true;
    }

    private static boolean maybeDisconnectForRejoinCycle(Minecraft minecraft) {
        int targetRejoinCycles = readRejoinCycles();
        if (targetRejoinCycles <= 0 || completedRejoinCycles >= targetRejoinCycles) {
            return false;
        }

        connectedTicks++;
        int requiredConnectedTicks = readRejoinConnectedTicks();
        if (connectedTicks < requiredConnectedTicks) {
            return false;
        }

        completedRejoinCycles++;
        connectedOnce = false;
        connectedTicks = 0;
        phase = AutoConnectPhase.IDLE;
        rejoinDelayTicksRemaining = readRejoinDelayTicks();
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientAutoConnect][RejoinTest] Disconnecting to multiplayer screen for rejoin cycle {}/{}; reconnectDelayTicks={}.",
                completedRejoinCycles,
                targetRejoinCycles,
                rejoinDelayTicksRemaining
        );
        minecraft.level.disconnect(Component.literal("BandwidthOptimizer rejoin regression"));
        minecraft.disconnect(new JoinMultiplayerScreen(new TitleScreen()), false);
        return true;
    }

    private static boolean maybeStopClientAfterFinalConnectedSample(Minecraft minecraft) {
        if (!shouldUseRunAllMarkerExit() || completedRejoinCycles < readRejoinCycles()) {
            return false;
        }

        connectedTicks++;
        int requiredConnectedTicks = readRejoinConnectedTicks();
        if (connectedTicks < requiredConnectedTicks) {
            return false;
        }

        if (!shutdownAfterDisconnectLogged) {
            shutdownAfterDisconnectLogged = true;
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientAutoConnect] Final connected sample reached after {} ticks, stopping client process.",
                    connectedTicks
            );
        }
        minecraft.stop();
        return true;
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
        if (rejoinDelayTicksRemaining > 0) {
            rejoinDelayTicksRemaining--;
            return false;
        }
        if (serverReadyRetryTicksRemaining > 0) {
            serverReadyRetryTicksRemaining--;
            return false;
        }
        return true;
    }

    // Wait for resource overlays before auto-connect.
    private static boolean isRetryEligibleScreen(Minecraft minecraft, Screen currentScreen) {
        if (ClientWindowCompat.hasOverlay(minecraft)) {
            return false;
        }

        if (currentScreen instanceof ConnectScreen) {
            return false;
        }
        return true;
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
        ServerData serverData = new ServerData(serverName, autoConnectAddress, ServerData.Type.OTHER);
        // Use a fallback parent when no screen is active.
        Screen parentScreen = currentScreen == null ? new TitleScreen() : currentScreen;
        startedAttemptCount++;
        phase = AutoConnectPhase.CONNECTING;
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientAutoConnect] Starting attempt {}/{} to {}",
                startedAttemptCount,
                maxAttempts,
                autoConnectAddress
        );
        ConnectScreen.startConnecting(parentScreen, minecraft, serverAddress, serverData, false, null);
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
        String value = System.getProperty(Config.RuntimeProperty.Experient.AUTO_CONNECT_ADDRESS);
        if (value == null) {
            return null;
        }

        String trimmedValue = value.trim();
        return trimmedValue.isEmpty() ? null : trimmedValue;
    }

    private static String readAutoConnectName() {
        String value = System.getProperty(Config.RuntimeProperty.Experient.AUTO_CONNECT_NAME);
        if (value == null) {
            return Config.RuntimeProperty.Experient.DEFAULT_AUTO_CONNECT_NAME;
        }

        String trimmedValue = value.trim();
        return trimmedValue.isEmpty() ? Config.RuntimeProperty.Experient.DEFAULT_AUTO_CONNECT_NAME : trimmedValue;
    }


    private static int readAutoConnectDelayTicks() {
        return readNonNegativeIntProperty(
                Config.RuntimeProperty.Experient.AUTO_CONNECT_DELAY_TICKS,
                Config.RuntimeProperty.Experient.DEFAULT_AUTO_CONNECT_DELAY_TICKS,
                "connect delay ticks"
        );
    }

    private static int readMaxAttempts() {
        return Math.max(
                readNonNegativeIntProperty(
                        Config.RuntimeProperty.Experient.AUTO_CONNECT_MAX_ATTEMPTS,
                        Config.RuntimeProperty.Experient.DEFAULT_AUTO_CONNECT_MAX_ATTEMPTS,
                        "max attempts"
                ),
                1
        );
    }

    private static int readRetryDelayTicks() {
        return readNonNegativeIntProperty(
                Config.RuntimeProperty.Experient.AUTO_CONNECT_RETRY_DELAY_TICKS,
                Config.RuntimeProperty.Experient.DEFAULT_AUTO_CONNECT_RETRY_DELAY_TICKS,
                "retry delay ticks"
        );
    }

    private static int readRejoinCycles() {
        return readNonNegativeIntProperty(
                Config.RuntimeProperty.Experient.AUTO_CONNECT_REJOIN_CYCLES,
                Config.RuntimeProperty.Experient.DEFAULT_AUTO_CONNECT_REJOIN_CYCLES,
                "rejoin cycles"
        );
    }

    private static int readRejoinConnectedTicks() {
        return Math.max(
                readNonNegativeIntProperty(
                        Config.RuntimeProperty.Experient.AUTO_CONNECT_REJOIN_CONNECTED_TICKS,
                        Config.RuntimeProperty.Experient.DEFAULT_AUTO_CONNECT_REJOIN_CONNECTED_TICKS,
                        "rejoin connected ticks"
                ),
                1
        );
    }

    private static int readRejoinDelayTicks() {
        return readNonNegativeIntProperty(
                Config.RuntimeProperty.Experient.AUTO_CONNECT_REJOIN_DELAY_TICKS,
                Config.RuntimeProperty.Experient.DEFAULT_AUTO_CONNECT_REJOIN_DELAY_TICKS,
                "rejoin delay ticks"
        );
    }

    private static boolean shouldUseRunAllMarkerExit() {
        return Boolean.parseBoolean(System.getProperty(Config.RuntimeProperty.Experient.RUN_ALL_MARKER_EXIT, "false"));
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
