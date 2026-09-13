package com.PinkCats.bandwidthoptimizer.channel.algorithm.mapping;

import com.PinkCats.bandwidthoptimizer.Config;

import java.util.Arrays;
import java.util.List;

public final class KineticTemplateDictionarySecurityRegressionMain {

    private KineticTemplateDictionarySecurityRegressionMain() {}

    public static void main(String[] args) {
        int originalPacketBytes = Config.batchTemplateDictionaryMaxPacketBytes;
        int originalEntries = Config.batchTemplateDictionaryMaxEntries;
        int originalPayloadBytes = Config.batchTemplateDictionaryMaxPayloadBytes;
        try {
            verifyValidExactAndTemplateReuse();
            verifyCurrentFrameEviction();
            verifyEntryBudgetAndTransactionalFailure();
            verifyByteBudgetAndTransactionalFailure();
            verifyIdAndKeyCollisions();
            verifyTemplateStructureAndReferencePayload();
            verifyInvalidMappingIdsAndRemovals();
        } finally {
            Config.batchTemplateDictionaryMaxPacketBytes = originalPacketBytes;
            Config.batchTemplateDictionaryMaxEntries = originalEntries;
            Config.batchTemplateDictionaryMaxPayloadBytes = originalPayloadBytes;
        }
        System.out.println("Kinetic template dictionary security regression passed");
    }

    private static void verifyValidExactAndTemplateReuse() {
        configure(4096, 64, 1 << 20);
        KineticMapTableLayer exactEncoder = new KineticMapTableLayer();
        KineticMapTableLayer exactDecoder = new KineticMapTableLayer();
        byte[] exactPayload = filled(64, 7);
        decodeAndRequire(exactDecoder, exactEncoder.encodeWithTelemetry(exactPayload), exactPayload);
        KineticMapTableLayer.LayerResult exactAddition = exactEncoder.encodeWithTelemetry(exactPayload);
        require(exactAddition.telemetry().exactAdditionCount() == 1, "Exact mapping was not established");
        decodeAndRequire(exactDecoder, exactAddition, exactPayload);
        KineticMapTableLayer.LayerResult exactReference = exactEncoder.encodeWithTelemetry(exactPayload);
        require(exactReference.telemetry().exactReferenceCount() == 1, "Exact mapping was not reused");
        decodeAndRequire(exactDecoder, exactReference, exactPayload);

        KineticMapTableLayer templateEncoder = new KineticMapTableLayer();
        KineticMapTableLayer templateDecoder = new KineticMapTableLayer();
        byte[] base = filled(96, 3);
        byte[] changed = Arrays.copyOf(base, base.length);
        changed[40] = 9;
        decodeAndRequire(templateDecoder, templateEncoder.encodeWithTelemetry(base), base);
        KineticMapTableLayer.LayerResult templateAddition = templateEncoder.encodeWithTelemetry(changed);
        require(templateAddition.telemetry().templateAdditionCount() == 1, "Template mapping was not established");
        decodeAndRequire(templateDecoder, templateAddition, changed);
    }

    private static void verifyCurrentFrameEviction() {
        configure(4096, 1, 1 << 20);
        KineticMapTableLayer encoder = new KineticMapTableLayer();
        KineticMapTableLayer decoder = new KineticMapTableLayer();
        byte[] first = filled(64, 1);
        byte[] second = filled(64, 2);
        decodeAndRequire(decoder, encoder.encodeWithTelemetry(first), first);
        decodeAndRequire(decoder, encoder.encodeWithTelemetry(first), first);
        decodeAndRequire(decoder, encoder.encodeWithTelemetry(second), second);
        KineticMapTableLayer.LayerResult replacement = encoder.encodeWithTelemetry(second);
        require(replacement.telemetry().exactAdditionCount() == 1, "Replacement mapping was not added");
        require(replacement.telemetry().exactRemovalCount() == 1, "Eviction was delayed to a later frame");
        decodeAndRequire(decoder, replacement, second);
    }

