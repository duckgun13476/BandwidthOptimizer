package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.template;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmSupport;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class TemplateDictionaryBatchAlgorithm implements BatchAlgorithm {

    private static final int ADD_EXACT = 0;
    private static final int ADD_TEMPLATE = 1;
    private static final int ENTRY_LITERAL = 0;
    private static final int ENTRY_EXACT_REFERENCE = 1;
    private static final int ENTRY_TEMPLATE_REFERENCE = 2;
    private static final int ENTRY_STATIC_TEMPLATE_REFERENCE = 3;
    private static final int MAX_RECENT_SEEDS_PER_LENGTH = 48;
    private static final int MAX_CANDIDATE_SEEDS_PER_LOOKUP = 24;
    private static final int MIN_TEMPLATE_LITERAL_BYTES = 8;
    private static final List<StaticTemplate> STATIC_TEMPLATES = List.of(
            staticTemplate(0, 238,
                    TemplateSegment.literal(hex("0001")),
                    TemplateSegment.variable(2),
                    TemplateSegment.literal(hex("ff")),
                    TemplateSegment.variable(3),
                    TemplateSegment.literal(hex("cb030a000005000450756c6c0000000005000c4974656d506f736974696f6e3e4ccccf0a00044974656d0800026964000d6d696e6563726166743a6169720a000374616700010005436f756e740000030012636c7443757272656e74496e74657276616c000000010a0009466f726765436170730003000c46696c746572416d6f756e74000000400100045570546f01050015426f74746f6d416972466c6f7744697374616e63650000000005000450757368000000000a000646696c7465720800026964000d6d696e6563726166743a6169720a000374616700010005436f756e74000000"))
            ),
            staticTemplate(1, 231,
                    TemplateSegment.literal(hex("0001")),
                    TemplateSegment.variable(2),
                    TemplateSegment.literal(hex("ff99")),
                    TemplateSegment.variable(1),
                    TemplateSegment.literal(hex("cdcb030a000005000450756c6c0000000005000c4974656d506f736974696f6e3e4ccccf0a00044974656d0800026964000d6d696e6563726166743a616972010005436f756e740100030012636c7443757272656e74496e74657276616c000000010a0009466f726765436170730003000c46696c746572416d6f756e74000000400100045570546f01050015426f74746f6d416972466c6f7744697374616e63650000000005000450757368000000000a000646696c746572080002696400146d696e6563726166743a6e65746865727261636b010005436f756e74400000"))
            ),
            staticTemplate(2, 64,
                    TemplateSegment.literal(hex("146172636869746563747572793a6e6574776f726b1e6661726d5f616e645f636861726d3a73796e635f73617475726174696f6e000004")),
                    TemplateSegment.variable(1),
                    TemplateSegment.literal(hex("0000000000000000"))
            ),
            staticTemplate(3, 231,
                    TemplateSegment.literal(hex("000121")),
                    TemplateSegment.variable(1),
                    TemplateSegment.literal(hex("ff9e")),
                    TemplateSegment.variable(2),
                    TemplateSegment.literal(hex("cb030a000005000450756c6c0000000005000c4974656d506f736974696f6e3e4ccccf0a00044974656d0800026964000d6d696e6563726166743a6169720a000374616700010005436f756e740000030012636c7443757272656e74496e74657276616c000000010a0009466f726765436170730003000c46696c746572416d6f756e74000000400100045570546f01050015426f74746f6d416972466c6f7744697374616e63650000000005000450757368000000000a000646696c7465720800026964000d6d696e6563726166743a616972010005436f756e74")),
                    TemplateSegment.variable(1),
                    TemplateSegment.literal(hex("0000"))
            )
    );
    private static final Map<Integer, List<StaticTemplate>> STATIC_TEMPLATES_BY_LENGTH = buildStaticTemplatesByLength();

    @Override
    public String id() {
        return "template_dictionary";
    }

    @Override
    public Session createSession() {
        return new TemplateSession();
    }

    public static List<Integer> staticTemplateVariableLengths(int mappingId) {
        StaticTemplate staticTemplate = staticTemplateById(mappingId);
        if (staticTemplate == null) {
            return List.of();
        }
        return staticTemplate.entry().segments().stream()
                .filter(TemplateSegment::variable)
                .map(TemplateSegment::length)
                .toList();
    }

    private static final class TemplateSession implements Session {
        private final Map<HashKey, Integer> exactIdByHash = new HashMap<>();
        private final Map<Integer, ExactEntry> exactEntriesById = new HashMap<>();
        private final Map<TemplateKey, Integer> templateIdByKey = new HashMap<>();
        private final Map<Integer, TemplateEntry> templateEntriesById = new HashMap<>();
        private final Map<Integer, List<Integer>> templateIdsByLength = new HashMap<>();
        private final Map<HashKey, UnsyncedExactSeed> unsyncedExactSeeds = new HashMap<>();
        private final Map<Integer, List<SeedEntry>> recentSeedsByLength = new HashMap<>();
        private final List<PendingRemoval> pendingRemovals = new ArrayList<>();
        private int nextId;
        private long touchCounter;
        private int totalStoredBytes;

        @Override
        public EncodedBatch encode(List<byte[]> payloads) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                List<PendingRemoval> removalsToSend = List.copyOf(this.pendingRemovals);
                this.pendingRemovals.clear();
                List<PendingAddition> additions = new ArrayList<>();
                List<PendingEntry> entries = new ArrayList<>(payloads.size());
                List<EntryInfo> entryInfos = new ArrayList<>(payloads.size());
                LinkedHashSet<Integer> protectedIds = new LinkedHashSet<>();

                for (int index = 0; index < payloads.size(); index++) {
                    byte[] payload = payloads.get(index);
                    if (payload.length == 0 || payload.length > TemplateDictionaryBatchAlgorithmModule.maxPacketBytes()) {
                        entries.add(PendingEntry.literal(payload));
                        entryInfos.add(EntryInfo.literal(index, payload.length, literalEncodedBytes(payload.length)));
                        rememberSeed(payload);
                        continue;
                    }

                    HashKey payloadKey = new HashKey(payload);
                    Integer exactId = this.exactIdByHash.get(payloadKey);
                    if (exactId != null) {
                        touchExact(exactId);
                        protectedIds.add(exactId);
                        entries.add(PendingEntry.exactReference(exactId));
                        entryInfos.add(EntryInfo.reference(index, exactId, payload.length, referenceEncodedBytes(exactId)));
                        rememberSeed(payload);
                        continue;
                    }

                    TemplateMatch staticTemplateMatch = tryMatchStaticTemplate(payload);
                    if (staticTemplateMatch != null && staticTemplateMatch.encodedBytes() < literalEncodedBytes(payload.length)) {
                        entries.add(PendingEntry.staticTemplateReference(staticTemplateMatch.mappingId(), staticTemplateMatch.variableBytes()));
                        entryInfos.add(EntryInfo.reference(index, staticTemplateMatch.mappingId(), payload.length, staticTemplateMatch.encodedBytes()));
                        rememberSeed(payload);
                        continue;
                    }

                    TemplateMatch existingTemplateMatch = tryMatchExistingTemplate(payload);
                    if (existingTemplateMatch != null && existingTemplateMatch.encodedBytes() < literalEncodedBytes(payload.length)) {
                        touchTemplate(existingTemplateMatch.mappingId());
                        protectedIds.add(existingTemplateMatch.mappingId());
                        entries.add(PendingEntry.templateReference(existingTemplateMatch.mappingId(), existingTemplateMatch.variableBytes()));
                        entryInfos.add(EntryInfo.reference(index, existingTemplateMatch.mappingId(), payload.length, existingTemplateMatch.encodedBytes()));
                        rememberSeed(payload);
                        continue;
                    }

                    UnsyncedExactSeed unsynced = this.unsyncedExactSeeds.get(payloadKey);
                    if (unsynced != null && Arrays.equals(unsynced.payload(), payload)) {
                        int mappingId = this.nextId++;
                        byte[] stored = Arrays.copyOf(payload, payload.length);
                        this.exactIdByHash.put(new HashKey(stored), mappingId);
                        this.exactEntriesById.put(mappingId, new ExactEntry(stored, nextTouch()));
                        this.totalStoredBytes += stored.length;
                        additions.add(PendingAddition.exact(mappingId, stored));
                        this.unsyncedExactSeeds.remove(payloadKey);
                        protectedIds.add(mappingId);
                        entries.add(PendingEntry.exactReference(mappingId));
                        entryInfos.add(EntryInfo.reference(index, mappingId, payload.length, referenceEncodedBytes(mappingId)));
                        rememberSeed(payload);
                        continue;
                    }

                    TemplateCandidate templateCandidate = tryCreateTemplate(payload);
                    if (templateCandidate != null) {
                        Integer mappingId = this.templateIdByKey.get(templateCandidate.key());
                        if (mappingId == null) {
                            mappingId = this.nextId++;
                            TemplateEntry templateEntry = templateCandidate.templateEntry().touch(nextTouch());
                            this.templateIdByKey.put(templateCandidate.key(), mappingId);
                            this.templateEntriesById.put(mappingId, templateEntry);
                            this.templateIdsByLength.computeIfAbsent(templateEntry.totalLength(), ignored -> new ArrayList<>()).add(mappingId);
                            this.totalStoredBytes += templateEntry.literalByteCount();
                            additions.add(PendingAddition.template(mappingId, templateEntry));
                        } else {
                            touchTemplate(mappingId);
                        }
                        protectedIds.add(mappingId);
                        entries.add(PendingEntry.templateReference(mappingId, templateCandidate.variableBytes()));
                        entryInfos.add(EntryInfo.reference(index, mappingId, payload.length, templateCandidate.referenceEncodedBytes(mappingId)));
                        rememberSeed(payload);
                        continue;
                    }

                    this.unsyncedExactSeeds.put(payloadKey, new UnsyncedExactSeed(Arrays.copyOf(payload, payload.length), nextTouch()));
                    entries.add(PendingEntry.literal(payload));
                    entryInfos.add(EntryInfo.literal(index, payload.length, literalEncodedBytes(payload.length)));
                    rememberSeed(payload);
                }

                evictMappings(protectedIds);

                buffer.writeVarInt(additions.size());
                for (PendingAddition addition : additions) {
                    buffer.writeVarInt(addition.kind());
                    buffer.writeVarInt(addition.mappingId());
                    if (addition.kind() == ADD_EXACT) {
                        byte[] payload = addition.payload();
                        buffer.writeVarInt(payload.length);
                        buffer.writeBytes(payload);
                        continue;
                    }
                    TemplateEntry template = addition.templateEntry();
                    buffer.writeVarInt(template.totalLength());
                    buffer.writeVarInt(template.segments().size());
                    for (TemplateSegment segment : template.segments()) {
                        buffer.writeBoolean(segment.variable());
                        buffer.writeVarInt(segment.length());
                        if (!segment.variable()) {
                            buffer.writeBytes(segment.literalBytes());
                        }
                    }
                }

                buffer.writeVarInt(removalsToSend.size());
                for (PendingRemoval removal : removalsToSend) {
                    buffer.writeVarInt(removal.kind());
                    buffer.writeVarInt(removal.mappingId());
                }

                buffer.writeVarInt(entries.size());
                for (PendingEntry entry : entries) {
                    buffer.writeVarInt(entry.type());
                    switch (entry.type()) {
                        case ENTRY_LITERAL -> {
                            buffer.writeVarInt(entry.payload().length);
                            buffer.writeBytes(entry.payload());
                        }
                        case ENTRY_EXACT_REFERENCE -> buffer.writeVarInt(entry.mappingId());
                        case ENTRY_TEMPLATE_REFERENCE -> {
                            buffer.writeVarInt(entry.mappingId());
                            for (byte[] variableByteArray : entry.variableBytes()) {
                                buffer.writeBytes(variableByteArray);
                            }
                        }
                        case ENTRY_STATIC_TEMPLATE_REFERENCE -> {
                            buffer.writeVarInt(entry.mappingId());
                            for (byte[] variableByteArray : entry.variableBytes()) {
                                buffer.writeBytes(variableByteArray);
                            }
                        }
                        default -> throw new IllegalArgumentException("Unknown pending entry type: " + entry.type());
                    }
                }

                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(0, bytes);
                return new EncodedBatch(bytes, List.copyOf(entryInfos), additions.size(), removalsToSend.size());
            } finally {
                buffer.release();
            }
        }

        @Override
        public List<byte[]> decode(byte[] encodedBytes) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedBytes));
            List<String> additionsSeen = new ArrayList<>();
            List<String> removalsSeen = new ArrayList<>();
            int entryCount = -1;
            int entryIndex = -1;
            try {
                int addedCount = buffer.readVarInt();
                for (int i = 0; i < addedCount; i++) {
                    int kind = buffer.readVarInt();
                    int mappingId = buffer.readVarInt();
                    additionsSeen.add(mappingKindName(kind) + ":" + mappingId);
                    if (kind == ADD_EXACT) {
                        int payloadLength = buffer.readVarInt();
                        byte[] payload = new byte[payloadLength];
                        buffer.readBytes(payload);
                        this.exactIdByHash.put(new HashKey(payload), mappingId);
                        this.exactEntriesById.put(mappingId, new ExactEntry(payload, nextTouch()));
                        this.totalStoredBytes += payload.length;
                        this.nextId = Math.max(this.nextId, mappingId + 1);
                        continue;
                    }
                    if (kind != ADD_TEMPLATE) {
                        throw new IllegalArgumentException("Unknown mapping addition kind: " + kind);
                    }
                    int totalLength = buffer.readVarInt();
                    int segmentCount = buffer.readVarInt();
                    List<TemplateSegment> segments = new ArrayList<>(segmentCount);
                    int literalByteCount = 0;
                    for (int j = 0; j < segmentCount; j++) {
                        boolean variable = buffer.readBoolean();
                        int length = buffer.readVarInt();
                        if (variable) {
                            segments.add(TemplateSegment.variable(length));
                            continue;
                        }
                        byte[] literalBytes = new byte[length];
                        buffer.readBytes(literalBytes);
                        literalByteCount += literalBytes.length;
                        segments.add(TemplateSegment.literal(literalBytes));
                    }
                    TemplateEntry entry = new TemplateEntry(totalLength, List.copyOf(segments), literalByteCount, nextTouch());
                    TemplateKey key = TemplateKey.from(entry);
                    this.templateIdByKey.put(key, mappingId);
                    this.templateEntriesById.put(mappingId, entry);
                    this.templateIdsByLength.computeIfAbsent(totalLength, ignored -> new ArrayList<>()).add(mappingId);
                    this.totalStoredBytes += literalByteCount;
                    this.nextId = Math.max(this.nextId, mappingId + 1);
                }

                int removedCount = buffer.readVarInt();
                for (int i = 0; i < removedCount; i++) {
                    int kind = buffer.readVarInt();
                    int mappingId = buffer.readVarInt();
                    removalsSeen.add(mappingKindName(kind) + ":" + mappingId);
                    if (kind == ADD_EXACT) {
                        ExactEntry removed = this.exactEntriesById.remove(mappingId);
                        if (removed != null) {
                            this.exactIdByHash.remove(new HashKey(removed.payload()));
                            this.totalStoredBytes -= removed.payload().length;
                        }
                        continue;
                    }
                    if (kind != ADD_TEMPLATE) {
                        throw new IllegalArgumentException("Unknown mapping removal kind: " + kind);
                    }
                    TemplateEntry removed = this.templateEntriesById.remove(mappingId);
                    if (removed != null) {
                        this.templateIdByKey.remove(TemplateKey.from(removed));
                        List<Integer> ids = this.templateIdsByLength.get(removed.totalLength());
                        if (ids != null) {
                            ids.removeIf(id -> id == mappingId);
                            if (ids.isEmpty()) {
                                this.templateIdsByLength.remove(removed.totalLength());
                            }
                        }
                        this.totalStoredBytes -= removed.literalByteCount();
                    }
                }

                entryCount = buffer.readVarInt();
                List<byte[]> payloads = new ArrayList<>(entryCount);
                for (int i = 0; i < entryCount; i++) {
                    entryIndex = i;
                    int entryType = buffer.readVarInt();
                    if (entryType == ENTRY_LITERAL) {
                        int payloadLength = buffer.readVarInt();
                        byte[] payload = new byte[payloadLength];
                        buffer.readBytes(payload);
                        payloads.add(payload);
                        continue;
                    }
                    if (entryType == ENTRY_EXACT_REFERENCE) {
                        int mappingId = buffer.readVarInt();
                        ExactEntry entry = this.exactEntriesById.get(mappingId);
                        if (entry == null) {
                            throw missingMapping("exact", mappingId, entryType, entryIndex, entryCount, additionsSeen, removalsSeen, buffer.readableBytes());
                        }
                        this.exactEntriesById.put(mappingId, entry.touch(nextTouch()));
                        payloads.add(Arrays.copyOf(entry.payload(), entry.payload().length));
                        continue;
                    }
                    if (entryType == ENTRY_TEMPLATE_REFERENCE) {
                        int mappingId = buffer.readVarInt();
                        TemplateEntry template = this.templateEntriesById.get(mappingId);
                        if (template == null) {
                            throw missingMapping("template", mappingId, entryType, entryIndex, entryCount, additionsSeen, removalsSeen, buffer.readableBytes());
                        }
                        this.templateEntriesById.put(mappingId, template.touch(nextTouch()));
                        byte[] payload = new byte[template.totalLength()];
                        int writeIndex = 0;
                        for (TemplateSegment segment : template.segments()) {
                            if (segment.variable()) {
                                buffer.readBytes(payload, writeIndex, segment.length());
                                writeIndex += segment.length();
                                continue;
                            }
                            byte[] literalBytes = segment.literalBytes();
                            System.arraycopy(literalBytes, 0, payload, writeIndex, literalBytes.length);
                            writeIndex += literalBytes.length;
                        }
                        payloads.add(payload);
                        continue;
                    }
                    if (entryType == ENTRY_STATIC_TEMPLATE_REFERENCE) {
                        int mappingId = buffer.readVarInt();
                        StaticTemplate staticTemplate = staticTemplateById(mappingId);
                        if (staticTemplate == null) {
                            throw new IllegalStateException("Missing static template mapping id " + mappingId);
                        }
                        TemplateEntry template = staticTemplate.entry();
                        byte[] payload = new byte[template.totalLength()];
                        int writeIndex = 0;
                        for (TemplateSegment segment : template.segments()) {
                            if (segment.variable()) {
                                buffer.readBytes(payload, writeIndex, segment.length());
                                writeIndex += segment.length();
                                continue;
                            }
                            byte[] literalBytes = segment.literalBytes();
                            System.arraycopy(literalBytes, 0, payload, writeIndex, literalBytes.length);
                            writeIndex += literalBytes.length;
                        }
                        payloads.add(payload);
                        continue;
                    }
                    throw new IllegalArgumentException("Unknown entry type: " + entryType);
                }
                return payloads;
            } catch (RuntimeException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("[TemplateDictionary][DecodeState]")) {
                    throw exception;
                }
                throw new IllegalStateException(
                        exception.getMessage()
                                + " [TemplateDictionary][DecodeState] entryIndex=" + entryIndex
                                + "/" + entryCount
                                + ", additions=" + additionsSeen
                                + ", removals=" + removalsSeen
                                + ", exactCount=" + this.exactEntriesById.size()
                                + ", templateCount=" + this.templateEntriesById.size()
                                + ", exactIds=" + sampleIds(this.exactEntriesById)
                                + ", templateIds=" + sampleIds(this.templateEntriesById)
                                + ", readableBytes=" + buffer.readableBytes(),
                        exception
                );
            } finally {
                buffer.release();
            }
        }

        private IllegalStateException missingMapping(
                String kind,
                int mappingId,
                int entryType,
                int entryIndex,
                int entryCount,
                List<String> additionsSeen,
                List<String> removalsSeen,
                int readableBytes
        ) {
            return new IllegalStateException(
                    "Missing " + kind + " mapping id " + mappingId
                            + " [TemplateDictionary][DecodeState] entryType=" + entryType
                            + ", entryIndex=" + entryIndex + "/" + entryCount
                            + ", additions=" + additionsSeen
                            + ", removals=" + removalsSeen
                            + ", exactCount=" + this.exactEntriesById.size()
                            + ", templateCount=" + this.templateEntriesById.size()
                            + ", exactIds=" + sampleIds(this.exactEntriesById)
                            + ", templateIds=" + sampleIds(this.templateEntriesById)
                            + ", readableBytes=" + readableBytes
            );
        }

        private static String mappingKindName(int kind) {
            return switch (kind) {
                case ADD_EXACT -> "exact";
                case ADD_TEMPLATE -> "template";
                default -> "unknown(" + kind + ")";
            };
        }

        private static String sampleIds(Map<Integer, ?> entriesById) {
            if (entriesById.isEmpty()) {
                return "[]";
            }
            return entriesById.keySet().stream()
                    .sorted()
                    .limit(16)
                    .toList()
                    + (entriesById.size() > 16 ? "...+" + (entriesById.size() - 16) : "");
        }

        @Override
        public void reset() {
            this.exactIdByHash.clear();
            this.exactEntriesById.clear();
            this.templateIdByKey.clear();
            this.templateEntriesById.clear();
            this.templateIdsByLength.clear();
            this.unsyncedExactSeeds.clear();
            this.recentSeedsByLength.clear();
            this.pendingRemovals.clear();
            this.nextId = 0;
            this.touchCounter = 0L;
            this.totalStoredBytes = 0;
        }

        private TemplateMatch tryMatchExistingTemplate(byte[] payload) {
            List<Integer> candidateIds = this.templateIdsByLength.get(payload.length);
            if (candidateIds == null || candidateIds.isEmpty()) {
                return null;
            }

            TemplateMatch best = null;
            for (int mappingId : candidateIds) {
                TemplateEntry entry = this.templateEntriesById.get(mappingId);
                if (entry == null) {
                    continue;
                }
                int payloadIndex = 0;
                List<byte[]> variableBytes = new ArrayList<>();
                boolean matched = true;
                for (TemplateSegment segment : entry.segments()) {
                    if (segment.variable()) {
                        byte[] bytes = Arrays.copyOfRange(payload, payloadIndex, payloadIndex + segment.length());
                        variableBytes.add(bytes);
                        payloadIndex += segment.length();
                        continue;
                    }
                    byte[] literalBytes = segment.literalBytes();
                    for (int i = 0; i < literalBytes.length; i++) {
                        if (payload[payloadIndex + i] != literalBytes[i]) {
                            matched = false;
                            break;
                        }
                    }
                    if (!matched) {
                        break;
                    }
                    payloadIndex += literalBytes.length;
                }
                if (!matched) {
                    continue;
                }

                TemplateMatch match = new TemplateMatch(
                        ENTRY_TEMPLATE_REFERENCE,
                        mappingId,
                        List.copyOf(variableBytes),
                        referenceEncodedBytes(ENTRY_TEMPLATE_REFERENCE, mappingId, variableBytes)
                );
                if (best == null || match.encodedBytes() < best.encodedBytes()) {
                    best = match;
                }
            }
            return best;
        }

        private TemplateMatch tryMatchStaticTemplate(byte[] payload) {
            List<StaticTemplate> candidates = STATIC_TEMPLATES_BY_LENGTH.get(payload.length);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }

            TemplateMatch best = null;
            for (StaticTemplate staticTemplate : candidates) {
                TemplateMatch match = matchTemplate(payload, staticTemplate.id(), staticTemplate.entry(), ENTRY_STATIC_TEMPLATE_REFERENCE);
                if (match == null) {
                    continue;
                }
                if (best == null || match.encodedBytes() < best.encodedBytes()) {
                    best = match;
                }
            }
            return best;
        }

        private TemplateMatch matchTemplate(byte[] payload, int mappingId, TemplateEntry entry, int entryType) {
            int payloadIndex = 0;
            List<byte[]> variableBytes = new ArrayList<>();
            for (TemplateSegment segment : entry.segments()) {
                if (segment.variable()) {
                    byte[] bytes = Arrays.copyOfRange(payload, payloadIndex, payloadIndex + segment.length());
                    variableBytes.add(bytes);
                    payloadIndex += segment.length();
                    continue;
                }
                byte[] literalBytes = segment.literalBytes();
                for (int i = 0; i < literalBytes.length; i++) {
                    if (payload[payloadIndex + i] != literalBytes[i]) {
                        return null;
                    }
                }
                payloadIndex += literalBytes.length;
            }
            return new TemplateMatch(entryType, mappingId, List.copyOf(variableBytes), referenceEncodedBytes(entryType, mappingId, variableBytes));
        }

        private TemplateCandidate tryCreateTemplate(byte[] payload) {
            List<SeedEntry> seeds = this.recentSeedsByLength.get(payload.length);
            if (seeds == null || seeds.isEmpty()) {
                return null;
            }

            TemplateCandidate best = null;
            int considered = 0;
            for (int i = seeds.size() - 1; i >= 0 && considered < MAX_CANDIDATE_SEEDS_PER_LOOKUP; i--, considered++) {
                SeedEntry seed = seeds.get(i);
                TemplateCandidate candidate = buildTemplateCandidate(seed.payload(), payload);
                if (candidate == null) {
                    continue;
                }
                if (best == null || candidate.referenceEncodedBytes(-1) < best.referenceEncodedBytes(-1)) {
                    best = candidate;
                }
            }
            return best;
        }

        private TemplateCandidate buildTemplateCandidate(byte[] base, byte[] payload) {
            if (base.length != payload.length || base.length == 0) {
                return null;
            }

            List<Range> diffRuns = new ArrayList<>();
            int totalChangedBytes = 0;
            int index = 0;
            while (index < base.length) {
                if (base[index] == payload[index]) {
                    index++;
                    continue;
                }

                int start = index;
                while (index < base.length && base[index] != payload[index]) {
                    index++;
                }
                int endExclusive = index;
                diffRuns.add(new Range(start, endExclusive - start));
                totalChangedBytes += endExclusive - start;
                if (diffRuns.size() > TemplateDictionaryBatchAlgorithmModule.maxDiffRuns()
                        || totalChangedBytes > TemplateDictionaryBatchAlgorithmModule.maxChangedBytes()) {
                    return null;
                }
            }

            if (diffRuns.isEmpty()) {
                return null;
            }
            if (base.length - totalChangedBytes < MIN_TEMPLATE_LITERAL_BYTES) {
                return null;
            }

            List<TemplateSegment> segments = new ArrayList<>();
            List<byte[]> variableBytes = new ArrayList<>();
            int position = 0;
            int literalByteCount = 0;
            for (Range diffRun : diffRuns) {
                if (diffRun.start() > position) {
                    byte[] literalBytes = Arrays.copyOfRange(base, position, diffRun.start());
                    literalByteCount += literalBytes.length;
                    segments.add(TemplateSegment.literal(literalBytes));
                }
                byte[] variable = Arrays.copyOfRange(payload, diffRun.start(), diffRun.endExclusive());
                variableBytes.add(variable);
                segments.add(TemplateSegment.variable(variable.length));
                position = diffRun.endExclusive();
            }
            if (position < base.length) {
                byte[] literalBytes = Arrays.copyOfRange(base, position, base.length);
                literalByteCount += literalBytes.length;
                segments.add(TemplateSegment.literal(literalBytes));
            }

            TemplateEntry entry = new TemplateEntry(base.length, List.copyOf(segments), literalByteCount, 0L);
            TemplateKey key = TemplateKey.from(entry);
            int encodedBytes = BatchAlgorithmSupport.varIntSize(ENTRY_TEMPLATE_REFERENCE)
                    + BatchAlgorithmSupport.varIntSize(0)
                    + variableBytes.stream().mapToInt(bytes -> bytes.length).sum();
            if (encodedBytes >= literalEncodedBytes(payload.length)) {
                return null;
            }
            return new TemplateCandidate(entry, key, List.copyOf(variableBytes));
        }

        private void rememberSeed(byte[] payload) {
            SeedEntry seedEntry = new SeedEntry(Arrays.copyOf(payload, payload.length), nextTouch());
            List<SeedEntry> seeds = this.recentSeedsByLength.computeIfAbsent(payload.length, ignored -> new ArrayList<>());
            seeds.add(seedEntry);
            if (seeds.size() > MAX_RECENT_SEEDS_PER_LENGTH) {
                seeds.remove(0);
            }
        }

        private void evictMappings(LinkedHashSet<Integer> protectedIds) {
            int maxEntries = TemplateDictionaryBatchAlgorithmModule.maxEntries();
            int maxPayloadBytes = TemplateDictionaryBatchAlgorithmModule.maxPayloadBytes();
            if (this.exactEntriesById.size() + this.templateEntriesById.size() <= maxEntries
                    && this.totalStoredBytes <= maxPayloadBytes) {
                return;
            }

            List<EvictionCandidate> candidates = new ArrayList<>();
            for (Map.Entry<Integer, ExactEntry> entry : this.exactEntriesById.entrySet()) {
                if (!protectedIds.contains(entry.getKey())) {
                    candidates.add(new EvictionCandidate(ADD_EXACT, entry.getKey(), entry.getValue().lastTouched(), entry.getValue().payload().length));
                }
            }
            for (Map.Entry<Integer, TemplateEntry> entry : this.templateEntriesById.entrySet()) {
                if (!protectedIds.contains(entry.getKey())) {
                    candidates.add(new EvictionCandidate(ADD_TEMPLATE, entry.getKey(), entry.getValue().lastTouched(), entry.getValue().literalByteCount()));
                }
            }
            candidates.sort(Comparator.comparingLong(EvictionCandidate::lastTouched));

            for (EvictionCandidate candidate : candidates) {
                if (this.exactEntriesById.size() + this.templateEntriesById.size() <= maxEntries
                        && this.totalStoredBytes <= maxPayloadBytes) {
                    break;
                }

                if (candidate.kind() == ADD_EXACT) {
                    ExactEntry removed = this.exactEntriesById.remove(candidate.mappingId());
                    if (removed == null) {
                        continue;
                    }
                    this.exactIdByHash.remove(new HashKey(removed.payload()));
                    this.totalStoredBytes -= removed.payload().length;
                    this.pendingRemovals.add(new PendingRemoval(ADD_EXACT, candidate.mappingId()));
                    continue;
                }

                TemplateEntry removed = this.templateEntriesById.remove(candidate.mappingId());
                if (removed == null) {
                    continue;
                }
                this.templateIdByKey.remove(TemplateKey.from(removed));
                List<Integer> ids = this.templateIdsByLength.get(removed.totalLength());
                if (ids != null) {
                    ids.removeIf(id -> id == candidate.mappingId());
                    if (ids.isEmpty()) {
                        this.templateIdsByLength.remove(removed.totalLength());
                    }
                }
                this.totalStoredBytes -= removed.literalByteCount();
                this.pendingRemovals.add(new PendingRemoval(ADD_TEMPLATE, candidate.mappingId()));
            }
        }

        private void touchExact(int mappingId) {
            ExactEntry entry = this.exactEntriesById.get(mappingId);
            if (entry != null) {
                this.exactEntriesById.put(mappingId, entry.touch(nextTouch()));
            }
        }

        private void touchTemplate(int mappingId) {
            TemplateEntry entry = this.templateEntriesById.get(mappingId);
            if (entry != null) {
                this.templateEntriesById.put(mappingId, entry.touch(nextTouch()));
            }
        }

        private long nextTouch() {
            return ++this.touchCounter;
        }

        private static int literalEncodedBytes(int payloadLength) {
            return BatchAlgorithmSupport.varIntSize(ENTRY_LITERAL)
                    + BatchAlgorithmSupport.varIntSize(payloadLength)
                    + payloadLength;
        }

        private static int referenceEncodedBytes(int mappingId) {
            return BatchAlgorithmSupport.varIntSize(ENTRY_EXACT_REFERENCE)
                    + BatchAlgorithmSupport.varIntSize(mappingId);
        }

        private static int referenceEncodedBytes(int entryType, int mappingId, List<byte[]> variableBytes) {
            return BatchAlgorithmSupport.varIntSize(entryType)
                    + BatchAlgorithmSupport.varIntSize(mappingId)
                    + variableBytes.stream().mapToInt(bytes -> bytes.length).sum();
        }
    }

    private record PendingAddition(int kind, int mappingId, byte[] payload, TemplateEntry templateEntry) {
        private static PendingAddition exact(int mappingId, byte[] payload) {
            return new PendingAddition(ADD_EXACT, mappingId, payload, null);
        }

        private static PendingAddition template(int mappingId, TemplateEntry templateEntry) {
            return new PendingAddition(ADD_TEMPLATE, mappingId, null, templateEntry);
        }
    }

    private record PendingRemoval(int kind, int mappingId) {
    }

    private record PendingEntry(int type, int mappingId, byte[] payload, List<byte[]> variableBytes) {
        private static PendingEntry literal(byte[] payload) {
            return new PendingEntry(ENTRY_LITERAL, -1, payload, List.of());
        }

        private static PendingEntry exactReference(int mappingId) {
            return new PendingEntry(ENTRY_EXACT_REFERENCE, mappingId, null, List.of());
        }

        private static PendingEntry templateReference(int mappingId, List<byte[]> variableBytes) {
            return new PendingEntry(ENTRY_TEMPLATE_REFERENCE, mappingId, null, variableBytes);
        }

        private static PendingEntry staticTemplateReference(int mappingId, List<byte[]> variableBytes) {
            return new PendingEntry(ENTRY_STATIC_TEMPLATE_REFERENCE, mappingId, null, variableBytes);
        }
    }

    private record ExactEntry(byte[] payload, long lastTouched) {
        private ExactEntry touch(long touch) {
            return new ExactEntry(this.payload, touch);
        }
    }

    private record UnsyncedExactSeed(byte[] payload, long lastTouched) {
    }

    private record SeedEntry(byte[] payload, long lastTouched) {
    }

    private record TemplateEntry(int totalLength, List<TemplateSegment> segments, int literalByteCount, long lastTouched) {
        private TemplateEntry touch(long touch) {
            return new TemplateEntry(this.totalLength, this.segments, this.literalByteCount, touch);
        }
    }

    private record TemplateSegment(boolean variable, int length, byte[] literalBytes) {
        private static TemplateSegment variable(int length) {
            return new TemplateSegment(true, length, null);
        }

        private static TemplateSegment literal(byte[] literalBytes) {
            return new TemplateSegment(false, literalBytes.length, literalBytes);
        }
    }

    private record TemplateCandidate(TemplateEntry templateEntry, TemplateKey key, List<byte[]> variableBytes) {
        private int referenceEncodedBytes(int mappingId) {
            return BatchAlgorithmSupport.varIntSize(ENTRY_TEMPLATE_REFERENCE)
                    + BatchAlgorithmSupport.varIntSize(Math.max(0, mappingId))
                    + this.variableBytes.stream().mapToInt(bytes -> bytes.length).sum();
        }
    }

    private record TemplateMatch(int entryType, int mappingId, List<byte[]> variableBytes, int encodedBytes) {
    }

    private record EvictionCandidate(int kind, int mappingId, long lastTouched, int storedBytes) {
    }

    private record Range(int start, int length) {
        private int endExclusive() {
            return this.start + this.length;
        }
    }

    private record HashKey(byte[] bytes) {
        private HashKey(byte[] bytes) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof HashKey other && Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.bytes);
        }
    }

    private record TemplateKey(byte[] bytes) {
        private static TemplateKey from(TemplateEntry entry) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(entry.totalLength());
                buffer.writeVarInt(entry.segments().size());
                for (TemplateSegment segment : entry.segments()) {
                    buffer.writeBoolean(segment.variable());
                    buffer.writeVarInt(segment.length());
                    if (!segment.variable()) {
                        buffer.writeBytes(segment.literalBytes());
                    }
                }
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(0, bytes);
                return new TemplateKey(bytes);
            } finally {
                buffer.release();
            }
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof TemplateKey other && Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.bytes);
        }
    }

    private record StaticTemplate(int id, TemplateEntry entry) {
    }

    private static StaticTemplate staticTemplate(int id, int totalLength, TemplateSegment... segments) {
        List<TemplateSegment> segmentList = List.of(segments);
        int literalByteCount = segmentList.stream()
                .filter(segment -> !segment.variable())
                .mapToInt(TemplateSegment::length)
                .sum();
        return new StaticTemplate(id, new TemplateEntry(totalLength, segmentList, literalByteCount, 0L));
    }

    private static Map<Integer, List<StaticTemplate>> buildStaticTemplatesByLength() {
        Map<Integer, List<StaticTemplate>> templatesByLength = new HashMap<>();
        for (StaticTemplate staticTemplate : STATIC_TEMPLATES) {
            templatesByLength.computeIfAbsent(staticTemplate.entry().totalLength(), ignored -> new ArrayList<>()).add(staticTemplate);
        }
        return Map.copyOf(templatesByLength);
    }

    private static StaticTemplate staticTemplateById(int id) {
        for (StaticTemplate staticTemplate : STATIC_TEMPLATES) {
            if (staticTemplate.id() == id) {
                return staticTemplate;
            }
        }
        return null;
    }

    private static byte[] hex(String value) {
        int length = value.length();
        if ((length & 1) != 0) {
            throw new IllegalArgumentException("Hex string length must be even");
        }
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return bytes;
    }
}
