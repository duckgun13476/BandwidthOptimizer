package com.PinkCats.bandwidthoptimizer.networkprobe.message;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Players resolve translations locally; console and RCON receive English text. */
public final class LatencyProbeCommandMessages {
    private LatencyProbeCommandMessages() {}

    public static Component text(String name, boolean player, Object... args) {
        if (player) return Component.translatable("command.bandwidthoptimizer.ping." + name, args);
        String english = switch (name) {
            case "player_only" -> "Specify a player or all.";
            case "unavailable" -> "Latency sampling is unavailable on this connection.";
            case "busy" -> "A latency test is already running or the server is busy.";
            case "started" -> "Latency sampling started: %s seconds, one sample every 500 ms.";
            case "list" -> "Recent RTT samples (ms): %s";
            case "summary" -> "Latency sampling: %s s; average %s ms, minimum %s ms, maximum %s ms; replies %s/%s, timed out %s.";
            case "spike" -> "High latency at %s–%s s: peak %s ms (%s samples).";
            case "spike_limit" -> "Showing the 8 highest-latency intervals out of %s.";
            case "no_spikes" -> "No latency samples exceeded 200 ms.";
            case "incomplete" -> "Sampling stopped: connection closed. Results include completed observations only.";
            case "no_reply" -> "No RTT replies: sent %s, timed out %s, still pending %s.";
            case "no_players" -> "There are no online players to test.";
            case "queue_started" -> "Testing %s online players, up to %s at a time.";
            case "queue_finished" -> "Latency tests finished for %s online players.";
            default -> "Latency sampling failed.";
        };
        return Component.literal(String.format(Locale.ROOT, english, args));
    }
}
