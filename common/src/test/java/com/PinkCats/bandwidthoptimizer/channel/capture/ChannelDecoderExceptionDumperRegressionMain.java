package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.connection.ConnectionInternetProbeGuard;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class ChannelDecoderExceptionDumperRegressionMain {

    private static final AttributeKey<ChannelCapturedFrame> CANDIDATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:last_inbound_decode_candidate");

    private ChannelDecoderExceptionDumperRegressionMain() {}

    public static void main(String[] arguments) throws Exception {
        Path output = Files.createTempDirectory("bo-decoder-dump-regression-");
        String previousOutput = System.getProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY);
        System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, output.toString());
        try {
            verifyInitialFailureDoesNotWrite(output);
            verifyEstablishedFailureUsesBoundedHistory(output);
            var holder = Class.forName("com.PinkCats.bandwidthoptimizer.debug.ConnectionFailureReport$Holder");
            var recorder = holder.getDeclaredField("RECORDER");
            recorder.setAccessible(true);
            ((AutoCloseable) recorder.get(null)).close();
            var history = output.resolve("diagnostics/connection/failures.jsonl");
            var lines = Files.readAllLines(history);
            if (lines.size() != 22) throw new AssertionError("Rolling-second detail budget or recovery incorrect: " + lines.size());
            var first = com.google.gson.JsonParser.parseString(lines.get(0)).getAsJsonObject();
            if (!first.get("payload_hex").getAsString().equals("000203")
                    || !first.get("event").getAsString().equals("decoder_failure_detail")) {
                throw new AssertionError("Missing embedded decoder bytes");
            }
            var missing = com.google.gson.JsonParser.parseString(lines.get(20)).getAsJsonObject();
            if (missing.get("candidate_available").getAsBoolean()) throw new AssertionError("Missing frame was fabricated");
            var truncated = com.google.gson.JsonParser.parseString(lines.get(21)).getAsJsonObject();
            if (truncated.get("captured_payload_bytes").getAsInt() != 2048
                    || !truncated.get("payload_truncated").getAsBoolean()) throw new AssertionError("Payload not bounded");
            try (var files = Files.walk(output)) {
                if (files.filter(Files::isRegularFile).count() != 1) throw new AssertionError("Decoder created sidecar files");
            }
            System.out.println("Channel decoder exception dumper regression passed");
        } finally {
            if (previousOutput == null) {
                System.clearProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, previousOutput);
            }
            deleteRecursively(output);
        }
    }

    private static void verifyInitialFailureDoesNotWrite(Path output) {
        EmbeddedChannel channel = channel();
        setCandidate(channel, "HANDSHAKING", new byte[] {0x03, 0x00, 0x00, 0x00});
        ChannelDecoderExceptionDumper.dumpIfDecoderException(
                channel.pipeline().firstContext(),
                channel,
                new DecoderException("initial malformed frame")
        );
        if (Files.exists(output.resolve("decoder-exception-dump")) || Files.exists(output.resolve("diagnostics"))) {
            throw new AssertionError("Initial malformed handshakes must not enter persistent diagnostics");
        }
        channel.close();
    }

    private static void verifyEstablishedFailureUsesBoundedHistory(Path output) throws IOException, InterruptedException {
        EmbeddedChannel channel = channel();
        ChannelHandlerContext context = channel.pipeline().firstContext();
        ConnectionInternetProbeGuard.markMinecraftPacketDecoded(context);
        DecoderException failure = new DecoderException("established malformed frame");
        for (int index = 0; index <= ChannelDecoderExceptionDumper.MAX_DUMPS_PER_CONNECTION; index++) {
            setCandidate(channel, "PLAY", new byte[] {(byte) index, 0x02, 0x03});
            ChannelDecoderExceptionDumper.dumpIfDecoderException(context, channel, failure);
        }

        if (Files.exists(output.resolve("decoder-exception-dump"))) throw new AssertionError("Legacy sidecars created");

        EmbeddedChannel secondChannel = channel();
        ChannelHandlerContext secondContext = secondChannel.pipeline().firstContext();
        ConnectionInternetProbeGuard.markMinecraftPacketDecoded(secondContext);
        setCandidate(secondChannel, "PLAY", new byte[] {0x01, 0x02, 0x03});
        ChannelDecoderExceptionDumper.dumpIfDecoderException(secondContext, secondChannel, failure);
        Thread.sleep(1100); // New rolling-second allowance, same process-wide total budget.
        secondChannel.attr(CANDIDATE_KEY).set(null);
        ChannelDecoderExceptionDumper.dumpIfDecoderException(secondContext, secondChannel, failure);
        setCandidate(secondChannel, "PLAY", new byte[4096]);
        ChannelDecoderExceptionDumper.dumpIfDecoderException(secondContext, secondChannel, failure);
        secondChannel.close();
        channel.close();
    }

    private static void setCandidate(EmbeddedChannel channel, String protocol, byte[] payload) {
        channel.attr(CANDIDATE_KEY).set(new ChannelCapturedFrame(
                channel.id().asLongText(),
                "INBOUND",
                protocol,
                "<pre-decode>",
                -1,
                payload.length,
                payload,
                System.currentTimeMillis()
        ));
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
