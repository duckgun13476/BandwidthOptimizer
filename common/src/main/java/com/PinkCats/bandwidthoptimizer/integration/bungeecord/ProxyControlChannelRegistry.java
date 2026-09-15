package com.PinkCats.bandwidthoptimizer.integration.bungeecord;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    private ProxyControlChannelRegistry() {
    }

    public static boolean contains(String payloadChannel) {
        String normalized = normalize(payloadChannel);
        return normalized != null && (BUILT_INS.contains(normalized) || ADDITIONAL.get().contains(normalized));
    }

    public static ReloadResult reload() {
        String configuredPath = System.getProperty(CONFIG_PATH_PROPERTY);
        Path configPath = configuredPath == null || configuredPath.isBlank()
                ? Path.of("config", "bandwidthoptimizer-common.toml")
                : Path.of(configuredPath);
        return reload(configPath, true);
    }

    static ReloadResult reload(Path configPath) {
        return reload(configPath, false);
    }

    private static ReloadResult reload(Path configPath, boolean logFailure) {
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

    static void resetForTests() {
        ADDITIONAL.set(Set.of());
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

    public record ReloadResult(boolean success, int additionalChannelCount, String detail) {
    }
}
