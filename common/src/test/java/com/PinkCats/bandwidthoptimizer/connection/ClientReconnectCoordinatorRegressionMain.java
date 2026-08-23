package com.PinkCats.bandwidthoptimizer.connection;

public final class ClientReconnectCoordinatorRegressionMain {

    private ClientReconnectCoordinatorRegressionMain() {}

    public static void main(String[] args) {
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
        System.out.println("Client reconnect coordinator regression passed");
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
