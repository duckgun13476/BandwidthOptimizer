package com.PinkCats.bandwidthoptimizer.gate;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.gate.recovery.IdleGateRecoveryRegistry;
import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IdleGateServerState {

    private static final long STATE_STALE_MILLIS = 150_000L;
    private static final long RESUME_DIRECT_MILLIS = 5_000L;
    private static final long JOIN_DIRECT_MILLIS = 30_000L;
    private static final ConcurrentHashMap<UUID, PlayerIdleState> PLAYER_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, UUID> CHANNEL_PLAYERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> RESUME_DIRECT_UNTIL = new ConcurrentHashMap<>();

    private IdleGateServerState() {}

    public static void accept(ServerPlayer player, IdleGateStatePayload payload) {
        if (player == null) {
            return;
        }
        IdleGateStatePayload safePayload = payload == null ? IdleGateStatePayload.active() : payload;
        Channel channel = ChunkPeerStateManager.findPlayerChannel(player);
        String channelId = channel == null ? "" : ChannelIdentity.longText(channel);
        if (!channelId.isBlank()) {
            UUID previousPlayerId = CHANNEL_PLAYERS.put(channelId, player.getUUID());
            if (!player.getUUID().equals(previousPlayerId)) {
                RESUME_DIRECT_UNTIL.put(channelId, System.currentTimeMillis() + JOIN_DIRECT_MILLIS);
            }
        }
        PlayerIdleState previousState = PLAYER_STATES.get(player.getUUID());
        if (previousState != null
                && previousState.mode() == IdleGateMode.BACKGROUND_IDLE
                && safePayload.mode() != IdleGateMode.BACKGROUND_IDLE
                && !channelId.isBlank()) {
            RESUME_DIRECT_UNTIL.put(channelId, System.currentTimeMillis() + RESUME_DIRECT_MILLIS);
        }
        // A stale state resolves to ACTIVE so a lost client report cannot suppress delivery.
        PLAYER_STATES.put(player.getUUID(), new PlayerIdleState(
                safePayload.mode(),
                safePayload.hudVisible(),
                safePayload.sequence(),
                System.currentTimeMillis()));
        if (previousState != null
                && previousState.mode() == IdleGateMode.BACKGROUND_IDLE
                && safePayload.mode() != IdleGateMode.BACKGROUND_IDLE) {
            IdleGateRecoveryRegistry.requestRestore(player);
        }
    }

    public static PlayerIdleState snapshot(ServerPlayer player) {
        if (player == null) {
            return PlayerIdleState.defaultActive();
        }
        PlayerIdleState state = PLAYER_STATES.get(player.getUUID());
        if (state == null || state.isStale(System.currentTimeMillis())) {
            return PlayerIdleState.defaultActive();
        }
        return state;
    }

    public static void onPlayerLoggedOut(ServerPlayer player) {
        if (player != null) {
            IdleGateRecoveryRegistry.discard(player);
            PLAYER_STATES.remove(player.getUUID());
            CHANNEL_PLAYERS.entrySet().removeIf(entry -> player.getUUID().equals(entry.getValue()));
        }
    }

    public static PlayerIdleState snapshot(Channel channel) {
        String channelId = channel == null ? "" : ChannelIdentity.longText(channel);
        if (channelId.isBlank()) {
            return PlayerIdleState.defaultActive();
        }
        UUID playerId = CHANNEL_PLAYERS.get(channelId);
        if (playerId == null) {
            return PlayerIdleState.defaultActive();
        }
        PlayerIdleState state = PLAYER_STATES.get(playerId);
        if (state == null || state.isStale(System.currentTimeMillis())) {
            return PlayerIdleState.defaultActive();
        }
        return state;
    }

    public static boolean isResumeDirectWindow(Channel channel) {
        String channelId = channel == null ? "" : ChannelIdentity.longText(channel);
        if (channelId.isBlank()) {
            return false;
        }
        Long untilMillis = RESUME_DIRECT_UNTIL.get(channelId);
        if (untilMillis == null) {
            return false;
        }
        long nowMillis = System.currentTimeMillis();
        if (nowMillis > untilMillis) {
            RESUME_DIRECT_UNTIL.remove(channelId, untilMillis);
            return false;
        }
        return true;
    }

    public record PlayerIdleState(
            IdleGateMode mode,
            boolean hudVisible,
            int sequence,
            long receivedAtMillis
    ) {
        private static PlayerIdleState defaultActive() {
            return new PlayerIdleState(IdleGateMode.ACTIVE, true, 0, System.currentTimeMillis());
        }

        public boolean isStale(long nowMillis) {
            return nowMillis - receivedAtMillis > STATE_STALE_MILLIS;
        }
    }
}
