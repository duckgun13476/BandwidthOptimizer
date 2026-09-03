package com.PinkCats.bandwidthoptimizer.report.traffic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

public final class TrafficPeriodReportJson {
    public static final String MEDIA_TYPE = "application/vnd.bandwidthoptimizer.traffic-period+json;version=1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private TrafficPeriodReportJson() {
    }

    public static byte[] encode(TrafficPeriodReport report) {
        return GSON.toJson(encodeReport(report)).getBytes(StandardCharsets.UTF_8);
    }

    public static TrafficPeriodReport decode(String json) {
        return decodeReport(parseObject(json));
    }

    static byte[] encodeHourlyDay(LocalDate day, List<TrafficPeriodReport> hours) {
        JsonObject archive = new JsonObject();
        archive.addProperty("schemaVersion", 1);
        archive.addProperty("day", day.toString());
        JsonArray encodedHours = new JsonArray();
        for (TrafficPeriodReport hour : hours == null ? List.<TrafficPeriodReport>of() : hours) {
            encodedHours.add(encodeReport(hour));
        }
        archive.add("hours", encodedHours);
        return GSON.toJson(archive).getBytes(StandardCharsets.UTF_8);
    }

    static List<TrafficPeriodReport> decodeHourlyDay(String json) {
        JsonObject archive = parseObject(json);
        if (readInt(archive, "schemaVersion") != 1 || !archive.has("hours") || !archive.get("hours").isJsonArray()) {
            throw new IllegalArgumentException("Unsupported hourly day archive");
        }
        JsonArray encodedHours = archive.getAsJsonArray("hours");
        java.util.ArrayList<TrafficPeriodReport> hours = new java.util.ArrayList<>(encodedHours.size());
        for (JsonElement encodedHour : encodedHours) {
            if (!encodedHour.isJsonObject()) {
                throw new IllegalArgumentException("Invalid hourly traffic report");
            }
            hours.add(decodeReport(encodedHour.getAsJsonObject()));
        }
        return List.copyOf(hours);
    }

    private static JsonObject encodeReport(TrafficPeriodReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Missing traffic report");
        }
        JsonObject encoded = new JsonObject();
        encoded.addProperty("schemaVersion", report.schemaVersion());
        encoded.addProperty("reportId", report.reportId());
        encoded.addProperty("periodType", report.periodType());
        encoded.addProperty("periodStartMillis", report.periodStartMillis());
        encoded.addProperty("periodEndMillis", report.periodEndMillis());
        encoded.addProperty("generatedAtMillis", report.generatedAtMillis());
        encoded.addProperty("zoneId", report.zoneId());
        encoded.addProperty("complete", report.complete());
        encoded.addProperty("privacyLevel", report.privacyLevel());
        encoded.add("totals", encodeCounters(report.totals()));
        JsonArray players = new JsonArray();
        for (TrafficPeriodReport.PlayerTraffic player : report.players() == null
                ? List.<TrafficPeriodReport.PlayerTraffic>of()
                : report.players()) {
            JsonObject encodedPlayer = new JsonObject();
            encodedPlayer.addProperty("playerUuid", player.playerUuid());
            encodedPlayer.addProperty("playerName", player.playerName());
            encodedPlayer.add("traffic", encodeCounters(player.traffic()));
            players.add(encodedPlayer);
        }
        encoded.add("players", players);
        return encoded;
    }

    private static TrafficPeriodReport decodeReport(JsonObject encoded) {
        JsonArray players = readArray(encoded, "players");
        java.util.ArrayList<TrafficPeriodReport.PlayerTraffic> decodedPlayers = new java.util.ArrayList<>(players.size());
        for (JsonElement encodedPlayer : players) {
            if (!encodedPlayer.isJsonObject()) {
                throw new IllegalArgumentException("Invalid player traffic entry");
            }
            JsonObject player = encodedPlayer.getAsJsonObject();
            decodedPlayers.add(new TrafficPeriodReport.PlayerTraffic(
                    readString(player, "playerUuid"),
                    readString(player, "playerName"),
                    decodeCounters(readObject(player, "traffic"))
            ));
        }
        return new TrafficPeriodReport(
                readInt(encoded, "schemaVersion"),
                readString(encoded, "reportId"),
                readString(encoded, "periodType"),
                readLong(encoded, "periodStartMillis"),
                readLong(encoded, "periodEndMillis"),
                readLong(encoded, "generatedAtMillis"),
                readString(encoded, "zoneId"),
                readBoolean(encoded, "complete"),
                readString(encoded, "privacyLevel"),
                decodeCounters(readObject(encoded, "totals")),
                List.copyOf(decodedPlayers)
        );
    }

    private static JsonObject encodeCounters(TrafficPeriodReport.TrafficCounters counters) {
        TrafficPeriodReport.TrafficCounters safeCounters = counters == null
                ? TrafficPeriodReport.TrafficCounters.empty()
                : counters;
        JsonObject encoded = new JsonObject();
        encoded.addProperty("outboundRawPackets", safeCounters.outboundRawPackets());
        encoded.addProperty("outboundRawBytes", safeCounters.outboundRawBytes());
        encoded.addProperty("outboundVanillaEstimateBytes", safeCounters.outboundVanillaEstimateBytes());
        encoded.addProperty("outboundVanillaEstimateWireBytes", safeCounters.outboundVanillaEstimateWireBytes());
        encoded.addProperty("outboundTransportFrames", safeCounters.outboundTransportFrames());
        encoded.addProperty("outboundTransportBytes", safeCounters.outboundTransportBytes());
        encoded.addProperty("outboundBypassPackets", safeCounters.outboundBypassPackets());
        encoded.addProperty("outboundBypassBytes", safeCounters.outboundBypassBytes());
        encoded.addProperty("outboundWireBytes", safeCounters.outboundWireBytes());
        encoded.addProperty("inboundRawPackets", safeCounters.inboundRawPackets());
        encoded.addProperty("inboundRawBytes", safeCounters.inboundRawBytes());
        encoded.addProperty("inboundTransportFrames", safeCounters.inboundTransportFrames());
        encoded.addProperty("inboundTransportBytes", safeCounters.inboundTransportBytes());
        encoded.addProperty("inboundBypassPackets", safeCounters.inboundBypassPackets());
        encoded.addProperty("inboundBypassBytes", safeCounters.inboundBypassBytes());
        encoded.addProperty("inboundWireBytes", safeCounters.inboundWireBytes());
        return encoded;
    }

    private static TrafficPeriodReport.TrafficCounters decodeCounters(JsonObject encoded) {
        return new TrafficPeriodReport.TrafficCounters(
                readLong(encoded, "outboundRawPackets"),
                readLong(encoded, "outboundRawBytes"),
                readLong(encoded, "outboundVanillaEstimateBytes"),
                readLong(encoded, "outboundVanillaEstimateWireBytes"),
                readLong(encoded, "outboundTransportFrames"),
                readLong(encoded, "outboundTransportBytes"),
                readLong(encoded, "outboundBypassPackets"),
                readLong(encoded, "outboundBypassBytes"),
                readLong(encoded, "outboundWireBytes"),
                readLong(encoded, "inboundRawPackets"),
                readLong(encoded, "inboundRawBytes"),
                readLong(encoded, "inboundTransportFrames"),
                readLong(encoded, "inboundTransportBytes"),
                readLong(encoded, "inboundBypassPackets"),
                readLong(encoded, "inboundBypassBytes"),
                readLong(encoded, "inboundWireBytes")
        );
    }

    private static JsonObject parseObject(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Missing traffic report JSON");
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Invalid traffic report JSON");
        }
        return parsed.getAsJsonObject();
    }

    private static JsonObject readObject(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Missing traffic report object: " + name);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray readArray(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Missing traffic report array: " + name);
        }
        return value.getAsJsonArray();
    }

    private static String readString(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing traffic report string: " + name);
        }
        return value.getAsString();
    }

    private static int readInt(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing traffic report integer: " + name);
        }
        return value.getAsInt();
    }

    private static long readLong(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing traffic report long: " + name);
        }
        return value.getAsLong();
    }

    private static boolean readBoolean(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing traffic report boolean: " + name);
        }
        return value.getAsBoolean();
    }
}
