package com.PinkCats.bandwidthoptimizer.connection;

public final class ClientReconnectCoordinatorRegressionMain {

    private ClientReconnectCoordinatorRegressionMain() {}

    public static void main(String[] args) {
        verifyOrdinaryRetryAndPolicyBoundaries();
        verifyBudgetSurvivesShortPlaySessions();
        verifyBudgetSurvivesReconnectBeforeClientTick();
        verifyStablePlayResetsBudget();
        verifyLocalDisconnectCancelsReconnect();
        verifyTargetSwitchDoesNotInheritBudget();
        ClientReconnectCoordinator.resetForTesting();
        System.out.println("Client reconnect coordinator regression passed");
    }

    private static void verifyOrdinaryRetryAndPolicyBoundaries() {
        FakePlatform platform = new FakePlatform();
        ClientReconnectCoordinator.install(platform);

        platform.active = true;
        platform.target = new ClientReconnectCoordinator.Target("test", "127.0.0.1:25565");
        ClientReconnectCoordinator.onClientTickAt(1_000L);

        platform.active = false;
        platform.disconnectedScreen = true;
        ClientReconnectCoordinator.onReconnectCandidate(decision(ConnectionDisconnectClassifier.Category.CONNECTION_RESET));
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE - 10_000L);
        check(platform.attempts == 1, "first reconnect attempt was not started");

