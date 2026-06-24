package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public final class ServerBoLogExportService {

    private static final int MAX_EXPORT_CHARS = 4 * 1024 * 1024;
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final Pattern LOCALIZED_TIMESTAMP =
            Pattern.compile("^\\[(\\d{1,2})(\\d{1,2})月(\\d{4}) (\\d{2}):(\\d{2}):(\\d{2})\\.\\d{3}]");
    private static final String[] BO_MARKERS = {
            "[BO:",
            "BandwidthOptimizer",
            "bandwidthoptimizer",
            "com.PinkCats.bandwidthoptimizer"
    };
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new DaemonThreadFactory());

    private static volatile ServerBoLogExportSender sender;

    private ServerBoLogExportService() {
    }

    public static void setSender(ServerBoLogExportSender exportSender) {
        sender = exportSender;
    }

    public static int request(CommandSourceStack source, int minutes) {
        int safeMinutes = DiagnosticToolRegistry.validateMinutes(minutes);
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("BO server log export must be requested by an in-game OP player."));
            return 0;
        }
        if (sender == null) {
            source.sendFailure(Component.literal("BO server log export is unavailable on this loader."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO server log export queued for the last " + safeMinutes + " minutes."
        ), false);
        EXECUTOR.execute(() -> exportAndSend(server, player, safeMinutes));
        return Command.SINGLE_SUCCESS;
    }

    private static void exportAndSend(MinecraftServer server, ServerPlayer player, int minutes) {
        ExportResult result;
        try {
            result = collect(minutes);
        } catch (IOException exception) {
            server.execute(() -> player.sendSystemMessage(Component.literal(
                    "BO server log export failed: " + exception.getMessage()
            )));
            return;
        }

        server.execute(() -> {
            ServerBoLogExportSender activeSender = sender;
            if (activeSender == null || !activeSender.send(player, result.export())) {
                player.sendSystemMessage(Component.literal(
                        "BO server log export could not be sent. Check that your client has the same BO version."
                ));
                return;
            }
            player.sendSystemMessage(Component.literal(
                    "BO server log export sent: matchedLines=" + result.export().matchedLines()
                            + ", bytes=" + result.export().bytes().length
                            + (result.export().truncated() ? ", truncated=true" : "")
            ));
        });
    }

    private static ExportResult collect(int minutes) throws IOException {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds((long) minutes * 60L);
        Path logDirectory = Path.of("logs");
        List<Path> logFiles = logFiles(logDirectory, cutoff);
        String sessionId = UUID.randomUUID().toString();
        String fileName = "server-bo-log-" + FILE_TIME_FORMAT.format(LocalDateTime.now()) + ".log";
        StringBuilder body = new StringBuilder();
        int matchedLines = 0;
        boolean truncated = false;

        for (Path logFile : logFiles) {
            try (BufferedReader reader = openLog(logFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!isRecentEnough(line, cutoff)) {
                        continue;
                    }
                    if (!isBoLine(line)) {
                        continue;
                    }
                    String entry = logFile.getFileName() + " | " + line + '\n';
                    if (body.length() + entry.length() > MAX_EXPORT_CHARS) {
                        truncated = true;
                        break;
                    }
                    body.append(entry);
                    matchedLines++;
                }
            }
            if (truncated) {
                break;
            }
        }

        String header = "BandwidthOptimizer server BO log export\n"
                + "generatedAt=" + now + '\n'
                + "rangeMinutes=" + minutes + '\n'
                + "cutoff=" + cutoff + '\n'
                + "scannedFiles=" + logFiles.size() + '\n'
                + "matchedLines=" + matchedLines + '\n'
                + "truncated=" + truncated + '\n'
                + "filters=[BO:,BandwidthOptimizer,bandwidthoptimizer,com.PinkCats.bandwidthoptimizer]\n\n";
        byte[] bytes = (header + body).getBytes(StandardCharsets.UTF_8);
        return new ExportResult(new ServerBoLogExport(
                sessionId,
                fileName,
                bytes,
                minutes,
                logFiles.size(),
                matchedLines,
                truncated
        ));
    }

    private static List<Path> logFiles(Path logDirectory, Instant cutoff) throws IOException {
        if (!Files.isDirectory(logDirectory)) {
            return List.of();
        }
        ArrayList<Path> files = new ArrayList<>();
        try (var paths = Files.list(logDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(ServerBoLogExportService::isLogFile)
                    .filter(path -> modifiedAfter(path, cutoff))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(ServerBoLogExportService::lastModified));
        return files;
    }

    private static boolean isLogFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".log") || name.endsWith(".log.gz");
    }

    private static boolean modifiedAfter(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isAfter(cutoff);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private static BufferedReader openLog(Path path) throws IOException {
        InputStream inputStream = Files.newInputStream(path);
        if (path.getFileName().toString().endsWith(".gz")) {
            inputStream = new GZIPInputStream(inputStream);
        }
        return new BufferedReader(new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    private static boolean isRecentEnough(String line, Instant cutoff) {
        Instant timestamp = parseLocalizedTimestamp(line);
        return timestamp == null || !timestamp.isBefore(cutoff);
    }

    private static Instant parseLocalizedTimestamp(String line) {
        Matcher matcher = LOCALIZED_TIMESTAMP.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        try {
            int day = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int year = Integer.parseInt(matcher.group(3));
            int hour = Integer.parseInt(matcher.group(4));
            int minute = Integer.parseInt(matcher.group(5));
            int second = Integer.parseInt(matcher.group(6));
            return LocalDateTime.of(year, month, day, hour, minute, second)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isBoLine(String line) {
        if (line == null) {
            return false;
        }
        for (String marker : BO_MARKERS) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private record ExportResult(ServerBoLogExport export) {
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bo-server-log-export");
            thread.setDaemon(true);
            return thread;
        }
    }
}