    private static void verifyEntryBudgetAndTransactionalFailure() {
        configure(4096, 1, 64);
        KineticTemplateDictionarySession decoder = new KineticTemplateDictionarySession();
        byte[] retained = new byte[]{1, 2, 3, 4};
        requireDecoded(decoder, exactAdditionFrame(0, retained), retained);
        requireRejected(
                "entry budget",
                decoder,
                frame(
                        List.of(KineticTemplateMappingCodec.MappingAddition.exact(1, new byte[]{5})),
                        List.of(),
                        KineticTemplateMappingCodec.MappingEntry.exactReference(0)
                )
        );
        requireDecoded(decoder, exactReferenceFrame(0), retained);
    }

    private static void verifyByteBudgetAndTransactionalFailure() {
        configure(4096, 4, 4);
        KineticTemplateDictionarySession decoder = new KineticTemplateDictionarySession();
        byte[] retained = new byte[]{1, 2, 3, 4};
        requireDecoded(decoder, exactAdditionFrame(0, retained), retained);
        requireRejected(
                "byte budget",
                decoder,
                frame(
                        List.of(KineticTemplateMappingCodec.MappingAddition.exact(1, new byte[]{5})),
                        List.of(),
                        KineticTemplateMappingCodec.MappingEntry.exactReference(0)
                )
        );
        requireDecoded(decoder, exactReferenceFrame(0), retained);
    }

    private static void verifyIdAndKeyCollisions() {
        configure(4096, 8, 128);
        byte[] retained = new byte[]{8, 7, 6, 5};

        KineticTemplateDictionarySession idCollision = new KineticTemplateDictionarySession();
        requireDecoded(idCollision, exactAdditionFrame(0, retained), retained);
        requireRejected("existing id replacement", idCollision, exactAdditionFrame(0, new byte[]{4, 3, 2, 1}));
        requireDecoded(idCollision, exactReferenceFrame(0), retained);

        KineticTemplateDictionarySession duplicateId = new KineticTemplateDictionarySession();
        requireRejected(
                "same-frame duplicate id",
                duplicateId,
                frame(
                        List.of(
                                KineticTemplateMappingCodec.MappingAddition.exact(0, retained),
                                KineticTemplateMappingCodec.MappingAddition.exact(0, new byte[]{1})
                        ),
                        List.of(),
                        KineticTemplateMappingCodec.MappingEntry.literal(new byte[]{0})
                )
        );
        requireDecoded(duplicateId, exactAdditionFrame(0, retained), retained);

        KineticTemplateDictionarySession duplicateKey = new KineticTemplateDictionarySession();
        requireRejected(
                "same-frame duplicate payload",
                duplicateKey,
                frame(
                        List.of(
                                KineticTemplateMappingCodec.MappingAddition.exact(0, retained),
                                KineticTemplateMappingCodec.MappingAddition.exact(1, retained)
                        ),
                        List.of(),
                        KineticTemplateMappingCodec.MappingEntry.literal(new byte[]{0})
                )
        );
        requireDecoded(duplicateKey, exactAdditionFrame(0, retained), retained);

        KineticTemplateDictionarySession keyReuse = new KineticTemplateDictionarySession();
        requireDecoded(keyReuse, exactAdditionFrame(0, retained), retained);
        requireDecoded(
                keyReuse,
                frame(
                        List.of(KineticTemplateMappingCodec.MappingAddition.exact(1, retained)),
                        List.of(new KineticTemplateMappingCodec.MappingRemoval(KineticTemplateMappingCodec.ADD_EXACT, 0)),
                        KineticTemplateMappingCodec.MappingEntry.exactReference(1)
                ),
                retained
        );
        requireDecoded(keyReuse, exactReferenceFrame(1), retained);
    }