        platform.connecting = true;
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE - 9_000L);
        check(platform.attempts == 1, "connecting state started a duplicate attempt");

        platform.connecting = false;
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE - 8_000L);
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE - 6_000L);
        check(platform.attempts == 2, "second reconnect attempt was not started");

        platform.active = true;
        platform.disconnectedScreen = false;
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE - 5_000L);

        platform.active = false;
        platform.disconnectedScreen = true;
        ClientReconnectCoordinator.onReconnectCandidate(decision(ConnectionDisconnectClassifier.Category.PROTOCOL_FAILURE));
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE - 1_000L);
        check(platform.attempts == 2, "protocol failure incorrectly triggered reconnect");

        platform.startSucceeded = false;
        ClientReconnectCoordinator.onReconnectCandidate(decision(ConnectionDisconnectClassifier.Category.READ_TIMEOUT));
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE - 500L);
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE);
        check(platform.attempts == 3, "failed platform start was retried after cancellation");

        ClientReconnectCoordinator.resetForTesting();
    }

    private static void verifyBudgetSurvivesShortPlaySessions() {
        FakePlatform platform = connectedPlatform("test", "127.0.0.1:25565", 1_000L);

        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.CONNECTION_RESET, 2_000L);
        markPlayActive(platform, 3_000L);
        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.READ_TIMEOUT, 4_000L);
        markPlayActive(platform, 5_000L);
        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.CONNECTION_RESET, 6_000L);
        markPlayActive(platform, 7_000L);

        platform.active = false;
        platform.disconnectedScreen = true;
        ClientReconnectCoordinator.onReconnectCandidate(decision(ConnectionDisconnectClassifier.Category.READ_TIMEOUT));
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE);
        check(platform.attempts == ClientReconnectCoordinator.MAX_ATTEMPTS,
                "short PLAY sessions reset the reconnect attempt budget");
        ClientReconnectCoordinator.onReconnectCandidateAt(
                decision(ConnectionDisconnectClassifier.Category.CONNECTION_RESET),
                9_000L
        );
        ClientReconnectCoordinator.onClientTickAt(9_500L);
        check(platform.attempts == ClientReconnectCoordinator.MAX_ATTEMPTS,
                "an exhausted reconnect episode was reopened by a repeated close callback");

        ClientReconnectCoordinator.resetForTesting();
    }

    private static void verifyBudgetSurvivesReconnectBeforeClientTick() {
        FakePlatform platform = connectedPlatform("test", "127.0.0.1:25565", 1_000L);
        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.CONNECTION_RESET, 2_000L);

        ClientReconnectCoordinator.onReconnectCandidateAt(
                decision(ConnectionDisconnectClassifier.Category.READ_TIMEOUT),
                3_000L
        );
        ClientReconnectCoordinator.onClientTickAt(3_500L);
        check(platform.attempts == 2,
                "disconnect before the first PLAY client tick reset the reconnect budget");

        ClientReconnectCoordinator.resetForTesting();
    }

    private static void verifyStablePlayResetsBudget() {
        FakePlatform platform = connectedPlatform("test", "127.0.0.1:25565", 1_000L);

        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.CONNECTION_RESET, 2_000L);
        markPlayActive(platform, 3_000L);
        ClientReconnectCoordinator.onClientTickAt(3_000L + ClientReconnectCoordinator.STABLE_PLAY_RESET_MILLIS);

        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.READ_TIMEOUT, 40_000L);
        check(platform.attempts == 2, "stable PLAY did not reset the reconnect budget");

        ClientReconnectCoordinator.resetForTesting();
    }

    private static void verifyLocalDisconnectCancelsReconnect() {
        FakePlatform platform = connectedPlatform("test", "127.0.0.1:25565", 1_000L);
        platform.active = false;
        platform.disconnectedScreen = true;
        ClientReconnectCoordinator.onReconnectCandidate(decision(ConnectionDisconnectClassifier.Category.CONNECTION_RESET));
        ClientReconnectCoordinator.onLocalDisconnect();
        ClientReconnectCoordinator.onClientTickAt(Long.MAX_VALUE);
        check(platform.attempts == 0, "local disconnect retained an automatic reconnect");

        ClientReconnectCoordinator.resetForTesting();
    }

    private static void verifyTargetSwitchDoesNotInheritBudget() {
        FakePlatform platform = connectedPlatform("first", "127.0.0.1:25565", 1_000L);
        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.CONNECTION_RESET, 2_000L);
        markPlayActive(platform, 3_000L);
        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.READ_TIMEOUT, 4_000L);
        markPlayActive(platform, 5_000L);
        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.CONNECTION_RESET, 6_000L);

        platform.target = new ClientReconnectCoordinator.Target("second", "127.0.0.1:25566");
        markPlayActive(platform, 7_000L);

        reconnectAfter(platform, ConnectionDisconnectClassifier.Category.READ_TIMEOUT, 8_000L);
        check(platform.attempts == 4, "new target inherited the previous target reconnect budget");

        ClientReconnectCoordinator.resetForTesting();
    }

    private static FakePlatform connectedPlatform(String name, String address, long nowMillis) {
        FakePlatform platform = new FakePlatform();
        ClientReconnectCoordinator.install(platform);
        platform.active = true;
        platform.target = new ClientReconnectCoordinator.Target(name, address);
        ClientReconnectCoordinator.onClientTickAt(nowMillis);
        return platform;
    }

    private static void reconnectAfter(
            FakePlatform platform,
            ConnectionDisconnectClassifier.Category category,
            long nowMillis
    ) {
        platform.active = false;
        platform.disconnectedScreen = true;
        ClientReconnectCoordinator.onReconnectCandidateAt(decision(category), nowMillis);
        ClientReconnectCoordinator.onClientTickAt(nowMillis + ClientReconnectCoordinator.INITIAL_DELAY_MILLIS);
    }

    private static void markPlayActive(FakePlatform platform, long nowMillis) {
        platform.active = true;
        platform.disconnectedScreen = false;
        ClientReconnectCoordinator.onClientTickAt(nowMillis);
    }

    private static ConnectionDisconnectClassifier.Decision decision(
            ConnectionDisconnectClassifier.Category category
    ) {
        return new ConnectionDisconnectClassifier.Decision(
                category,
                category == ConnectionDisconnectClassifier.Category.PROTOCOL_FAILURE
                        ? ConnectionDisconnectClassifier.RecoveryPolicy.DO_NOT_AUTO_RECONNECT
                        : ConnectionDisconnectClassifier.RecoveryPolicy.RECONNECT_CANDIDATE,
                "test",
                "",
                "",
                "",
                0L
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class FakePlatform implements ClientReconnectCoordinator.Platform {
        private ClientReconnectCoordinator.Target target;
        private boolean active;
        private boolean connecting;
        private boolean disconnectedScreen;
        private boolean startSucceeded = true;
        private int attempts;

        @Override
        public ClientReconnectCoordinator.Target currentTarget() {
            return target;
        }

        @Override
        public boolean hasActivePlayConnection() {
            return active;
        }

        @Override
        public boolean isConnectInProgress() {
            return connecting;
        }

        @Override
        public boolean isDisconnectedScreenReady() {
            return disconnectedScreen;
        }

        @Override
        public boolean startConnection(ClientReconnectCoordinator.Target target) {
            attempts++;
            return startSucceeded;
        }
    }
}
