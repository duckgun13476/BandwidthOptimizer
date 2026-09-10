package com.PinkCats.bandwidthoptimizer.channel.capture;

import java.util.Locale;

final class LevelChunkNbtDepthFailureDiagnostic {

    private static final String LEVEL_CHUNK_PACKET = "clientbound/minecraft:level_chunk_with_light";

    private LevelChunkNbtDepthFailureDiagnostic() {}

    static Result inspect(ChannelCapturedFrame frame, Throwable throwable) {
        if (frame == null
                || !"INBOUND".equals(frame.direction())
                || !"PLAY".equals(frame.protocolName())
                || !containsLevelChunkDecodeFailure(throwable)
                || !containsNbtDepthLimit(throwable)) {
            return Result.notMatched();
        }
        return Result.matched(readCoordinates(frame.encodedBytes()));
    }

    private static boolean containsLevelChunkDecodeFailure(Throwable throwable) {
        return containsText(throwable, "failed to decode packet '" + LEVEL_CHUNK_PACKET + "'");
    }

    private static boolean containsNbtDepthLimit(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if ("NbtAccounterException".equals(current.getClass().getSimpleName())
                    && containsLowerCase(current.getMessage(), "depth > 512")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsText(Throwable throwable, String expected) {
        Throwable current = throwable;
        String needle = expected.toLowerCase(Locale.ROOT);
        while (current != null) {
            if (containsLowerCase(current.getMessage(), needle)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsLowerCase(String text, String expectedLowerCase) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(expectedLowerCase);
    }

    private static Coordinates readCoordinates(byte[] packetBytes) {
        VarIntResult packetId = readVarInt(packetBytes);
        if (!packetId.complete() || packetBytes == null || packetBytes.length - packetId.nextIndex() < 8) {
            return Coordinates.unavailable();
        }
        return new Coordinates(
                true,
                readInt(packetBytes, packetId.nextIndex()),
                readInt(packetBytes, packetId.nextIndex() + Integer.BYTES)
        );
    }

    private static VarIntResult readVarInt(byte[] bytes) {
        if (bytes == null) {
            return VarIntResult.incomplete();
        }
        for (int index = 0; index < Math.min(bytes.length, 5); index++) {
            int next = bytes[index] & 0xFF;
            if ((next & 0x80) == 0) {
                return new VarIntResult(true, index + 1);
            }
        }
        return VarIntResult.incomplete();
    }

    private static int readInt(byte[] bytes, int index) {
        return ((bytes[index] & 0xFF) << 24)
                | ((bytes[index + 1] & 0xFF) << 16)
                | ((bytes[index + 2] & 0xFF) << 8)
                | (bytes[index + 3] & 0xFF);
    }

    record Result(boolean matched, Coordinates coordinates) {

        static Result notMatched() {
            return new Result(false, Coordinates.unavailable());
        }

        static Result matched(Coordinates coordinates) {
            return new Result(true, coordinates == null ? Coordinates.unavailable() : coordinates);
        }
    }

    record Coordinates(boolean available, int chunkX, int chunkZ) {

        static Coordinates unavailable() {
            return new Coordinates(false, 0, 0);
        }

        long blockX() {
            return (long) this.chunkX << 4;
        }

        long blockZ() {
            return (long) this.chunkZ << 4;
        }
    }

    private record VarIntResult(boolean complete, int nextIndex) {

        private static VarIntResult incomplete() {
            return new VarIntResult(false, 0);
        }
    }
}
