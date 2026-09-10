package com.PinkCats.bandwidthoptimizer.channel.capture;

public final class LevelChunkNbtDepthFailureDiagnosticRegressionMain {

    private LevelChunkNbtDepthFailureDiagnosticRegressionMain() {}

    public static void main(String[] args) {
        verifiesCoordinatesForConfirmedDepthFailure();
        rejectsUnconfirmedOrMalformedPackets();
    }

    private static void verifiesCoordinatesForConfirmedDepthFailure() {
        ChannelCapturedFrame frame = frame(new byte[] {
                0x25,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xF4,
                0x00, 0x00, 0x00, 0x22
        });
        LevelChunkNbtDepthFailureDiagnostic.Result result =
                LevelChunkNbtDepthFailureDiagnostic.inspect(frame, confirmedFailure());

        check(result.matched(), "confirmed NBT depth failure should match");
        check(result.coordinates().available(), "coordinates should be available");
        check(result.coordinates().chunkX() == -12, "negative chunk X must round-trip");
        check(result.coordinates().chunkZ() == 34, "positive chunk Z must round-trip");
        check(result.coordinates().blockX() == -192L, "block X must be derived without overflow");
        check(result.coordinates().blockZ() == 544L, "block Z must be derived without overflow");
    }

    private static void rejectsUnconfirmedOrMalformedPackets() {
        LevelChunkNbtDepthFailureDiagnostic.Result wrongPacket =
                LevelChunkNbtDepthFailureDiagnostic.inspect(frame(new byte[] {0x25, 0, 0, 0, 0, 0, 0, 0, 0}),
                        new RuntimeException("Failed to decode packet 'clientbound/minecraft:custom_payload'", nbtDepthFailure()));
        check(!wrongPacket.matched(), "a non-level-chunk failure must not produce coordinates");

        LevelChunkNbtDepthFailureDiagnostic.Result wrongCause =
                LevelChunkNbtDepthFailureDiagnostic.inspect(frame(new byte[] {0x25, 0, 0, 0, 0, 0, 0, 0, 0}),
                        new RuntimeException("Failed to decode packet 'clientbound/minecraft:level_chunk_with_light'"));
        check(!wrongCause.matched(), "a non-NBT failure must not produce coordinates");

        LevelChunkNbtDepthFailureDiagnostic.Result truncated =
                LevelChunkNbtDepthFailureDiagnostic.inspect(frame(new byte[] {0x25, 0, 0, 0}), confirmedFailure());
        check(truncated.matched(), "the confirmed failure classification must survive a truncated capture");
        check(!truncated.coordinates().available(), "a truncated packet must not invent coordinates");

        LevelChunkNbtDepthFailureDiagnostic.Result malformedVarInt =
                LevelChunkNbtDepthFailureDiagnostic.inspect(frame(new byte[] {
                        (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        0, 0, 0, 0, 0, 0, 0, 0
                }), confirmedFailure());
        check(malformedVarInt.matched(), "the confirmed failure classification must survive a malformed packet id");
        check(!malformedVarInt.coordinates().available(), "a malformed packet id must not invent coordinates");
    }

    private static ChannelCapturedFrame frame(byte[] bytes) {
        return new ChannelCapturedFrame("channel", "INBOUND", "PLAY", "<pre-decode>", 37, bytes.length, bytes, 0L);
    }

    private static RuntimeException confirmedFailure() {
        return new RuntimeException(
                "Failed to decode packet 'clientbound/minecraft:level_chunk_with_light'",
                nbtDepthFailure()
        );
    }

    private static NbtAccounterException nbtDepthFailure() {
        return new NbtAccounterException("Tried to read NBT tag with too high complexity, depth > 512");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class NbtAccounterException extends RuntimeException {

        private NbtAccounterException(String message) {
            super(message);
        }
    }
}
