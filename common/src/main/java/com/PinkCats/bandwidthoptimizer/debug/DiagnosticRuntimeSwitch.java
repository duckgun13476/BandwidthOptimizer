package com.PinkCats.bandwidthoptimizer.debug;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class DiagnosticRuntimeSwitch {
    private static final String ALL_PROPERTY = "bandwidthoptimizer.diagnose.all";
    private static final AtomicInteger ENABLED_MASK = new AtomicInteger();

    private DiagnosticRuntimeSwitch() {}

    public static boolean isEnabled(Topic topic) {
        if (topic == null) {
            return false;
        }
        return DebugRuntimeConfig.isDiagnoseEnabled()
                || Boolean.getBoolean(ALL_PROPERTY)
                || Boolean.getBoolean(topic.propertyName())
                || (ENABLED_MASK.get() & topic.mask()) != 0;
    }

    public static void setEnabled(Topic topic, boolean enabled) {
        if (topic == null) {
            return;
        }
        if (enabled) {
            ENABLED_MASK.updateAndGet(mask -> mask | topic.mask());
        } else {
            ENABLED_MASK.updateAndGet(mask -> mask & ~topic.mask());
        }
    }

    public static void setAll(boolean enabled) {
        ENABLED_MASK.set(enabled ? Topic.allMask() : 0);
    }

    public static String statusText() {
        StringBuilder builder = new StringBuilder("BO diagnose:");
        for (Topic topic : Topic.values()) {
            builder.append(' ')
                    .append(topic.id())
                    .append('=')
                    .append(isEnabled(topic) ? "on" : "off");
        }
        return builder.toString();
    }

    public enum Topic {
        MOVEMENT("movement", 1 << 0),
        TRANSPORT("transport", 1 << 1),
        CACHE("cache", 1 << 2);

        private final String id;
        private final int mask;
        private final String propertyName;

        Topic(String id, int mask) {
            this.id = id;
            this.mask = mask;
            this.propertyName = "bandwidthoptimizer.diagnose." + id;
        }

        public String id() {
            return id;
        }

        int mask() {
            return mask;
        }

        String propertyName() {
            return propertyName;
        }

        public static Topic fromId(String id) {
            String normalized = id == null ? "" : id.toLowerCase(Locale.ROOT);
            for (Topic topic : values()) {
                if (topic.id.equals(normalized)) {
                    return topic;
                }
            }
            return null;
        }

        static int allMask() {
            int mask = 0;
            for (Topic topic : values()) {
                mask |= topic.mask;
            }
            return mask;
        }
    }
}
