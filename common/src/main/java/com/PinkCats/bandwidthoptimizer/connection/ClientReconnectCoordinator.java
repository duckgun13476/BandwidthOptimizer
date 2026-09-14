package com.PinkCats.bandwidthoptimizer.connection;

import java.util.Objects;

public final class ClientReconnectCoordinator {

    static final long INITIAL_DELAY_MILLIS = 500L;
    static final long STABLE_PLAY_RESET_MILLIS = 30_000L;
    static final int MAX_ATTEMPTS = 3;
    private static volatile Platform platform;
    private static Target lastTarget;
    private static PendingReconnect pending;
    private static ReconnectEpisode episode;

    private ClientReconnectCoordinator() {}

    public static synchronized void install(Platform clientPlatform) {
        platform = Objects.requireNonNull(clientPlatform, "clientPlatform");
        ConnectionDisconnectClassifier.setReconnectCandidateListener(
                ClientReconnectCoordinator::onReconnectCandidate
        );
    }

    public static void onClientTick() {
        onClientTickAt(System.currentTimeMillis());
    }

    static synchronized void onClientTickAt(long nowMillis) {
        Platform currentPlatform = platform;
        if (currentPlatform == null) {
            return;
        }

        Target currentTarget = currentPlatform.currentTarget();
        if (currentTarget != null && currentPlatform.hasActivePlayConnection()) {
            lastTarget = currentTarget;
            if (pending != null) {
                if (pending.target.equals(currentTarget) && pending.attempts > 0) {
                    episode = new ReconnectEpisode(currentTarget, pending.attempts, nowMillis);
                } else {
                    episode = null;
                }
                pending = null;
            }
            if (episode != null) {
                if (!episode.target.equals(currentTarget)
                        || nowMillis - episode.stablePlaySinceMillis >= STABLE_PLAY_RESET_MILLIS) {
                    episode = null;
                }
            }
            return;
        }

        PendingReconnect current = pending;
        if (current == null || currentPlatform.isConnectInProgress()) {
            return;
        }

        if (current.awaitingAttemptResult) {
            if (!currentPlatform.isDisconnectedScreenReady()) {
                return;
            }
            if (current.attempts >= MAX_ATTEMPTS) {
                pending = null;
                return;
            }
            current.awaitingAttemptResult = false;
            current.nextAttemptAtMillis = nowMillis + retryDelayMillis(current.attempts);
        }

        if (nowMillis < current.nextAttemptAtMillis || !currentPlatform.isDisconnectedScreenReady()) {
            return;
        }
        if (current.attempts >= MAX_ATTEMPTS) {
            pending = null;
            return;
        }

        current.attempts++;
        current.awaitingAttemptResult = true;
        if (!currentPlatform.startConnection(current.target)) {
            pending = null;
        }
    }

    static synchronized void onReconnectCandidate(ConnectionDisconnectClassifier.Decision decision) {
        onReconnectCandidateAt(decision, System.currentTimeMillis());
    }

    static synchronized void onReconnectCandidateAt(
            ConnectionDisconnectClassifier.Decision decision,
            long nowMillis
    ) {
        if (!isEligible(decision) || lastTarget == null) {
            return;
        }
        int attempts = 0;
        if (pending != null && pending.target.equals(lastTarget)) {
            attempts = pending.attempts;
        } else if (episode != null && episode.target.equals(lastTarget)) {
            attempts = episode.attempts;
        }
        pending = new PendingReconnect(
                lastTarget,
                attempts,
                nowMillis + INITIAL_DELAY_MILLIS
        );
    }

    public static synchronized void onLocalDisconnect() {
        pending = null;
        episode = null;
    }

    static boolean isEligible(ConnectionDisconnectClassifier.Decision decision) {
        return decision != null
                && decision.recoveryPolicy() == ConnectionDisconnectClassifier.RecoveryPolicy.RECONNECT_CANDIDATE
                && (decision.category() == ConnectionDisconnectClassifier.Category.CONNECTION_RESET
                || decision.category() == ConnectionDisconnectClassifier.Category.READ_TIMEOUT);
    }

    static long retryDelayMillis(int completedAttempts) {
        return switch (completedAttempts) {
            case 1 -> 1_000L;
            default -> 2_000L;
        };
    }

    static synchronized void resetForTesting() {
        platform = null;
        lastTarget = null;
        pending = null;
        episode = null;
        ConnectionDisconnectClassifier.setReconnectCandidateListener(null);
    }

    public record Target(String name, String address) {
        public Target {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(address, "address");
        }
    }

    public interface Platform {
        Target currentTarget();

        boolean hasActivePlayConnection();

        boolean isConnectInProgress();

        boolean isDisconnectedScreenReady();

        boolean startConnection(Target target);
    }

    private static final class PendingReconnect {
        private final Target target;
        private int attempts;
        private long nextAttemptAtMillis;
        private boolean awaitingAttemptResult;

        private PendingReconnect(
                Target target,
                int attempts,
                long nextAttemptAtMillis
        ) {
            this.target = target;
            this.attempts = attempts;
            this.nextAttemptAtMillis = nextAttemptAtMillis;
        }
    }

    private record ReconnectEpisode(Target target, int attempts, long stablePlaySinceMillis) {}
}
