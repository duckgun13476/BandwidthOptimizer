package com.PinkCats.bandwidthoptimizer.network.payload;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
public final class PayloadInspectionSupport {

    private PayloadInspectionSupport() {
    }

    public static JsonObject inspectPayload(String packetClass, byte[] payloadBytes) {
        JsonObject object = new JsonObject();
        byte[] safeBytes = payloadBytes == null ? new byte[0] : payloadBytes;
        JsonObject packetSpecific = inspectPacketSpecific(packetClass, safeBytes);
        boolean matchedParser = packetSpecific != null;
        boolean completeParse = isCompleteParse(packetSpecific);

        object.addProperty("matched_parser", matchedParser);
        object.addProperty("complete_parse", completeParse);

        if (packetSpecific != null) {
            object.add("packet_specific", packetSpecific);
        }

        if (shouldEmitDiagnosticAnalysis(packetSpecific)) {
            object.addProperty("printable", toPrintable(safeBytes));

            JsonObject utfLeading = inspectLeadingUtf(safeBytes);
            if (utfLeading != null) {
                object.add("leading_utf", utfLeading);
            }
        }
        return object;
    }

    public static String toPrintable(byte[] payloadBytes) {
        byte[] safeBytes = payloadBytes == null ? new byte[0] : payloadBytes;
        StringBuilder builder = new StringBuilder(safeBytes.length);
        for (byte value : safeBytes) {
            int unsigned = value & 0xFF;
            if (unsigned >= 32 && unsigned <= 126) {
                builder.append((char) unsigned);
            } else {
                builder.append('.');
            }
        }
        return builder.toString();
    }

