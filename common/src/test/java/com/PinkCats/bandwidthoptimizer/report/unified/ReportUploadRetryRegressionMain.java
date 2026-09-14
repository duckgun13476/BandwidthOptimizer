package com.PinkCats.bandwidthoptimizer.report.unified;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReportUploadRetryRegressionMain {
    private ReportUploadRetryRegressionMain() {
    }

    public static void main(String[] args) throws Exception {
        retriesBusyResponsesUntilSuccess();
        doesNotRetryClientErrors();
        stopsAfterBoundedAttempts();
        System.out.println("Report upload retry regression passed.");
    }

    private static void retriesBusyResponsesUntilSuccess() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (ServerFixture fixture = new ServerFixture(exchange -> {
            int attempt = requests.incrementAndGet();
            if (attempt < 3) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 503, "{\"retryable\":true,\"active\":16,\"waiting\":32}");
                return;
            }
            respond(exchange, 201, "{\"key\":\"abcdefghijklmnopqrstuvwx\"}");
        })) {
            UnifiedBandwidthReportService.UploadResponse response = send(fixture, seconds -> {
            });
            require(response.response().statusCode() == 201, "busy response did not recover");
            require(response.attempts() == 3, "unexpected retry count");
            require(requests.get() == 3, "unexpected request count");
        }
    }

    private static void doesNotRetryClientErrors() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (ServerFixture fixture = new ServerFixture(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 400, "{\"error\":\"invalid report\"}");
        })) {
            UnifiedBandwidthReportService.UploadResponse response = send(fixture, seconds -> {
                throw new AssertionError("client errors must not sleep or retry");
            });
            require(response.response().statusCode() == 400, "client error changed status");
            require(response.attempts() == 1, "client error was retried");
            require(requests.get() == 1, "client error sent more than once");
        }
    }

    private static void stopsAfterBoundedAttempts() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (ServerFixture fixture = new ServerFixture(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 503, "{\"retryable\":true,\"active\":16,\"waiting\":32}");
        })) {
            UnifiedBandwidthReportService.UploadResponse response = send(fixture, seconds -> {
            });
            require(response.response().statusCode() == 503, "exhausted retry changed status");
            require(response.attempts() == 4, "retry bound changed");
            require(requests.get() == 4, "retry bound was not enforced");
        }
    }

    private static UnifiedBandwidthReportService.UploadResponse send(
            ServerFixture fixture,
            UnifiedBandwidthReportService.RetrySleeper sleeper
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(fixture.uri())
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                .build();
        return UnifiedBandwidthReportService.sendWithRetry(HttpClient.newHttpClient(), request, sleeper);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class ServerFixture implements AutoCloseable {
        private final HttpServer server;

        private ServerFixture(ExchangeHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/upload", handler::handle);
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/upload");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
