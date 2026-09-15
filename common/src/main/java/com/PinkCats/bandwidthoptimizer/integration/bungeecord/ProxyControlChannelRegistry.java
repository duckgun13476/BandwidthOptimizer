package com.PinkCats.bandwidthoptimizer.integration.bungeecord;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class ProxyControlChannelRegistry {
    static final String CONFIG_SECTION = "proxy";
    static final String CONFIG_KEY = "additional_control_channels";
    private static final String CONFIG_PATH_PROPERTY = "bandwidthoptimizer.proxyControlConfigPath";
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    private static final int MAX_ADDITIONAL_CHANNELS = 256;
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Set<String> BUILT_INS = Set.of(
            "bungeecord",
            "bungeecord:main",
            "bungee:main",
            "potato:chat",
            "potato:pcpbridge",
            "potato:tpa",
            "yuntpa:main"
    );
    private static final AtomicReference<Set<String>> ADDITIONAL = new AtomicReference<>(Set.of());
    private static final Object CONFIG_LOCK = new Object();

    private ProxyControlChannelRegistry() {
    }

    public static boolean contains(String payloadChannel) {
        String normalized = normalize(payloadChannel);
        return normalized != null && (BUILT_INS.contains(normalized) || ADDITIONAL.get().contains(normalized));
    }

    public static ReloadResult reload() {
        return reload(configPath(), true);
    }

    static ReloadResult reload(Path configPath) {
        return reload(configPath, false);
    }

    private static ReloadResult reload(Path configPath, boolean logFailure) {
        synchronized (CONFIG_LOCK) {
            try {
                ensureConfigExists(configPath);
                Set<String> parsed = parse(configPath);
                ADDITIONAL.set(parsed);
                return new ReloadResult(true, parsed.size(), "loaded");
            } catch (IOException | IllegalArgumentException exception) {
                if (logFailure) {
                    Bandwidthoptimizer.LOGGER.warn(
                            "Failed to reload additional proxy control channels from {}: {}; previous list remains active",
                            configPath,
                            exception.getMessage()
                    );
                }
                return new ReloadResult(false, ADDITIONAL.get().size(), exception.getMessage());
            }
        }
    }

    public static ChannelSnapshot snapshot() {
        return new ChannelSnapshot(sorted(BUILT_INS), sorted(ADDITIONAL.get()));
    }

    public static MutationResult add(String channel) {
        return mutate(configPath(), channel, true);
    }

    public static MutationResult remove(String channel) {
        return mutate(configPath(), channel, false);
    }

    static MutationResult add(Path configPath, String channel) {
        return mutate(configPath, channel, true);
    }

    static MutationResult remove(Path configPath, String channel) {
        return mutate(configPath, channel, false);
    }

    private static MutationResult mutate(Path configPath, String channel, boolean add) {
        String normalized = normalize(channel);
        if (normalized == null || normalized.length() > 128 || !CHANNEL.matcher(normalized).matches()) {
            return new MutationResult(false, false, ADDITIONAL.get().size(), "invalid channel id");
        }
        if (BUILT_INS.contains(normalized)) {
            String detail = add ? "channel is built in" : "built-in channels cannot be removed";
            return new MutationResult(true, false, ADDITIONAL.get().size(), detail);
        }
        synchronized (CONFIG_LOCK) {
            try {
                ensureConfigExists(configPath);
                LinkedHashSet<String> updated = new LinkedHashSet<>(parse(configPath));
                boolean changed = add ? updated.add(normalized) : updated.remove(normalized);
                if (!changed) {
                    return new MutationResult(true, false, updated.size(), add ? "channel already exists" : "channel was not configured");
                }
                if (updated.size() > MAX_ADDITIONAL_CHANNELS) {
                    return new MutationResult(false, false, ADDITIONAL.get().size(), "too many additional proxy control channels");
                }
                writeConfig(configPath, Set.copyOf(updated));
                ADDITIONAL.set(Set.copyOf(updated));
                return new MutationResult(true, true, updated.size(), add ? "added" : "removed");
            } catch (IOException | IllegalArgumentException exception) {
                return new MutationResult(false, false, ADDITIONAL.get().size(), exception.getMessage());
            }
        }
    }

    static void resetForTests() {
        ADDITIONAL.set(Set.of());
    }

    private static Path configPath() {
        String configuredPath = System.getProperty(CONFIG_PATH_PROPERTY);
        return configuredPath == null || configuredPath.isBlank()
                ? Path.of("config", "bandwidthoptimizer-common.toml")
                : Path.of(configuredPath);
    }

    private static Set<String> parse(Path configPath) throws IOException {
        long size = Files.size(configPath);
        if (size > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("config exceeds " + MAX_CONFIG_BYTES + " bytes");
        }
        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        String section = "";
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = stripComment(lines.get(lineIndex)).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            if (!CONFIG_SECTION.equals(section)) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 0 || !CONFIG_KEY.equals(line.substring(0, separator).trim())) {
                continue;
            }
            StringBuilder value = new StringBuilder(line.substring(separator + 1).trim());
            while (!arrayClosed(value) && ++lineIndex < lines.size()) {
                value.append('\n').append(stripComment(lines.get(lineIndex)).trim());
            }
            return parseArray(value.toString());
        }
        return Set.of();
    }

    private static Set<String> parseArray(String value) {
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            throw new IllegalArgumentException(CONFIG_KEY + " must be a TOML string array");
        }
        LinkedHashSet<String> channels = new LinkedHashSet<>();
        int index = 1;
        while (index < trimmed.length() - 1) {
            while (index < trimmed.length() - 1 && (Character.isWhitespace(trimmed.charAt(index)) || trimmed.charAt(index) == ',')) {
                index++;
            }
            if (index >= trimmed.length() - 1) {
                break;
            }
            if (trimmed.charAt(index++) != '"') {
                throw new IllegalArgumentException(CONFIG_KEY + " accepts quoted channel ids only");
            }
            StringBuilder entry = new StringBuilder();
            boolean closed = false;
            while (index < trimmed.length() - 1) {
                char current = trimmed.charAt(index++);
                if (current == '"') {
                    closed = true;
                    break;
                }
                if (current == '\\') {
                    if (index >= trimmed.length() - 1) {
                        throw new IllegalArgumentException("invalid escape in " + CONFIG_KEY);
                    }
                    char escaped = trimmed.charAt(index++);
                    if (escaped != '\\' && escaped != '"') {
                        throw new IllegalArgumentException("unsupported escape in " + CONFIG_KEY);
                    }
                    current = escaped;
                }
                entry.append(current);
            }
            if (!closed) {
                throw new IllegalArgumentException("unterminated string in " + CONFIG_KEY);
            }
            String normalized = normalize(entry.toString());
            if (normalized == null || normalized.length() > 128 || !CHANNEL.matcher(normalized).matches()) {
                throw new IllegalArgumentException("invalid proxy control channel: " + entry);
            }
            if (!BUILT_INS.contains(normalized)) {
                channels.add(normalized);
                if (channels.size() > MAX_ADDITIONAL_CHANNELS) {
                    throw new IllegalArgumentException("too many additional proxy control channels");
                }
            }
            while (index < trimmed.length() - 1 && Character.isWhitespace(trimmed.charAt(index))) {
                index++;
            }
            if (index < trimmed.length() - 1 && trimmed.charAt(index) != ',') {
                throw new IllegalArgumentException("missing comma in " + CONFIG_KEY);
            }
        }
        return Set.copyOf(channels);
    }

    private static boolean arrayClosed(CharSequence value) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\' && quoted) {
                escaped = true;
            } else if (current == '"') {
                quoted = !quoted;
            } else if (current == ']' && !quoted) {
                return true;
            }
        }
        return false;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\' && quoted) {
                escaped = true;
            } else if (current == '"') {
                quoted = !quoted;
            } else if (current == '#' && !quoted) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static String normalize(String channel) {
        if (channel == null) {
            return null;
        }
        String normalized = channel.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static void ensureConfigExists(Path configPath) throws IOException {
        if (Files.exists(configPath)) {
            return;
        }
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String initial = "[" + CONFIG_SECTION + "]\n" + CONFIG_KEY + " = []\n";
        try {
            Files.writeString(configPath, initial, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
        }
    }

    private static void writeConfig(Path configPath, Set<String> channels) throws IOException {
        String source = Files.readString(configPath, StandardCharsets.UTF_8);
        String value = formatArray(channels);
        Assignment assignment = findAssignment(source);
        String updated;
        if (assignment != null) {
            updated = source.substring(0, assignment.valueStart()) + value + source.substring(assignment.valueEnd());
        } else {
            int sectionEnd = findSectionEnd(source);
            if (sectionEnd >= 0) {
                String separator = sectionEnd == 0 || source.charAt(sectionEnd - 1) == '\n' ? "" : "\n";
                updated = source.substring(0, sectionEnd) + separator + CONFIG_KEY + " = " + value + "\n" + source.substring(sectionEnd);
            } else {
                String separator = source.isEmpty() || source.endsWith("\n") ? "" : "\n";
                updated = source + separator + "\n[" + CONFIG_SECTION + "]\n" + CONFIG_KEY + " = " + value + "\n";
            }
        }
        if (updated.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("config exceeds " + MAX_CONFIG_BYTES + " bytes");
        }
        Path absolute = configPath.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Assignment findAssignment(String source) {
        String section = "";
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = source.length();
            }
            String rawLine = source.substring(lineStart, lineEnd);
            String line = stripComment(rawLine).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
            } else if (CONFIG_SECTION.equals(section)) {
                int separator = line.indexOf('=');
                if (separator >= 0 && CONFIG_KEY.equals(line.substring(0, separator).trim())) {
                    int rawSeparator = rawLine.indexOf('=');
                    int valueStart = lineStart + rawSeparator + 1;
                    while (valueStart < source.length() && Character.isWhitespace(source.charAt(valueStart))) {
                        valueStart++;
                    }
                    return new Assignment(valueStart, findArrayEnd(source, valueStart));
                }
            }
            lineStart = lineEnd + 1;
        }
        return null;
    }

    private static int findArrayEnd(String source, int valueStart) {
        boolean quoted = false;
        boolean escaped = false;
        boolean comment = false;
        for (int index = valueStart; index < source.length(); index++) {
            char current = source.charAt(index);
            if (comment) {
                if (current == '\n') {
                    comment = false;
                }
            } else if (escaped) {
                escaped = false;
            } else if (current == '\\' && quoted) {
                escaped = true;
            } else if (current == '"') {
                quoted = !quoted;
            } else if (current == '#' && !quoted) {
                comment = true;
            } else if (current == ']' && !quoted) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException(CONFIG_KEY + " must be a TOML string array");
    }

    private static int findSectionEnd(String source) {
        String section = "";
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = source.length();
            }
            String line = stripComment(source.substring(lineStart, lineEnd)).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                String nextSection = line.substring(1, line.length() - 1).trim();
                if (CONFIG_SECTION.equals(section)) {
                    return lineStart;
                }
                section = nextSection;
            }
            lineStart = lineEnd + 1;
        }
        return CONFIG_SECTION.equals(section) ? source.length() : -1;
    }

    private static String formatArray(Set<String> channels) {
        List<String> sorted = sorted(channels);
        if (sorted.isEmpty()) {
            return "[]";
        }
        StringBuilder value = new StringBuilder("[\n");
        for (String channel : sorted) {
            value.append("    \"").append(channel).append("\",\n");
        }
        return value.append(']').toString();
    }

    private static List<String> sorted(Set<String> channels) {
        ArrayList<String> sorted = new ArrayList<>(channels);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    public record ReloadResult(boolean success, int additionalChannelCount, String detail) {
    }

    public record MutationResult(boolean success, boolean changed, int additionalChannelCount, String detail) {
    }

    public record ChannelSnapshot(List<String> builtIn, List<String> additional) {
    }

    private record Assignment(int valueStart, int valueEnd) {
    }
}