    public static String hex(byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        StringBuilder builder = new StringBuilder(safeBytes.length * 2);
        for (byte value : safeBytes) {
            builder.append(String.format("%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private static JsonObject inspectPacketSpecific(String packetClass, byte[] payloadBytes) {
        List<ProbeCandidate> candidates = probeCandidates(packetClass, payloadBytes);
        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingInt(ProbeCandidate::score).reversed());
        ProbeCandidate best = candidates.get(0);
        JsonObject object = best.packetSpecific().deepCopy();

        JsonArray probeLog = new JsonArray();
        int limit = Math.min(5, candidates.size());
        for (int i = 0; i < limit; i++) {
            ProbeCandidate candidate = candidates.get(i);
            JsonObject line = new JsonObject();
            line.addProperty("rank", i + 1);
            line.addProperty("kind", candidate.kind());
            line.addProperty("score", candidate.score());
            line.addProperty("remaining_bytes", candidate.remainingBytes());
            line.addProperty("trace", candidate.trace());
            probeLog.add(line);
        }
        object.add("probe_log", probeLog);
        return object;
    }

    private static List<ProbeCandidate> probeCandidates(String packetClass, byte[] payloadBytes) {
        boolean customPayload = packetClass.contains("CustomPayloadPacket");
        int maxDepth = customPayload ? 6 : 5;
        int beamWidth = customPayload ? 18 : 14;

        List<ProbePath> frontier = List.of(new ProbePath(List.of()));
        List<ProbeCandidate> allCandidates = new ArrayList<>();

        ProbeCandidate entityMetadataCandidate = inspectEntityMetadataPayload(payloadBytes);
        if (entityMetadataCandidate != null) {
            allCandidates.add(entityMetadataCandidate);
        }

        ProbeCandidate sectionBlocksCandidate = inspectSectionBlocksStylePayload(payloadBytes);
        if (sectionBlocksCandidate != null) {
            allCandidates.add(sectionBlocksCandidate);
        }

        allCandidates.addAll(inspectAttributeStreamCandidates(payloadBytes));

        for (int depth = 0; depth < maxDepth; depth++) {
            List<ProbeCandidate> expanded = new ArrayList<>();
            for (ProbePath path : frontier) {
                for (FieldReader next : allowedNextReaders(path.readers(), customPayload)) {
                    List<FieldReader> readers = new ArrayList<>(path.readers());
                    readers.add(next);
                    ProbeCandidate candidate = inspectFieldSequence(payloadBytes, readers);
                    if (candidate != null) {
                        expanded.add(candidate);
                    }
                }
            }

            if (expanded.isEmpty()) {
                break;
            }

            expanded.sort(Comparator.comparingInt(ProbeCandidate::score).reversed());
            int limit = Math.min(beamWidth, expanded.size());
            frontier = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                ProbeCandidate candidate = expanded.get(i);
                allCandidates.add(candidate);
                frontier.add(new ProbePath(candidate.readers()));
            }

            if (expanded.get(0).remainingBytes() == 0) {
                break;
            }
        }
        return allCandidates;
    }

    private static List<FieldReader> allowedNextReaders(List<FieldReader> currentReaders, boolean customPayload) {
        if (currentReaders.isEmpty()) {
            return customPayload
                    ? List.of(FieldReader.RESOURCE_LOCATION, FieldReader.UTF, FieldReader.COMPONENT, FieldReader.VAR_INT, FieldReader.VAR_LONG, FieldReader.DOUBLE, FieldReader.FLOAT, FieldReader.NBT, FieldReader.BLOCK_POS, FieldReader.LONG)
                    : List.of(FieldReader.BLOCK_POS, FieldReader.VAR_INT, FieldReader.VAR_LONG, FieldReader.LONG, FieldReader.DOUBLE, FieldReader.FLOAT, FieldReader.NBT, FieldReader.UTF, FieldReader.COMPONENT);
        }

        FieldReader first = currentReaders.get(0);
        if (customPayload && first == FieldReader.RESOURCE_LOCATION) {
            return List.of(FieldReader.RESOURCE_LOCATION, FieldReader.UTF, FieldReader.VAR_INT, FieldReader.VAR_LONG, FieldReader.BOOLEAN, FieldReader.UUID, FieldReader.NBT, FieldReader.BLOCK_POS, FieldReader.COMPONENT, FieldReader.FLOAT, FieldReader.DOUBLE, FieldReader.BYTE);
        }
        if (customPayload && (first == FieldReader.UTF || first == FieldReader.COMPONENT)) {
            return List.of(FieldReader.BOOLEAN, FieldReader.VAR_INT, FieldReader.VAR_LONG, FieldReader.UUID, FieldReader.NBT, FieldReader.RESOURCE_LOCATION, FieldReader.UTF, FieldReader.COMPONENT, FieldReader.FLOAT, FieldReader.DOUBLE, FieldReader.BYTE);
        }
        if (first == FieldReader.BLOCK_POS) {
            return List.of(FieldReader.VAR_INT, FieldReader.VAR_LONG, FieldReader.NBT, FieldReader.BOOLEAN, FieldReader.UTF, FieldReader.FLOAT, FieldReader.DOUBLE, FieldReader.LONG);
        }
        if (first == FieldReader.NBT) {
            return List.of(FieldReader.BLOCK_POS, FieldReader.VAR_INT, FieldReader.VAR_LONG, FieldReader.BOOLEAN, FieldReader.UTF, FieldReader.FLOAT, FieldReader.DOUBLE, FieldReader.LONG);
        }
        return customPayload
                ? List.of(FieldReader.VAR_INT, FieldReader.VAR_LONG, FieldReader.BOOLEAN, FieldReader.UUID, FieldReader.NBT, FieldReader.BLOCK_POS, FieldReader.UTF, FieldReader.COMPONENT, FieldReader.RESOURCE_LOCATION, FieldReader.BYTE, FieldReader.FLOAT, FieldReader.DOUBLE, FieldReader.LONG)
                : List.of(FieldReader.VAR_INT, FieldReader.VAR_LONG, FieldReader.BOOLEAN, FieldReader.NBT, FieldReader.BLOCK_POS, FieldReader.UTF, FieldReader.COMPONENT, FieldReader.UUID, FieldReader.BYTE, FieldReader.FLOAT, FieldReader.DOUBLE, FieldReader.LONG);
    }

    private static ProbeCandidate inspectFieldSequence(byte[] payloadBytes, List<FieldReader> readers) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        try {
            JsonObject packetBody = new JsonObject();
            List<String> trace = new ArrayList<>();
            for (int i = 0; i < readers.size(); i++) {
                FieldReader reader = readers.get(i);
                int before = buffer.readerIndex();
                reader.readInto(buffer, packetBody, "f" + i);
                int consumed = buffer.readerIndex() - before;
                trace.add(reader.kindName() + ":" + consumed);
            }

            JsonObject object = new JsonObject();
            object.addProperty("kind", joinKind(readers));
            object.add("packet_body", packetBody);
            object.add("derived", buildBlockEntityDerivedFields(packetBody));
            object.addProperty("remaining_bytes", buffer.readableBytes());
            int score = scoreCandidate(packetBody, readers, buffer.readableBytes(), payloadBytes.length);
            object.addProperty("probe_score", score);
            return new ProbeCandidate(
                    joinKind(readers),
                    object,
                    score,
                    buffer.readableBytes(),
                    String.join(" -> ", trace),
                    List.copyOf(readers)
            );
        } catch (Exception exception) {
            return null;
        } finally {
            buffer.release();
        }
    }

