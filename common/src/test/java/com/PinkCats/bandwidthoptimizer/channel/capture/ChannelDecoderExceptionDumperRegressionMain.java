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

    public static void main(String[] arguments) throws IOException {
        Path output = Files.createTempDirectory("bo-decoder-dump-regression-");
        String previousOutput = System.getProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY);
        System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, output.toString());
        try {
            verifyInitialFailureDoesNotWrite(output);
            verifyEstablishedFailureUsesBoundedHistory(output);
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
        if (Files.exists(output.resolve("decoder-exception-dump"))) {
            throw new AssertionError("Initial malformed handshakes must not enter persistent diagnostics");
        }
        channel.close();
    }

    private static void verifyEstablishedFailureUsesBoundedHistory(Path output) throws IOException {
        EmbeddedChannel channel = channel();
        ChannelHandlerContext context = channel.pipeline().firstContext();
        ConnectionInternetProbeGuard.markMinecraftPacketDecoded(context);
        DecoderException failure = new DecoderException("established malformed frame");
        for (int index = 0; index <= ChannelDecoderExceptionDumper.MAX_DUMPS_PER_CONNECTION; index++) {
            setCandidate(channel, "PLAY", new byte[] {(byte) index, 0x02, 0x03});
            ChannelDecoderExceptionDumper.dumpIfDecoderException(context, channel, failure);
        }

        Path reportDirectory = output.resolve("decoder-exception-dump");
        Path history = reportDirectory.resolve("decoder-exception-history.jsonl");
        if (!Files.isRegularFile(reportDirectory.resolve("latest-decoder-exception.json"))
                || !Files.isRegularFile(reportDirectory.resolve("latest-decoder-exception-payload.bin"))
                || Files.readAllLines(history).size() != ChannelDecoderExceptionDumper.MAX_DUMPS_PER_CONNECTION) {
            throw new AssertionError("Established decoder failures must retain the bounded per-connection history");
        }

        EmbeddedChannel secondChannel = channel();
        ChannelHandlerContext secondContext = secondChannel.pipeline().firstContext();
        ConnectionInternetProbeGuard.markMinecraftPacketDecoded(secondContext);
        setCandidate(secondChannel, "PLAY", new byte[] {0x01, 0x02, 0x03});
        ChannelDecoderExceptionDumper.dumpIfDecoderException(secondContext, secondChannel, failure);
        if (Files.readAllLines(history).size() != ChannelDecoderExceptionDumper.MAX_DUMPS_PER_CONNECTION + 1) {
            throw new AssertionError("Decoder dump limits must be isolated per connection");
        }
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
