package com.PinkCats.bandwidthoptimizer.debug;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiagnosticToolRegistry {

    private DiagnosticToolRegistry() {}

    public static boolean isEnabled(Tool tool) {
        if (tool == null) {
            return false;
        }
        return Boolean.getBoolean(tool.propertyName()) || tool.enabled.get();
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
                "Netty event-loop MSPT and BO operation spike attribution",
                "bandwidthoptimizer.diagnosetool.nettyMSPT"
        ),
        MOVEMENT_CORRECTION(
                "movementCorrection",
                Cost.SMALL,
                "Server outbound player-position correction sends",
                "bandwidthoptimizer.diagnosetool.movementCorrection"
        ),
        MOVEMENT_BURST(
                "movementBurst",
                Cost.SMALL,
                "Serverbound move-packet burst windows",
                "bandwidthoptimizer.diagnosetool.movementBurst"
        ),
        TRANSPORT_ENCODE_COST(
                "transportEncodeCost",
                Cost.SMALL,
                "Outbound packet encode and BO hook cost spikes",
                "bandwidthoptimizer.diagnosetool.transportEncodeCost"
        ),
        MOVEMENT_POSITION_HANDLE(
                "movementPositionHandle",
                Cost.SMALL,
                "Client handling of server position correction packets",
                "bandwidthoptimizer.diagnosetool.movementPositionHandle"
        ),
        CACHE_CHUNK_HANDLE(
                "cacheChunkHandle",
                Cost.SMALL,
                "Client chunk-cache center, radius, and chunk handle events",
                "bandwidthoptimizer.diagnosetool.cacheChunkHandle"
        ),
        CACHE_PERSISTENT_IO(
                "cachePersistentIO",
                Cost.MEDIUM,
                "Persistent chunk-cache store and load events",
                "bandwidthoptimizer.diagnosetool.cachePersistentIO"
        ),
        CHANNEL_JSONL_CAPTURE(
                "channelJsonlCapture",
                Cost.LARGE,
                "Full channel JSONL packet capture",
                "bandwidthoptimizer.diagnosetool.channelJsonlCapture"
        ),
        CHUNK_VERIFY_OUTPUT(
                "chunkVerifyOutput",
                Cost.MEDIUM,
                "Chunk hotspot verification and boundary output files",
                "bandwidthoptimizer.diagnosetool.chunkVerifyOutput"
        );

        private final String id;
        private final Cost cost;
        private final String description;
        private final String propertyName;
        private final AtomicBoolean enabled = new AtomicBoolean();

        Tool(String id, Cost cost, String description, String propertyName) {
            this.id = id;
            this.cost = cost;
            this.description = description;
            this.propertyName = propertyName;
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

        String propertyName() {
            return propertyName;
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
