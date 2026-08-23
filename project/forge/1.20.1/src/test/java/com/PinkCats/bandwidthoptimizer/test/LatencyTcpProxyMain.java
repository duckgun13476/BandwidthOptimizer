package com.PinkCats.bandwidthoptimizer.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class LatencyTcpProxyMain {

    private LatencyTcpProxyMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String listenHost = options.getOrDefault("listen-host", "127.0.0.1");
        int listenPort = parsePort(options.getOrDefault("listen-port", "25566"), "listen-port");
        String targetHost = options.getOrDefault("target-host", "127.0.0.1");
        int targetPort = parsePort(options.getOrDefault("target-port", "25567"), "target-port");
        long delayMillis = parseNonNegativeLong(options.getOrDefault("delay-ms", "300"), "delay-ms");
        long resetFirstConnectionAfterMillis = parseNonNegativeLong(
                options.getOrDefault("reset-first-connection-after-ms", "0"),
                "reset-first-connection-after-ms"
        );
        int resetConnectionId = parsePositiveInt(
                options.getOrDefault("reset-connection-id", "1"),
                "reset-connection-id"
        );

        AtomicInteger connectionIds = new AtomicInteger();
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(listenHost, listenPort));
            log("listening " + listenHost + ":" + listenPort
                    + " -> " + targetHost + ":" + targetPort
                    + ", delay=" + delayMillis + "ms"
                    + ", resetConnectionId=" + resetConnectionId
                    + ", resetAfter=" + resetFirstConnectionAfterMillis + "ms");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                int connectionId = connectionIds.incrementAndGet();
                Thread connectionThread = new Thread(
                        () -> handleConnection(
                                connectionId,
                                clientSocket,
                                targetHost,
                                targetPort,
                                delayMillis,
                                connectionId == resetConnectionId ? resetFirstConnectionAfterMillis : 0L
                        ),
                        "latency-tcp-proxy-connection-" + connectionId
                );
                connectionThread.setDaemon(true);
                connectionThread.start();
            }
        }
    }

    private static void handleConnection(
            int connectionId,
            Socket clientSocket,
            String targetHost,
            int targetPort,
            long delayMillis,
            long resetAfterMillis
    ) {
        Socket targetSocket = new Socket();
        try {
            configureSocket(clientSocket);
            targetSocket.connect(new InetSocketAddress(targetHost, targetPort), 10000);
            configureSocket(targetSocket);
            log("accepted id=" + connectionId
                    + " client=" + clientSocket.getRemoteSocketAddress()
                    + " target=" + targetHost + ":" + targetPort);

            AtomicBoolean closed = new AtomicBoolean(false);
            ScheduledExecutorService resetScheduler = null;
            if (resetAfterMillis > 0L) {
                resetScheduler = newSingleThreadScheduler("latency-tcp-proxy-reset-" + connectionId);
                resetScheduler.schedule(
                        () -> resetConnection(connectionId, clientSocket, targetSocket, closed),
                        resetAfterMillis,
                        TimeUnit.MILLISECONDS
                );
            }
            ScheduledExecutorService clientToServerDelay = newSingleThreadScheduler("latency-tcp-proxy-c2s-" + connectionId);
            ScheduledExecutorService serverToClientDelay = newSingleThreadScheduler("latency-tcp-proxy-s2c-" + connectionId);
            Thread clientToServerThread = new Thread(
                    () -> pumpDelayed(
                            connectionId,
                            "c2s",
                            clientSocket,
                            targetSocket,
                            clientToServerDelay,
                            delayMillis,
                            closed
                    ),
                    "latency-tcp-proxy-pump-c2s-" + connectionId
            );
            Thread serverToClientThread = new Thread(
                    () -> pumpDelayed(
                            connectionId,
                            "s2c",
                            targetSocket,
                            clientSocket,
                            serverToClientDelay,
                            delayMillis,
                            closed
                    ),
                    "latency-tcp-proxy-pump-s2c-" + connectionId
            );
            clientToServerThread.setDaemon(true);
            serverToClientThread.setDaemon(true);
            clientToServerThread.start();
            serverToClientThread.start();
            clientToServerThread.join();
            serverToClientThread.join();
            clientToServerDelay.shutdown();
            serverToClientDelay.shutdown();
            clientToServerDelay.awaitTermination(delayMillis + 5000L, TimeUnit.MILLISECONDS);
            serverToClientDelay.awaitTermination(delayMillis + 5000L, TimeUnit.MILLISECONDS);
            if (resetScheduler != null) {
                resetScheduler.shutdownNow();
            }
        } catch (Exception exception) {
            log("closed id=" + connectionId + " error=" + exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(targetSocket);
            log("closed id=" + connectionId);
        }
    }

    private static void pumpDelayed(
            int connectionId,
            String direction,
            Socket source,
            Socket target,
            ScheduledExecutorService scheduler,
            long delayMillis,
            AtomicBoolean closed
    ) {
        byte[] buffer = new byte[16384];
        try {
            InputStream inputStream = source.getInputStream();
            OutputStream outputStream = target.getOutputStream();
            while (!closed.get()) {
                int read = inputStream.read(buffer);
                if (read < 0) {
                    break;
                }
                byte[] packetBytes = new byte[read];
                System.arraycopy(buffer, 0, packetBytes, 0, read);
                scheduler.schedule(
                        () -> writeDelayed(connectionId, direction, outputStream, packetBytes, closed),
                        delayMillis,
                        TimeUnit.MILLISECONDS
                );
            }
        } catch (SocketException ignored) {
        } catch (IOException exception) {
            if (!closed.get()) {
                log("pump_error id=" + connectionId + " direction=" + direction + " error="
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        } finally {
            closed.set(true);
            scheduler.shutdown();
        }
    }

    private static void writeDelayed(
            int connectionId,
            String direction,
            OutputStream outputStream,
            byte[] packetBytes,
            AtomicBoolean closed
    ) {
        try {
            outputStream.write(packetBytes);
            outputStream.flush();
        } catch (IOException exception) {
            closed.set(true);
            log("write_error id=" + connectionId + " direction=" + direction + " error="
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static void configureSocket(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
    }

    private static void resetConnection(
            int connectionId,
            Socket clientSocket,
            Socket targetSocket,
            AtomicBoolean closed
    ) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log("resetting id=" + connectionId);
        try {
            clientSocket.setSoLinger(true, 0);
        } catch (SocketException ignored) {
        }
        closeQuietly(clientSocket);
        closeQuietly(targetSocket);
    }

    private static ScheduledExecutorService newSingleThreadScheduler(String threadName) {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String rawKey = args[index];
            if (!rawKey.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + rawKey);
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for argument: " + rawKey);
            }
            String key = rawKey.substring(2);
            String value = args[++index];
            options.put(key, value);
        }
        return options;
    }

    private static int parsePort(String rawValue, String label) {
        long parsedValue = parseNonNegativeLong(rawValue, label);
        if (parsedValue < 1L || parsedValue > 65535L) {
            throw new IllegalArgumentException(label + " must be in 1-65535: " + rawValue);
        }
        return (int) parsedValue;
    }

    private static long parseNonNegativeLong(String rawValue, String label) {
        try {
            long parsedValue = Long.parseLong(rawValue.trim());
            if (parsedValue < 0L) {
                throw new IllegalArgumentException(label + " must not be negative: " + rawValue);
            }
            return parsedValue;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be an integer: " + rawValue, exception);
        }
    }

    private static int parsePositiveInt(String rawValue, String label) {
        long parsedValue = parseNonNegativeLong(rawValue, label);
        if (parsedValue < 1L || parsedValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " must be positive: " + rawValue);
        }
        return (int) parsedValue;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void log(String message) {
        String line = "[LatencyTcpProxy] " + message + " time=" + Instant.now();
        System.out.println(line);
        System.out.flush();
    }
}