    private static int scoreCandidate(JsonObject packetBody, List<FieldReader> readers, int remainingBytes, int totalBytes) {
        int score = 0;
        score += remainingBytes == 0 ? 10_000 : Math.max(0, 2_000 - (remainingBytes * 8));
        score += readers.size() * 40;
        score += packetBody.has("resource_location") ? 800 : 0;
        score += packetBody.has("nbt") ? 600 : 0;
        score += packetBody.has("block_pos") ? 500 : 0;
        score += packetBody.has("uuid") ? 300 : 0;
        score += packetBody.has("component_json") ? 250 : 0;
        score += packetBody.has("utf") ? 200 : 0;
        score += packetBody.has("double") ? 120 : 0;
        score += packetBody.has("float") ? 80 : 0;
        score += packetBody.has("long") ? 80 : 0;
        score += packetBody.has("boolean") ? 50 : 0;
        score += packetBody.has("resource_location") && packetBody.get("resource_location").getAsString().contains(":") ? 300 : 0;
        score += packetBody.has("utf") ? printableBonus(packetBody.get("utf").getAsString()) : 0;
        score += packetBody.has("component_json") ? printableBonus(packetBody.get("component_json").getAsString()) : 0;
        score -= packetBody.has("utf") ? controlStringPenalty(packetBody.get("utf").getAsString()) : 0;
        score -= packetBody.has("component_json") ? controlStringPenalty(packetBody.get("component_json").getAsString()) : 0;
        score -= packetBody.has("resource_location") && !packetBody.get("resource_location").getAsString().contains(":") ? 900 : 0;
        score -= packetBody.has("nbt") && "null".equals(packetBody.get("nbt").getAsString()) && remainingBytes > 0 ? 700 : 0;
        score -= !readers.isEmpty() && readers.get(0) == FieldReader.NBT && packetBody.has("nbt") && "null".equals(packetBody.get("nbt").getAsString()) ? 500 : 0;
        score -= totalBytes > 0 && remainingBytes == totalBytes ? 2_000 : 0;
        return score;
    }

