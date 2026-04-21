package com.PinkCats.bandwidthoptimizer.channel.algorithm.mapping;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.TransportLayer;

import java.util.Arrays;

public final class KineticMapTableLayer implements TransportLayer {

    private final KineticTemplateDictionarySession dictionarySession = new KineticTemplateDictionarySession();

    @Override
    public byte[] encode(byte[] inputBytes) {
        return encodeWithTelemetry(inputBytes).bytes();
    }

    @Override
    public byte[] decode(byte[] inputBytes) {
        return decodeWithTelemetry(inputBytes).bytes();
    }

    public LayerResult encodeWithTelemetry(byte[] inputBytes) {
        byte[] safeBytes = copyBytesOrEmpty(inputBytes);
        return this.dictionarySession.encodeWithTelemetry(safeBytes);
    }

    public LayerResult decodeWithTelemetry(byte[] inputBytes) {
        byte[] safeBytes = copyBytesOrEmpty(inputBytes);
        return this.dictionarySession.decodeWithTelemetry(safeBytes);
    }

    @Override
    public void reset() {
        this.dictionarySession.reset();
    }

    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }

    public record LayerResult(byte[] bytes, MappingTelemetry telemetry) {
        public static LayerResult passthrough(byte[] bytes) {
            return new LayerResult(copyBytesOrEmpty(bytes), MappingTelemetry.empty());
        }
    }

    public record MappingTelemetry(
            int literalEntryCount,
            int exactReferenceCount,
            int templateReferenceCount,
            int exactAdditionCount,
            int templateAdditionCount,
            int exactRemovalCount,
            int templateRemovalCount
    ) {
        public static MappingTelemetry empty() {
            return new MappingTelemetry(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