    private static void verifyTemplateStructureAndReferencePayload() {
        configure(4096, 8, 128);
        KineticTemplateDictionarySession malformedStructure = new KineticTemplateDictionarySession();
        KineticTemplateMappingCodec.TemplateDescription mismatched = new KineticTemplateMappingCodec.TemplateDescription(
                5,
                List.of(KineticTemplateMappingCodec.MappingSegment.literal(new byte[]{1, 2, 3, 4}))
        );
        requireRejected(
                "template segment sum",
                malformedStructure,
                frame(
                        List.of(KineticTemplateMappingCodec.MappingAddition.template(0, mismatched)),
                        List.of(),
                        KineticTemplateMappingCodec.MappingEntry.literal(new byte[]{0})
                )
        );
        requireDecoded(malformedStructure, exactAdditionFrame(0, new byte[]{9}), new byte[]{9});

        KineticTemplateDictionarySession malformedReference = new KineticTemplateDictionarySession();
        KineticTemplateMappingCodec.TemplateDescription valid = new KineticTemplateMappingCodec.TemplateDescription(
                4,
                List.of(
                        KineticTemplateMappingCodec.MappingSegment.literal(new byte[]{1, 2}),
                        KineticTemplateMappingCodec.MappingSegment.variable(2)
                )
        );
        requireRejected(
                "template variable payload",
                malformedReference,
                frame(
                        List.of(KineticTemplateMappingCodec.MappingAddition.template(0, valid)),
                        List.of(),
                        KineticTemplateMappingCodec.MappingEntry.encodedTemplateReference(0, new byte[]{3})
                )
        );
        requireDecoded(malformedReference, exactAdditionFrame(0, new byte[]{9}), new byte[]{9});
    }

    private static void verifyInvalidMappingIdsAndRemovals() {
        configure(4096, 8, 128);
        KineticTemplateDictionarySession invalidId = new KineticTemplateDictionarySession();
        requireRejected(
                "maximum mapping id",
                invalidId,
                exactAdditionFrame(Integer.MAX_VALUE, new byte[]{1})
        );
        requireDecoded(invalidId, exactAdditionFrame(0, new byte[]{2}), new byte[]{2});

        KineticTemplateDictionarySession unknownRemoval = new KineticTemplateDictionarySession();
        requireRejected(
                "unknown removal",
                unknownRemoval,
                frame(
                        List.of(),
                        List.of(new KineticTemplateMappingCodec.MappingRemoval(KineticTemplateMappingCodec.ADD_EXACT, 4)),
                        KineticTemplateMappingCodec.MappingEntry.literal(new byte[]{0})
                )
        );
        requireDecoded(unknownRemoval, exactAdditionFrame(0, new byte[]{3}), new byte[]{3});
    }

    private static byte[] exactAdditionFrame(int mappingId, byte[] payload) {
        return frame(
                List.of(KineticTemplateMappingCodec.MappingAddition.exact(mappingId, payload)),
                List.of(),
                KineticTemplateMappingCodec.MappingEntry.exactReference(mappingId)
        );
    }

    private static byte[] exactReferenceFrame(int mappingId) {
        return frame(List.of(), List.of(), KineticTemplateMappingCodec.MappingEntry.exactReference(mappingId));
    }

    private static byte[] frame(
            List<KineticTemplateMappingCodec.MappingAddition> additions,
            List<KineticTemplateMappingCodec.MappingRemoval> removals,
            KineticTemplateMappingCodec.MappingEntry entry
    ) {
        return KineticTemplateMappingCodec.encodeFrame(
                new KineticTemplateMappingCodec.FrameData(additions, removals, entry)
        );
    }

    private static void decodeAndRequire(
            KineticMapTableLayer decoder,
            KineticMapTableLayer.LayerResult encoded,
            byte[] expected
    ) {
        byte[] decoded = decoder.decodeWithTelemetry(encoded.bytes()).bytes();
        require(Arrays.equals(decoded, expected), "Valid mapping round trip changed payload bytes");
    }

    private static void requireDecoded(
            KineticTemplateDictionarySession decoder,
            byte[] encoded,
            byte[] expected
    ) {
        byte[] decoded = decoder.decodeWithTelemetry(encoded).bytes();
        require(Arrays.equals(decoded, expected), "Decoded mapping payload changed");
    }

    private static void requireRejected(String label, KineticTemplateDictionarySession decoder, byte[] encoded) {
        try {
            decoder.decodeWithTelemetry(encoded);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError(label + " was accepted");
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void configure(int maxPacketBytes, int maxEntries, int maxPayloadBytes) {
        Config.batchTemplateDictionaryMaxPacketBytes = maxPacketBytes;
        Config.batchTemplateDictionaryMaxEntries = maxEntries;
        Config.batchTemplateDictionaryMaxPayloadBytes = maxPayloadBytes;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
