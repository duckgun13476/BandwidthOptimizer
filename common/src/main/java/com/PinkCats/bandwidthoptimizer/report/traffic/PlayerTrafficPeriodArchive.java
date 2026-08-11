package com.PinkCats.bandwidthoptimizer.report.traffic;

import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlayerTrafficPeriodArchive {
    private static final Object LOCK = new Object();
    private static final long SAMPLE_INTERVAL_MILLIS = 5_000L;
    private static final long CHECKPOINT_INTERVAL_MILLIS = 15L * 60L * 1_000L;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Map<String, Baseline> BASELINES = new HashMap<>();
    private static final Map<String, TrafficPeriodReportStore.MutablePlayer> CURRENT_PLAYERS = new HashMap<>();
    private static long currentHourStartMillis = Long.MIN_VALUE;
    private static long currentHourEndMillis = Long.MIN_VALUE;
    private static volatile long lastSampleMillis;
    private static long lastCheckpointMillis;

    private PlayerTrafficPeriodArchive() {
    }

    public static void onServerTick(long nowMillis) {
        if (nowMillis - lastSampleMillis < SAMPLE_INTERVAL_MILLIS) {
            return;
        }
        List<ChannelBandwidthStats.Snapshot> snapshots = ServerBandwidthStatsRegistry.snapshotChannels();
        synchronized (LOCK) {
            ensureInitialized(nowMillis);
            consumeAll(snapshots);
            rolloverIfNeeded(nowMillis);
            lastSampleMillis = nowMillis;
            if (nowMillis - lastCheckpointMillis >= CHECKPOINT_INTERVAL_MILLIS) {
                TrafficPeriodReportStore.saveAsync(buildCurrent(false, nowMillis));
                lastCheckpointMillis = nowMillis;
            }
        }
    }

    public static void onPlayerBound(ChannelBandwidthStats.Snapshot snapshot) {
        if (snapshot == null || snapshot.channelId() == null || snapshot.playerId() == null) {
            return;
        }
        synchronized (LOCK) {
            ensureInitialized(System.currentTimeMillis());
            BASELINES.put(snapshot.channelId(), new Baseline(snapshot.playerId(), snapshot.playerName(), snapshot));
        }
    }

    public static void onPlayerUnbinding(ChannelBandwidthStats.Snapshot snapshot) {
        if (snapshot == null || snapshot.channelId() == null || snapshot.playerId() == null) {
            return;
        }
        synchronized (LOCK) {
            ensureInitialized(System.currentTimeMillis());
            consume(snapshot);
            BASELINES.remove(snapshot.channelId());
        }
    }

    public static void onChannelClosed(ChannelBandwidthStats.Snapshot snapshot) {
        onPlayerUnbinding(snapshot);
    }

    public static void beforeCountersReset(List<ChannelBandwidthStats.Snapshot> snapshots) {
        synchronized (LOCK) {
            ensureInitialized(System.currentTimeMillis());
            consumeAll(snapshots);
        }
    }

    public static void afterCountersReset(List<ChannelBandwidthStats.Snapshot> snapshots) {
        synchronized (LOCK) {
            BASELINES.clear();
            for (ChannelBandwidthStats.Snapshot snapshot : snapshots) {
                if (snapshot.playerId() != null && snapshot.channelId() != null) {
                    BASELINES.put(snapshot.channelId(), new Baseline(snapshot.playerId(), snapshot.playerName(), snapshot));
                }
            }
        }
    }

    public static TrafficPeriodReport snapshotCurrent() {
        long now = System.currentTimeMillis();
        List<ChannelBandwidthStats.Snapshot> snapshots = ServerBandwidthStatsRegistry.snapshotChannels();
        synchronized (LOCK) {
            ensureInitialized(now);
            consumeAll(snapshots);
            rolloverIfNeeded(now);
            return buildCurrent(false, now);
        }
    }

    public static void flushOnServerStopping() {
        long now = System.currentTimeMillis();
        List<ChannelBandwidthStats.Snapshot> snapshots = ServerBandwidthStatsRegistry.snapshotChannels();
        TrafficPeriodReport report;
        synchronized (LOCK) {
            ensureInitialized(now);
            consumeAll(snapshots);
            rolloverIfNeeded(now);
            report = buildCurrent(false, now);
        }
        TrafficPeriodReportStore.saveBlocking(report);
    }

    private static void ensureInitialized(long nowMillis) {
        if (currentHourStartMillis != Long.MIN_VALUE) {
            return;
        }
        setCurrentHour(nowMillis);
        TrafficPeriodReport existing = TrafficPeriodReportStore.loadHour(currentHourStartMillis, ZONE);
        if (existing != null) {
            for (TrafficPeriodReport.PlayerTraffic player : existing.players()) {
                CURRENT_PLAYERS.computeIfAbsent(
                        player.playerUuid(),
                        ignored -> new TrafficPeriodReportStore.MutablePlayer(player.playerUuid(), player.playerName())
                ).add(player.playerName(), player.traffic());
            }
        }
        lastSampleMillis = nowMillis;
        lastCheckpointMillis = nowMillis;
    }

    private static void rolloverIfNeeded(long nowMillis) {
        if (nowMillis < currentHourEndMillis) {
            return;
        }
        TrafficPeriodReportStore.saveAsync(buildCurrent(true, currentHourEndMillis));
        CURRENT_PLAYERS.clear();
        setCurrentHour(nowMillis);
        TrafficPeriodReport existing = TrafficPeriodReportStore.loadHour(currentHourStartMillis, ZONE);
        if (existing != null) {
            for (TrafficPeriodReport.PlayerTraffic player : existing.players()) {
                CURRENT_PLAYERS.computeIfAbsent(
                        player.playerUuid(),
                        ignored -> new TrafficPeriodReportStore.MutablePlayer(player.playerUuid(), player.playerName())
                ).add(player.playerName(), player.traffic());
            }
        }
        lastCheckpointMillis = nowMillis;
    }

    private static void setCurrentHour(long nowMillis) {
        ZonedDateTime start = Instant.ofEpochMilli(nowMillis).atZone(ZONE).truncatedTo(ChronoUnit.HOURS);
        currentHourStartMillis = start.toInstant().toEpochMilli();
        currentHourEndMillis = start.plusHours(1L).toInstant().toEpochMilli();
    }

    private static void consumeAll(List<ChannelBandwidthStats.Snapshot> snapshots) {
        for (ChannelBandwidthStats.Snapshot snapshot : snapshots) {
            consume(snapshot);
        }
    }

    private static void consume(ChannelBandwidthStats.Snapshot current) {
        if (current == null || current.channelId() == null || current.playerId() == null) {
            return;
        }
        Baseline previous = BASELINES.get(current.channelId());
        if (previous == null || !current.playerId().equals(previous.playerId())) {
            BASELINES.put(current.channelId(), new Baseline(current.playerId(), current.playerName(), current));
            return;
        }
        TrafficPeriodReport.TrafficCounters delta = delta(current, previous.snapshot());
        if (!delta.isEmpty()) {
            String uuid = current.playerId().toString();
            CURRENT_PLAYERS.computeIfAbsent(
                    uuid,
                    ignored -> new TrafficPeriodReportStore.MutablePlayer(uuid, current.playerName())
            ).add(current.playerName(), delta);
        }
        BASELINES.put(current.channelId(), new Baseline(current.playerId(), current.playerName(), current));
    }

    private static TrafficPeriodReport buildCurrent(boolean complete, long nowMillis) {
        return TrafficPeriodReportStore.report(
                "hour",
                currentHourStartMillis,
                currentHourEndMillis,
                ZONE,
                complete,
                CURRENT_PLAYERS
        );
    }

    private static TrafficPeriodReport.TrafficCounters delta(
            ChannelBandwidthStats.Snapshot current,
            ChannelBandwidthStats.Snapshot previous
    ) {
        return new TrafficPeriodReport.TrafficCounters(
                positiveDelta(current.outboundRawEncodedPackets(), previous.outboundRawEncodedPackets()),
                positiveDelta(current.outboundRawEncodedBytes(), previous.outboundRawEncodedBytes()),
                positiveDelta(current.outboundVanillaCompressedEstimateBytes(), previous.outboundVanillaCompressedEstimateBytes()),
                positiveDelta(current.outboundVanillaEstimateWireBytes(), previous.outboundVanillaEstimateWireBytes()),
                positiveDelta(current.outboundTransportFrames(), previous.outboundTransportFrames()),
                positiveDelta(current.outboundTransportFrameBytes(), previous.outboundTransportFrameBytes()),
                positiveDelta(current.outboundBypassPackets(), previous.outboundBypassPackets()),
                positiveDelta(current.outboundBypassBytes(), previous.outboundBypassBytes()),
                positiveDelta(current.outboundWireBytes(), previous.outboundWireBytes()),
                positiveDelta(current.inboundRawEncodedPackets(), previous.inboundRawEncodedPackets()),
                positiveDelta(current.inboundRawEncodedBytes(), previous.inboundRawEncodedBytes()),
                positiveDelta(current.inboundTransportFrames(), previous.inboundTransportFrames()),
                positiveDelta(current.inboundTransportFrameBytes(), previous.inboundTransportFrameBytes()),
                positiveDelta(current.inboundBypassPackets(), previous.inboundBypassPackets()),
                positiveDelta(current.inboundBypassBytes(), previous.inboundBypassBytes()),
                positiveDelta(current.inboundWireBytes(), previous.inboundWireBytes())
        );
    }

    private static long positiveDelta(long current, long previous) {
        return current >= previous ? current - previous : 0L;
    }

    private record Baseline(UUID playerId, String playerName, ChannelBandwidthStats.Snapshot snapshot) {
    }
}
