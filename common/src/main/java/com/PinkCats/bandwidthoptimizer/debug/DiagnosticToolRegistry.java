package com.PinkCats.bandwidthoptimizer.debug;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiagnosticToolRegistry {

    private DiagnosticToolRegistry() {}

    public static boolean isEnabled(Tool tool) {
        if (tool == null) {
            return false;
        }
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
                return next;
            }
        }
    }

    public static void setEnabled(Tool tool, boolean enabled) {
        if (tool != null) {
            tool.enabled.set(enabled);
        }
    }

    public static void setAll(boolean enabled) {
        for (Tool tool : Tool.values()) {
            setEnabled(tool, enabled);
        }
    }

    public static String listText() {
        StringBuilder builder = new StringBuilder("BO diagnosetool:");
        for (Tool tool : Tool.values()) {
            builder.append('\n')
                    .append(tool.cost().label())
                    .append(' ')
                    .append(tool.id())
                    .append('=')
                    .append(isEnabled(tool) ? "on" : "off")
                    .append(" - ")
                    .append(tool.description());
        }
        return builder.toString();
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
