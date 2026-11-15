package com.PinkCats.bandwidthoptimizer.networkprobe.message;

import com.PinkCats.bandwidthoptimizer.networkprobe.NetworkProbeService;
import net.minecraft.network.chat.Component;

/** Keep translation components intact for players; consoles receive explicit English text. */
public final class NetworkProbeCommandMessages {
    private NetworkProbeCommandMessages() {}
    public static Component text(String name,boolean player) {
        String english=switch(name) {
            case "notice" -> "Network testing adds traffic to this game connection and may temporarily increase latency. Results: BO diagnostics/network/probes.jsonl.";
            case "unavailable" -> "Network test unavailable: unsupported peer or closed connection.";
            case "cancel_requested" -> "Network test cancellation requested.";
            case "started" -> "Network test started.";
            case "completed" -> "Network test completed.";
            case "invalid_budget" -> "Network test rejected: invalid traffic budget or sending rate.";
            case "connection_closed" -> "Network test stopped: the connection is closed.";
            case "already_running" -> "A network test is already running on this connection.";
            case "busy_or_cooldown" -> "Network testing is busy or cooling down. Try again later.";
            case "cancelled" -> "Network test cancelled.";
            case "write_failed" -> "Network test failed: socket write failed.";
            case "local_event_loop_stall" -> "Network test stopped: local network processing stalled.";
            case "timeout" -> "Network test timed out; this alone does not prove packet loss.";
            default -> "Network test failed. See the diagnostic report for details.";
        };
        return player?Component.translatable("command.bandwidthoptimizer.network."+name):Component.literal(english);
    }
    public static Component feedback(NetworkProbeService.Feedback feedback,boolean player) {
        String name=switch(feedback.status()) {
            case "started","completed","invalid_budget","already_running","busy_or_cooldown","write_failed","local_event_loop_stall" -> feedback.status();
            case "connection_closed","connection_closed_cause_unknown" -> "connection_closed";
            case "operator_cancelled","peer_cancelled" -> "cancelled";
            case "test_deadline_not_packet_loss" -> "timeout";
            default -> "failed";
        };
        Component message=text(name,player);
        if(feedback.testId()==null) return message;
        return player?Component.translatable("command.bandwidthoptimizer.network.with_id",message,feedback.testId().toString()):
                Component.literal(message.getString()+" (test ID: "+feedback.testId()+")");
    }
}
