package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Formats the stable task result protocol used by console and RCON callers. */
final class LatencyProbeTaskJson {
    private LatencyProbeTaskJson() {}

    static JsonObject format(LatencyProbeTasks.Task task, int index) {
        JsonObject json = new JsonObject();
        json.addProperty("id", task.id);
        if (index < 0) {
            json.addProperty("status", task.finishedAt == 0 ? "running" : "complete");
            json.addProperty("count", task.entries.size());
            json.addProperty("completed", task.entries.stream()
                    .filter(entry -> !entry.status.equals("pending") && !entry.status.equals("running"))
                    .count());
            return json;
        }

        LatencyProbeTasks.Entry entry = task.entries.get(index);
        json.addProperty("index", index);
        json.addProperty("player", entry.name);
        json.addProperty("status", entry.status);
        if (entry.result != null) {
            addResult(json, entry.result);
        }
        return json;
    }

    private static void addResult(JsonObject json, LatencyProbeWindow.Result result) {
        json.addProperty("sent", result.sent());
        json.addProperty("replies", result.replies());
        json.addProperty("timeouts", result.timedOut());
        json.addProperty("pending", result.sent() - result.replies() - result.timedOut());
        json.addProperty("duration_ms", result.duration() / 1e6);
        if (result.replies() > 0) {
            json.addProperty("average_ms", result.average() / 1e6);
            json.addProperty("min_ms", result.minimum() / 1e6);
            json.addProperty("max_ms", result.maximum() / 1e6);
        }
        JsonArray spikes = new JsonArray();
        LatencyProbeWindow.highestSpikes(result.spikes()).forEach(spike -> {
            JsonObject item = new JsonObject();
            item.addProperty("from_ms", spike.from() / 1e6);
            item.addProperty("to_ms", spike.to() / 1e6);
            item.addProperty("peak_ms", spike.peak() / 1e6);
            item.addProperty("samples", spike.samples());
            spikes.add(item);
        });
        json.add("spikes", spikes);
        json.addProperty("spike_count", result.spikes().size());
    }
}
