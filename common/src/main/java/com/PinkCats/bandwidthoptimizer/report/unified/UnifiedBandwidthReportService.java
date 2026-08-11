package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import com.PinkCats.bandwidthoptimizer.report.traffic.TrafficPeriodReport;
import com.PinkCats.bandwidthoptimizer.report.traffic.TrafficPeriodReportJson;
import com.PinkCats.bandwidthoptimizer.report.traffic.TrafficHistoryReport;
import com.PinkCats.bandwidthoptimizer.report.traffic.TrafficPeriodReportStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

public final class UnifiedBandwidthReportService {
    public static final String UPLOAD_ENDPOINT_PROPERTY = "bandwidthoptimizer.report.uploadEndpoint";
    public static final String VIEWER_BASE_URL_PROPERTY = "bandwidthoptimizer.report.viewerBaseUrl";
    public static final String DEFAULT_UPLOAD_ENDPOINT = "https://bostats.torqueflux.com/api/v1/reports";
    public static final String DEFAULT_VIEWER_BASE_URL = "https://bostats.torqueflux.com/report/";
    private static final String REPORT_DIRECTORY = "reports";
    private static final int MAX_REPORT_BYTES = 4 * 1024 * 1024;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bo-report-upload");
        thread.setDaemon(true);
        thread.setContextClassLoader(UnifiedBandwidthReportService.class.getClassLoader());
        return thread;
    });
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10L))
            .build();

    private UnifiedBandwidthReportService() {
    }

    public static CompletableFuture<Result> save(UnifiedBandwidthReport report) {
        return submit(unifiedPayload(report), false);
    }

    public static CompletableFuture<Result> upload(UnifiedBandwidthReport report) {
        return submit(unifiedPayload(report), true);
    }

    public static CompletableFuture<Result> save(TrafficPeriodReport report) {
        return submit(trafficPayload(report), false);
    }

    public static CompletableFuture<Result> upload(TrafficPeriodReport report) {
        return submit(trafficPayload(report), true);
    }

    public static CompletableFuture<Result> upload(BandwidthReportBundle report) {
        return submit(bundlePayload(report), true);
    }

    public static CompletableFuture<Result> uploadWithTrafficHistory(BandwidthReportBundle report) {
        if (report == null) {
            return CompletableFuture.completedFuture(Result.failure("Report snapshot is unavailable.", null));
        }
        return submit(() -> {
            TrafficHistoryReport history = TrafficPeriodReportStore.loadCurrentMonth(report.playerTraffic());
            return bundlePayload(new BandwidthReportBundle(
                    report.schemaVersion(),
                    report.reportId(),
                    report.generatedAtMillis(),
                    report.privacyLevel(),
                    report.summary(),
                    report.playerTraffic(),
                    history
            ));
        }, true);
    }

    private static CompletableFuture<Result> submit(ReportPayload payload, boolean upload) {
        return submit(() -> payload, upload);
    }

    private static CompletableFuture<Result> submit(Supplier<ReportPayload> payloadSupplier, boolean upload) {
        if (!BUSY.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(Result.failure("A BO report is already being written or uploaded.", null));
        }
        return CompletableFuture.supplyAsync(() -> {
                    ReportPayload payload = payloadSupplier.get();
                    return payload == null
                            ? Result.failure("Report snapshot is unavailable.", null)
                            : process(payload, upload);
                }, WORKER)
                .whenComplete((ignored, throwable) -> BUSY.set(false));
    }

    private static Result process(ReportPayload payload, boolean upload) {
        Path localPath = null;
        try {
            byte[] json = payload.json();
            if (json.length > MAX_REPORT_BYTES) {
                return Result.failure("Report exceeds the 4 MiB safety limit.", null);
            }
            localPath = writeLocal(payload.directory(), payload.reportId(), json);
            if (!upload) {
                return Result.saved(localPath);
            }
            String endpoint = System.getProperty(UPLOAD_ENDPOINT_PROPERTY, DEFAULT_UPLOAD_ENDPOINT).trim();
            if (endpoint.isEmpty()) {
                return Result.failure(
                        "Report saved locally, but no upload endpoint is configured with -D" + UPLOAD_ENDPOINT_PROPERTY + ".",
                        localPath
                );
            }
            byte[] compressed = gzip(json);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30L))
                    .header("Content-Type", payload.mediaType())
                    .header("Content-Encoding", "gzip")
                    .header("Accept", "application/json, text/plain")
                    .header("User-Agent", "BandwidthOptimizer/" + Bandwidthoptimizer.networkProtocolVersion())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(compressed))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Result.failure("Upload failed with HTTP " + response.statusCode() + "; local report retained.", localPath);
            }
            String url = resolveViewerUrl(response);
            if (url.isBlank()) {
                return Result.failure("Upload succeeded but the server returned no report URL or key; local report retained.", localPath);
            }
            return Result.uploaded(localPath, url);
        } catch (Exception exception) {
            Bandwidthoptimizer.LOGGER.warn("[Report] Failed to save or upload unified report", exception);
            return Result.failure("Report failed: " + safeMessage(exception), localPath);
        }
    }

    private static Path writeLocal(String subdirectory, String reportId, byte[] json) throws IOException {
        String directoryName = subdirectory == null || subdirectory.isBlank()
                ? REPORT_DIRECTORY
                : REPORT_DIRECTORY + '/' + subdirectory;
        Path directory = BandwidthOptimizerOutputPaths.resolveDirectory(directoryName);
        Files.createDirectories(directory);
        Path reportPath = directory.resolve(reportId + ".json");
        Path temporaryPath = directory.resolve(reportId + ".json.tmp");
        Files.write(temporaryPath, json);
        moveReplace(temporaryPath, reportPath);
        Path latestTemporaryPath = directory.resolve("latest-report.json.tmp");
        Files.write(latestTemporaryPath, json);
        moveReplace(latestTemporaryPath, directory.resolve("latest-report.json"));
        return reportPath.toAbsolutePath().normalize();
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(bytes.length / 2, 512));
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }

    private static String resolveViewerUrl(HttpResponse<String> response) {
        Optional<String> location = response.headers().firstValue("Location");
        if (location.isPresent() && !location.get().isBlank()) {
            return expandViewerValue(location.get().trim());
        }
        String body = response.body() == null ? "" : response.body().trim();
        if (body.isEmpty()) {
            return "";
        }
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            if (object.has("url")) {
                return expandViewerValue(object.get("url").getAsString());
            }
            if (object.has("key")) {
                return expandViewerValue(object.get("key").getAsString());
            }
        } catch (RuntimeException ignored) {
        }
        return expandViewerValue(body);
    }

    private static String expandViewerValue(String value) {
        String safeValue = value == null ? "" : value.trim();
        if (safeValue.startsWith("https://") || safeValue.startsWith("http://")) {
            return safeValue;
        }
        String viewerBase = System.getProperty(VIEWER_BASE_URL_PROPERTY, DEFAULT_VIEWER_BASE_URL).trim();
        if (viewerBase.isEmpty() || safeValue.isEmpty()) {
            return "";
        }
        return viewerBase.endsWith("/") ? viewerBase + safeValue : viewerBase + '/' + safeValue;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static ReportPayload unifiedPayload(UnifiedBandwidthReport report) {
        return report == null ? null : new ReportPayload(
                report.reportId(), "", UnifiedBandwidthReportJson.MEDIA_TYPE, UnifiedBandwidthReportJson.encode(report)
        );
    }

    private static ReportPayload trafficPayload(TrafficPeriodReport report) {
        return report == null ? null : new ReportPayload(
                report.reportId(), "traffic/manual", TrafficPeriodReportJson.MEDIA_TYPE, TrafficPeriodReportJson.encode(report)
        );
    }

    private static ReportPayload bundlePayload(BandwidthReportBundle report) {
        return report == null ? null : new ReportPayload(
                report.reportId(), "bundles", BandwidthReportBundleJson.MEDIA_TYPE, BandwidthReportBundleJson.encode(report)
        );
    }

    private record ReportPayload(String reportId, String directory, String mediaType, byte[] json) {
    }

    public record Result(boolean success, Path localPath, String viewerUrl, String message) {
        private static Result saved(Path path) {
            return new Result(true, path, "", "Report saved locally.");
        }

        private static Result uploaded(Path path, String viewerUrl) {
            return new Result(true, path, viewerUrl, "Report uploaded.");
        }

        private static Result failure(String message, Path path) {
            return new Result(false, path, "", message);
        }
    }
}
