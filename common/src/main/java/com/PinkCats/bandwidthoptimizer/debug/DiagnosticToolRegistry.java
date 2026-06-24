package com.PinkCats.bandwidthoptimizer.debug;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class DiagnosticToolRegistry {

    public static final int DEFAULT_MINUTES = 30;
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 300;

    private DiagnosticToolRegistry() {}

    public static boolean isEnabled(Tool tool) {
        if (tool == null) {
            return false;
        }
        expireIfNeeded(tool, System.currentTimeMillis());
        return tool.enabled.get();
    }

    public static boolean toggle(Tool tool) {
        if (tool == null) {
            return false;
        }
        while (true) {
            boolean current = tool.enabled.get();
            boolean next = !current;
            if (tool.enabled.compareAndSet(current, next)) {
                if (next) {
                    tool.expiresAtMillis.set(expiresAtMillis(DEFAULT_MINUTES));
                } else {
                    tool.expiresAtMillis.set(0L);
                }
                return next;
            }
        }
    }

    public static void setEnabled(Tool tool, boolean enabled) {
        if (tool != null) {
            tool.enabled.set(enabled);
            tool.expiresAtMillis.set(enabled ? expiresAtMillis(DEFAULT_MINUTES) : 0L);
        }
    }

    public static void enable(Tool tool, int minutes) {
        if (tool != null) {
            tool.expiresAtMillis.set(expiresAtMillis(minutes));
            tool.enabled.set(true);
        }
    }

    public static void disable(Tool tool) {
        setEnabled(tool, false);
    }

    public static void setAll(boolean enabled) {
        for (Tool tool : Tool.values()) {
            setEnabled(tool, enabled);
        }
    }

    public static long remainingMillis(Tool tool) {
        if (tool == null || !isEnabled(tool)) {
            return 0L;
        }
        long expiresAt = tool.expiresAtMillis.get();
        if (expiresAt <= 0L) {
            return 0L;
        }
        return Math.max(0L, expiresAt - System.currentTimeMillis());
    }

    public static int validateMinutes(int minutes) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException(
                    "Diagnostic duration must be between " + MIN_MINUTES + " and " + MAX_MINUTES + " minutes."
            );
        }
        return minutes;
    }

    public static String listText() {
        StringBuilder builder = new StringBuilder("BO debug tools:");
        for (Tool tool : Tool.values()) {
            boolean enabled = isEnabled(tool);
            builder.append('\n')
                    .append(tool.cost().label())
                    .append(' ')
                    .append(tool.id())
                    .append('=')
                    .append(enabled ? "on" : "off");
            if (enabled) {
                builder.append(" expiresIn=").append(formatRemaining(remainingMillis(tool)));
            }
            builder
                    .append(" - ")
                    .append(tool.description());
        }
        return builder.toString();
    }

    public static String formatRemaining(long millis) {
        long seconds = Math.max(0L, (millis + 999L) / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (minutes > 0L) {
            return remainingSeconds == 0L ? minutes + "m" : minutes + "m" + remainingSeconds + "s";
        }
        return remainingSeconds + "s";
    }

    private static void expireIfNeeded(Tool tool, long nowMillis) {
        if (!tool.enabled.get()) {
            return;
        }
        long expiresAt = tool.expiresAtMillis.get();
        if (expiresAt > 0L && nowMillis >= expiresAt && tool.enabled.compareAndSet(true, false)) {
            tool.expiresAtMillis.compareAndSet(expiresAt, 0L);
        }
    }

    private static long expiresAtMillis(int minutes) {
        return System.currentTimeMillis() + validateMinutes(minutes) * 60_000L;
    }

    public enum Cost {
        SMALL("小"),
        MEDIUM("中"),
        LARGE("大");

        private final String label;

        Cost(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Tool {
        NETTY_MSPT(
                "nettyMSPT",
                Cost.MEDIUM,
                "Netty event-loop MSPT and BO operation spike attribution"
        ),
        MOVEMENT_CORRECTION(
                "movementCorrection",
                Cost.SMALL,
                "Server outbound player-position correction sends"
        ),
        MOVEMENT_BURST(
                "movementBurst",
                Cost.SMALL,
                "Serverbound move-packet burst windows"
        ),
        TRANSPORT_ENCODE_COST(
                "transportEncodeCost",
                Cost.SMALL,
                "Outbound packet encode and BO hook cost spikes"
        ),
        MOVEMENT_POSITION_HANDLE(
                "movementPositionHandle",
                Cost.SMALL,
                "Client handling of server position correction packets"
        ),
        CACHE_CHUNK_HANDLE(
                "cacheChunkHandle",
                Cost.SMALL,
                "Client chunk-cache center, radius, and chunk handle events"
        ),
        CACHE_PERSISTENT_IO(
                "cachePersistentIO",
                Cost.MEDIUM,
                "Persistent chunk-cache store and load events"
        ),
        CHANNEL_JSONL_CAPTURE(
                "channelJsonlCapture",
                Cost.LARGE,
                "Full channel JSONL packet capture"
        ),
        CHUNK_VERIFY_OUTPUT(
                "chunkVerifyOutput",
                Cost.MEDIUM,
                "Chunk hotspot verification and boundary output files"
        ),
        CHUNK_LOAD_TIMELINE(
                "chunkLoadTimeline",
                Cost.LARGE,
                "Client chunk-load timeline after position sync"
        ),
        CHUNK_GAP_SCAN(
                "chunkGapScan",
                Cost.MEDIUM,
                "Client 3x3 chunk-cache gap scan"
        ),
        CHUNK_DELAY_TIMELINE(
                "chunkDelayTimeline",
                Cost.LARGE,
                "Chunk transport encode, restore, and stage timing timeline"
        ),
        TRANSPORT_TRACE_JOURNAL(
                "transportTraceJournal",
                Cost.MEDIUM,
                "Per-channel transport trace journal close dumps"
        ),
        CHUNK_TRANSPORT_FRAMES(
                "chunkTransportFrames",
                Cost.MEDIUM,
                "Chunk transport wrap, unwrap, and control-frame decisions"
        ),
        CHUNK_PEER_STATE(
                "chunkPeerState",
                Cost.MEDIUM,
                "Chunk peer observation, scope, and lifecycle state changes"
        ),
        CHUNK_GLOBAL_SNAPSHOT(
                "chunkGlobalSnapshot",
                Cost.MEDIUM,
                "Global chunk snapshot materialization, eviction, and release events"
        ),
        CACHE_MANIFEST_GATE(
                "cacheManifestGate",
                Cost.SMALL,
                "Persistent cache manifest gate arm, queue, and flush events"
        ),
        CACHE_MANIFEST_SYNC(
                "cacheManifestSync",
                Cost.MEDIUM,
                "Persistent cache manifest receive, batch apply, and completion events"
        ),
        CHUNK_LIFECYCLE(
                "chunkLifecycle",
                Cost.SMALL,
                "Player respawn, watch-boundary, and chunk lifecycle retention events"
        ),
        CHUNK_INBOUND_OBSERVATION(
                "chunkInboundObservation",
                Cost.SMALL,
                "Inbound chunk channel cleanup and server-switch reset events"
        ),
        CACHE_BUDGET(
                "cacheBudget",
                Cost.MEDIUM,
                "Client chunk-cache budget trimming and eviction summaries"
        ),
        CHUNK_PLAN_PREVIEW(
                "chunkPlanPreview",
                Cost.MEDIUM,
                "Chunk planner white-box preview decisions"
        ),
        CHUNK_PROTOCOL_PREVIEW(
                "chunkProtocolPreview",
                Cost.MEDIUM,
                "Chunk protocol frame encode/decode preview checks"
        ),
        COMPAT_DYNAMIC_GATES(
                "compatDynamicGates",
                Cost.MEDIUM,
                "Dynamic-structure compatibility gates and projection failures"
        );

        private final String id;
        private final Cost cost;
        private final String description;
        private final AtomicBoolean enabled = new AtomicBoolean();
        private final AtomicLong expiresAtMillis = new AtomicLong();

        Tool(String id, Cost cost, String description) {
            this.id = id;
            this.cost = cost;
            this.description = description;
        }

        public String id() {
            return id;
        }

        public Cost cost() {
            return cost;
        }

        public String description() {
            return description;
        }

        public static Tool fromId(String id) {
            String normalized = id == null ? "" : id.toLowerCase(Locale.ROOT);
            for (Tool tool : values()) {
                if (tool.id.toLowerCase(Locale.ROOT).equals(normalized)) {
                    return tool;
                }
            }
            return null;
        }
    }
}
