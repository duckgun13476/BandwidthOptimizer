package com.PinkCats.bandwidthoptimizer.report;

import java.util.HashMap;
import java.util.Map;

/** Channel-local identities observed from the native outbound stream, never world state. */
final class EntitySyncSourceTracker {
    static final int MAX_ENTITIES = 16_384;
    private final Map<Integer, String> types = new HashMap<>();
    private int playerId = -1;

    synchronized String observe(String name, String spawnType, byte[] bytes) {
        VarInt packetId = read(bytes, 0);
        int offset = packetId.next;
        if ("ClientboundLoginPacket".equals(name)) {
            types.clear();
            playerId = packetId.valid && bytes.length - offset >= 4
                    ? ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                    | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255) : -1;
            put(playerId, "minecraft:player");
        } else if ("ClientboundRespawnPacket".equals(name) || "ClientboundStartConfigurationPacket".equals(name)) {
            types.clear();
            put(playerId, "minecraft:player");
        } else if ("ClientboundAddEntityPacket".equals(name) || "ClientboundAddPlayerPacket".equals(name)) {
            VarInt id = read(bytes, offset);
            if (packetId.valid && id.valid) put(id.value, "ClientboundAddPlayerPacket".equals(name) ? "minecraft:player" : spawnType);
        } else if ("ClientboundRemoveEntitiesPacket".equals(name)) {
            VarInt count = read(bytes, offset);
            if (!packetId.valid || !count.valid || count.value < 0 || count.value > MAX_ENTITIES) types.clear();
            else {
                offset = count.next;
                for (int i = 0; i < count.value; i++) {
                    VarInt id = read(bytes, offset);
                    if (!id.valid) { types.clear(); break; }
                    types.remove(id.value);
                    offset = id.next;
                }
            }
        }
        if (!"ClientboundSetEntityDataPacket".equals(name) && !"ClientboundUpdateAttributesPacket".equals(name)) return "<unknown>";
        VarInt id = read(bytes, packetId.next);
        return packetId.valid && id.valid ? types.getOrDefault(id.value, "<unknown>") : "<unknown>";
    }

    private void put(int id, String type) {
        if (id < 0) return;
        if (types.size() >= MAX_ENTITIES && !types.containsKey(id)) types.clear();
        if (type == null) types.remove(id);
        else types.put(id, type);
    }
    private record VarInt(int value, int next, boolean valid) {}
    private static VarInt read(byte[] bytes, int offset) {
        if (bytes == null || offset < 0 || offset >= bytes.length) return new VarInt(-1, offset, false);
        int value = 0;
        for (int i = 0; i < 5 && offset + i < bytes.length; i++) {
            int current = bytes[offset + i] & 255;
            if (i == 4 && (current & 240) != 0) return new VarInt(-1, offset, false);
            value |= (current & 127) << (7 * i);
            if ((current & 128) == 0) return new VarInt(value, offset + i + 1, true);
        }
        return new VarInt(-1, offset, false);
    }
}