    private static int printableBonus(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int printable = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 32 && ch <= 126) {
                printable++;
            }
        }
        return (int) Math.round((printable * 100.0D) / Math.max(1, text.length()));
    }

    private static int controlStringPenalty(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int controlChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 32 && ch != '\r' && ch != '\n' && ch != '\t') {
                controlChars++;
            }
        }
        int penalty = controlChars * 250;
        if (text.length() == 1 && controlChars == 1) {
            penalty += 1_000;
        }
        return penalty;
    }

    private static String joinKind(List<FieldReader> readers) {
        List<String> parts = new ArrayList<>(readers.size());
        for (FieldReader reader : readers) {
            parts.add(reader.kindName());
        }
        return String.join("_", parts);
    }

    private static ProbeCandidate inspectEntityMetadataPayload(byte[] payloadBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        try {
            JsonObject packetBody = new JsonObject();
            int entityId = buffer.readVarInt();
            packetBody.addProperty("entity_id", entityId);

            JsonArray entries = new JsonArray();
            int entryCount = 0;
            while (buffer.isReadable()) {
                int accessorId = buffer.readUnsignedByte();
                if (accessorId == 255) {
                    packetBody.addProperty("terminator", 255);
                    packetBody.addProperty("entry_count", entryCount);

                    JsonObject object = new JsonObject();
                    object.addProperty("kind", "entity_metadata_stream");
                    object.add("packet_body", packetBody);
                    object.add("derived", new JsonObject());
                    object.addProperty("remaining_bytes", buffer.readableBytes());
                    int score = buffer.readableBytes() == 0 ? 16_000 + (entryCount * 35) : 9_000 - (buffer.readableBytes() * 8);
                    object.addProperty("probe_score", score);
                    return new ProbeCandidate(
                            "entity_metadata_stream",
                            object,
                            score,
                            buffer.readableBytes(),
                            "varint -> metadata_entries:" + entryCount,
                            List.of(FieldReader.VAR_INT)
                    );
                }

                JsonObject entry = new JsonObject();
                entry.addProperty("accessor_id", accessorId);

                int serializerId = buffer.readVarInt();
                entry.addProperty("serializer_id", serializerId);
                entry.addProperty("serializer_kind", serializerKindName(serializerId));

                readEntityMetadataValue(buffer, serializerId, entry);
                entries.add(entry);
                entryCount++;
            }
            return null;
        } catch (Exception exception) {
            return null;
        } finally {
            buffer.release();
        }
    }

    private static ProbeCandidate inspectSectionBlocksStylePayload(byte[] payloadBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        try {
            long sectionPosLong = buffer.readLong();
            SectionPos sectionPos = SectionPos.of(sectionPosLong);
            int entryCount = buffer.readVarInt();
            if (entryCount < 0 || entryCount > 4096) {
                return null;
            }

            JsonArray entries = new JsonArray();
            for (int i = 0; i < entryCount; i++) {
                long packed = buffer.readVarLong();
                int stateId = (int) (packed >>> 12);
                int localPos = (int) (packed & 4095L);

                JsonObject entry = new JsonObject();
                entry.addProperty("packed", packed);
                entry.addProperty("state_id", stateId);
                entry.addProperty("local_pos_packed", localPos);
                entry.addProperty("section_x", SectionPos.sectionRelativeX((short) localPos));
                entry.addProperty("section_y", SectionPos.sectionRelativeY((short) localPos));
                entry.addProperty("section_z", SectionPos.sectionRelativeZ((short) localPos));
                entries.add(entry);
            }

            JsonObject packetBody = new JsonObject();
            packetBody.addProperty("section_pos_long", sectionPosLong);
            packetBody.addProperty("section_pos", sectionPos.toString());
            packetBody.addProperty("section_pos_x", sectionPos.x());
            packetBody.addProperty("section_pos_y", sectionPos.y());
            packetBody.addProperty("section_pos_z", sectionPos.z());
            packetBody.addProperty("entry_count", entryCount);
            packetBody.add("entries", entries);

            JsonObject object = new JsonObject();
            object.addProperty("kind", "section_blocks_style");
            object.add("packet_body", packetBody);
            object.add("derived", buildSectionPosDerivedFields(packetBody));
            object.addProperty("remaining_bytes", buffer.readableBytes());
            int score = buffer.readableBytes() == 0 ? 14_000 + Math.min(512, entryCount * 4) : 8_000 - (buffer.readableBytes() * 8);
            object.addProperty("probe_score", score);
            return new ProbeCandidate(
                    "section_blocks_style",
                    object,
                    score,
                    buffer.readableBytes(),
                    "long -> varint -> varlong*" + entryCount,
                    List.of(FieldReader.LONG, FieldReader.VAR_INT, FieldReader.VAR_LONG)
            );
        } catch (Exception exception) {
            return null;
        } finally {
            buffer.release();
        }
    }

    private static List<ProbeCandidate> inspectAttributeStreamCandidates(byte[] payloadBytes) {
        List<ProbeCandidate> candidates = new ArrayList<>(2);
        ProbeCandidate uuidModifiers = inspectAttributeStreamPayload(payloadBytes, true);
        if (uuidModifiers != null) {
            candidates.add(uuidModifiers);
        }
        ProbeCandidate resourceModifiers = inspectAttributeStreamPayload(payloadBytes, false);
        if (resourceModifiers != null) {
            candidates.add(resourceModifiers);
        }
        return candidates;
    }

    private static ProbeCandidate inspectAttributeStreamPayload(byte[] payloadBytes, boolean uuidModifiers) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        try {
            int entityId = buffer.readVarInt();
            int attributeCount = buffer.readVarInt();
            if (attributeCount < 0 || attributeCount > 256) {
                return null;
            }

            JsonArray attributes = new JsonArray();
            for (int i = 0; i < attributeCount; i++) {
                JsonObject attribute = new JsonObject();
                ResourceLocation attributeId = buffer.readResourceLocation();
                attribute.addProperty("attribute_id", attributeId.toString());
                attribute.addProperty("base", buffer.readDouble());

                int modifierCount = buffer.readVarInt();
                if (modifierCount < 0 || modifierCount > 256) {
                    return null;
                }
                attribute.addProperty("modifier_count", modifierCount);

                JsonArray modifiers = new JsonArray();
                for (int j = 0; j < modifierCount; j++) {
                    JsonObject modifier = new JsonObject();
                    if (uuidModifiers) {
                        modifier.addProperty("id_uuid", buffer.readUUID().toString());
                        modifier.addProperty("amount", buffer.readDouble());
                        modifier.addProperty("operation", buffer.readByte());
                    } else {
                        modifier.addProperty("id", buffer.readResourceLocation().toString());
                        modifier.addProperty("amount", buffer.readDouble());
                        modifier.addProperty("operation", buffer.readVarInt());
                    }
                    modifiers.add(modifier);
                }
                attribute.add("modifiers", modifiers);
                attributes.add(attribute);
            }

            JsonObject packetBody = new JsonObject();
            packetBody.addProperty("entity_id", entityId);
            packetBody.addProperty("attribute_count", attributeCount);
            packetBody.addProperty("modifier_id_kind", uuidModifiers ? "uuid" : "resource_location");
            packetBody.add("attributes", attributes);

            JsonObject object = new JsonObject();
            object.addProperty("kind", uuidModifiers ? "attribute_stream_uuid_modifiers" : "attribute_stream_resource_modifiers");
            object.add("packet_body", packetBody);
            object.add("derived", new JsonObject());
            object.addProperty("remaining_bytes", buffer.readableBytes());
            int score = buffer.readableBytes() == 0
                    ? 15_000 + Math.min(1_024, attributeCount * 120) + (uuidModifiers ? 200 : 0)
                    : 8_500 - (buffer.readableBytes() * 8);
            object.addProperty("probe_score", score);
            return new ProbeCandidate(
                    uuidModifiers ? "attribute_stream_uuid_modifiers" : "attribute_stream_resource_modifiers",
                    object,
                    score,
                    buffer.readableBytes(),
                    "varint -> varint -> (resource_location -> double -> varint -> modifiers)*" + attributeCount,
                    List.of(FieldReader.VAR_INT, FieldReader.VAR_INT, FieldReader.RESOURCE_LOCATION, FieldReader.DOUBLE)
            );
        } catch (Exception exception) {
            return null;
        } finally {
            buffer.release();
        }
    }

    private static void readEntityMetadataValue(FriendlyByteBuf buffer, int serializerId, JsonObject entry) {
        switch (serializerId) {
            case 0 -> entry.addProperty("value_byte", buffer.readByte());
            case 1 -> entry.addProperty("value_varint", buffer.readVarInt());
            case 2 -> entry.addProperty("value_varlong", buffer.readVarLong());
            case 3 -> entry.addProperty("value_float", buffer.readFloat());
            case 4 -> entry.addProperty("value_string", buffer.readUtf(32767));
            case 5 -> entry.addProperty("value_component_json", Component.Serializer.toJson(buffer.readComponent()));
            case 6 -> {
                boolean present = buffer.readBoolean();
                entry.addProperty("value_present", present);
                if (present) {
                    entry.addProperty("value_component_json", Component.Serializer.toJson(buffer.readComponent()));
                }
            }
            case 8 -> entry.addProperty("value_boolean", buffer.readBoolean());
            case 10 -> {
                BlockPos blockPos = buffer.readBlockPos();
                addBlockPos(entry, "value", blockPos);
            }
            case 11 -> {
                boolean present = buffer.readBoolean();
                entry.addProperty("value_present", present);
                if (present) {
                    BlockPos blockPos = buffer.readBlockPos();
                    addBlockPos(entry, "value", blockPos);
                }
            }
            case 13 -> {
                boolean present = buffer.readBoolean();
                entry.addProperty("value_present", present);
                if (present) {
                    entry.addProperty("value_uuid", buffer.readUUID().toString());
                }
            }
            case 16 -> {
                CompoundTag nbt = buffer.readNbt();
                entry.addProperty("value_nbt", nbt == null ? "null" : nbt.toString());
            }
            case 20 -> {
                int raw = buffer.readVarInt();
                entry.addProperty("value_optional_unsigned_int_raw", raw);
                entry.addProperty("value_present", raw != 0);
                if (raw != 0) {
                    entry.addProperty("value_optional_unsigned_int", raw - 1);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported serializer id: " + serializerId);
        }
    }

    private static String serializerKindName(int serializerId) {
        return switch (serializerId) {
            case 0 -> "byte";
            case 1 -> "varint";
            case 2 -> "varlong";
            case 3 -> "float";
            case 4 -> "string";
            case 5 -> "component";
            case 6 -> "optional_component";
            case 8 -> "boolean";
            case 10 -> "block_pos";
            case 11 -> "optional_block_pos";
            case 13 -> "optional_uuid";
            case 16 -> "compound_tag";
            case 20 -> "optional_unsigned_int";
            default -> "unknown_" + serializerId;
        };
    }

    private static void addBlockPos(JsonObject object, String prefix, BlockPos blockPos) {
        object.addProperty(prefix + "_block_pos", blockPos.toString());
        object.addProperty(prefix + "_block_pos_x", blockPos.getX());
        object.addProperty(prefix + "_block_pos_y", blockPos.getY());
        object.addProperty(prefix + "_block_pos_z", blockPos.getZ());
    }

    private static JsonObject buildBlockEntityDerivedFields(JsonObject packetBody) {
        JsonObject derived = new JsonObject();
        if (packetBody.has("block_pos_x")) {
            derived.add("x", packetBody.get("block_pos_x"));
        }
        if (packetBody.has("block_pos_y")) {
            derived.add("y", packetBody.get("block_pos_y"));
        }
        if (packetBody.has("block_pos_z")) {
            derived.add("z", packetBody.get("block_pos_z"));
        }
        return derived;
    }

    private static JsonObject buildSectionPosDerivedFields(JsonObject packetBody) {
        JsonObject derived = new JsonObject();
        if (packetBody.has("section_pos_x")) {
            derived.add("section_x", packetBody.get("section_pos_x"));
        }
        if (packetBody.has("section_pos_y")) {
            derived.add("section_y", packetBody.get("section_pos_y"));
        }
        if (packetBody.has("section_pos_z")) {
            derived.add("section_z", packetBody.get("section_pos_z"));
        }
        return derived;
    }

    private static boolean shouldEmitDiagnosticAnalysis(JsonObject packetSpecific) {
        if (packetSpecific == null) {
            return false;
        }
        return packetSpecific.has("remaining_bytes")
                && packetSpecific.get("remaining_bytes").isJsonPrimitive()
                && packetSpecific.get("remaining_bytes").getAsInt() != 0;
    }

    private static boolean isCompleteParse(JsonObject packetSpecific) {
        return packetSpecific != null
                && !packetSpecific.has("parse_error")
                && packetSpecific.has("remaining_bytes")
                && packetSpecific.get("remaining_bytes").isJsonPrimitive()
                && packetSpecific.get("remaining_bytes").getAsInt() == 0;
    }

    private static JsonObject inspectLeadingUtf(byte[] payloadBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        try {
            if (!buffer.isReadable()) {
                return null;
            }

            buffer.markReaderIndex();
            try {
                String leading = buffer.readUtf(32767);
                byte[] remaining = new byte[buffer.readableBytes()];
                buffer.readBytes(remaining);

                JsonObject object = new JsonObject();
                object.addProperty("text", leading);
                object.addProperty("remaining_hex", remaining.length == 0 ? "<empty>" : hex(remaining));
                return object;
            } catch (Exception ignored) {
                buffer.resetReaderIndex();
                return null;
            }
        } finally {
            buffer.release();
        }
    }

    @FunctionalInterface
    private interface BufferFieldReader {
        void read(FriendlyByteBuf buffer, JsonObject packetBody, String fieldKey);
    }

    private enum FieldReader {
        BYTE((buffer, packetBody, fieldKey) -> {
            byte value = buffer.readByte();
            packetBody.addProperty(fieldKey + "_byte", value);
            if (!packetBody.has("byte")) {
                packetBody.addProperty("byte", value);
            }
        }, "byte"),
        BLOCK_POS((buffer, packetBody, fieldKey) -> {
            BlockPos blockPos = buffer.readBlockPos();
            packetBody.addProperty(fieldKey + "_block_pos", blockPos.toString());
            packetBody.addProperty(fieldKey + "_block_pos_x", blockPos.getX());
            packetBody.addProperty(fieldKey + "_block_pos_y", blockPos.getY());
            packetBody.addProperty(fieldKey + "_block_pos_z", blockPos.getZ());
            if (!packetBody.has("block_pos")) {
                packetBody.addProperty("block_pos", blockPos.toString());
                packetBody.addProperty("block_pos_x", blockPos.getX());
                packetBody.addProperty("block_pos_y", blockPos.getY());
                packetBody.addProperty("block_pos_z", blockPos.getZ());
            }
        }, "blockpos"),
        VAR_INT((buffer, packetBody, fieldKey) -> {
            int value = buffer.readVarInt();
            packetBody.addProperty(fieldKey + "_varint", value);
            if (!packetBody.has("varint")) {
                packetBody.addProperty("varint", value);
            }
        }, "varint"),
        VAR_LONG((buffer, packetBody, fieldKey) -> {
            long value = buffer.readVarLong();
            packetBody.addProperty(fieldKey + "_varlong", value);
            if (!packetBody.has("varlong")) {
                packetBody.addProperty("varlong", value);
            }
        }, "varlong"),
        LONG((buffer, packetBody, fieldKey) -> {
            long value = buffer.readLong();
            packetBody.addProperty(fieldKey + "_long", value);
            if (!packetBody.has("long")) {
                packetBody.addProperty("long", value);
            }
        }, "long"),
        FLOAT((buffer, packetBody, fieldKey) -> {
            float value = buffer.readFloat();
            packetBody.addProperty(fieldKey + "_float", value);
            if (!packetBody.has("float")) {
                packetBody.addProperty("float", value);
            }
        }, "float"),
        DOUBLE((buffer, packetBody, fieldKey) -> {
            double value = buffer.readDouble();
            packetBody.addProperty(fieldKey + "_double", value);
            if (!packetBody.has("double")) {
                packetBody.addProperty("double", value);
            }
        }, "double"),
        NBT((buffer, packetBody, fieldKey) -> {
            CompoundTag nbt = buffer.readNbt();
            String text = nbt == null ? "null" : nbt.toString();
            packetBody.addProperty(fieldKey + "_nbt", text);
            if (!packetBody.has("nbt")) {
                packetBody.addProperty("nbt", text);
            }
        }, "nbt"),
        RESOURCE_LOCATION((buffer, packetBody, fieldKey) -> {
            ResourceLocation resourceLocation = buffer.readResourceLocation();
            packetBody.addProperty(fieldKey + "_resource_location", resourceLocation.toString());
            if (!packetBody.has("resource_location")) {
                packetBody.addProperty("resource_location", resourceLocation.toString());
            }
        }, "resource_location"),
        UTF((buffer, packetBody, fieldKey) -> {
            String text = buffer.readUtf(32767);
            packetBody.addProperty(fieldKey + "_utf", text);
            if (!packetBody.has("utf")) {
                packetBody.addProperty("utf", text);
            }
        }, "utf"),
        UUID((buffer, packetBody, fieldKey) -> {
            String text = buffer.readUUID().toString();
            packetBody.addProperty(fieldKey + "_uuid", text);
            if (!packetBody.has("uuid")) {
                packetBody.addProperty("uuid", text);
            }
        }, "uuid"),
        BOOLEAN((buffer, packetBody, fieldKey) -> {
            boolean value = buffer.readBoolean();
            packetBody.addProperty(fieldKey + "_boolean", value);
            if (!packetBody.has("boolean")) {
                packetBody.addProperty("boolean", value);
            }
        }, "boolean"),
        COMPONENT((buffer, packetBody, fieldKey) -> {
            Component component = buffer.readComponent();
            String text = Component.Serializer.toJson(component);
            packetBody.addProperty(fieldKey + "_component_json", text);
            if (!packetBody.has("component_json")) {
                packetBody.addProperty("component_json", text);
            }
        }, "component");

        private final BufferFieldReader reader;
        private final String kindName;

        FieldReader(BufferFieldReader reader, String kindName) {
            this.reader = reader;
            this.kindName = kindName;
        }

        private void readInto(FriendlyByteBuf buffer, JsonObject packetBody, String fieldKey) {
            this.reader.read(buffer, packetBody, fieldKey);
        }

        private String kindName() {
            return this.kindName;
        }
    }

    private record ProbeCandidate(
            String kind,
            JsonObject packetSpecific,
            int score,
            int remainingBytes,
            String trace,
            List<FieldReader> readers
    ) {
    }

    private record ProbePath(
            List<FieldReader> readers
    ) {
    }
}
