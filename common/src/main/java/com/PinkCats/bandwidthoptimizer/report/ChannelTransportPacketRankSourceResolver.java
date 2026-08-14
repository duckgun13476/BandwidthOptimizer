package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.BlockEntityTypeKeyCompat;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.PacketContentSourceCompat;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ChannelTransportPacketRankSourceResolver {

    private static final int MAX_TRACKED_ENTITIES = 16_384;
    private static final int MAX_MULTI_SOURCE_NAMESPACES = 32;
    private static final AttributeKey<EntitySourceTable> ENTITY_SOURCES =
            AttributeKey.valueOf("bandwidthoptimizer.report.entity_sources");
    private static final Set<String> ENTITY_REFERENCE_PACKETS = Set.of(
            "ClientboundAnimatePacket",
            "ClientboundDamageEventPacket",
            "ClientboundEntityEventPacket",
            "ClientboundHurtAnimationPacket",
            "ClientboundMoveEntityPacket$Pos",
            "ClientboundMoveEntityPacket$PosRot",
            "ClientboundMoveEntityPacket$Rot",
            "ClientboundRotateHeadPacket",
            "ClientboundSetEntityDataPacket",
            "ClientboundSetEntityLinkPacket",
            "ClientboundSetEntityMotionPacket",
            "ClientboundSetEquipmentPacket",
            "ClientboundSetPassengersPacket",
            "ClientboundTakeItemEntityPacket",
            "ClientboundTeleportEntityPacket",
            "ClientboundUpdateAttributesPacket"
    );
    private ChannelTransportPacketRankSourceResolver() {}

    public static String resolveSourceKey(Packet<?> packet) {
        if (packet == null) {
            return "packet:<unknown>";
        }

        String payloadChannel = CustomPayloadPacketCompat.payloadChannel(packet);
        if (payloadChannel != null && !payloadChannel.isBlank()) {
            return "custom_payload:" + payloadChannel;
        }

        if (packet instanceof ClientboundBlockEntityDataPacket blockEntityDataPacket) {
            return "block_entity:" + resolveBlockEntityTypeKey(blockEntityDataPacket);
        }

        String packetName = simpleClassName(packet.getClass().getName());
        List<String> resourceKeys = PacketContentSourceCompat.resourceKeys(packet);
        String registryKey = switch (packetName) {
            case "ClientboundAddEntityPacket", "ClientboundBlockUpdatePacket", "ClientboundSoundPacket" -> firstKey(resourceKeys);
            default -> null;
        };
        if (registryKey != null) {
            String kind = switch (packetName) {
                case "ClientboundAddEntityPacket" -> "entity";
                case "ClientboundBlockUpdatePacket" -> "block";
                case "ClientboundSoundPacket" -> "sound";
                default -> "registry";
            };
            return kind + ":" + registryKey;
        }

        if ("ClientboundUpdateRecipesPacket".equals(packetName)) {
            return multiSourceKey("recipe_namespaces", resourceKeys);
        }
        if ("ClientboundRecipePacket".equals(packetName)) {
            return multiSourceKey("recipe_namespaces", resourceKeys);
        }
        if ("ClientboundRegistryDataPacket".equals(packetName)) {
            return multiSourceKey("registry_namespaces", resourceKeys);
        }

        return "packet:" + packetName;
    }

    public static String resolveBypassSourceKey(
            ChannelHandlerContext context,
            Packet<?> packet,
            byte[] packetBytes
    ) {
        if (packet == null) {
            return "packet:<unknown>";
        }

        String packetName = simpleClassName(packet.getClass().getName());
        EntitySourceTable entitySources = entitySources(context, false);
        if ("ClientboundAddEntityPacket".equals(packetName)) {
            String detailedKey = resolveSourceKey(packet);
            String sourceKey = compactSourceKey(detailedKey);
            int entityId = readFirstPayloadVarInt(packetBytes);
            if (entityId >= 0) {
                entitySources(context, true).put(entityId, sourceKey);
            }
            return sourceKey;
        }
        if ("ClientboundRemoveEntitiesPacket".equals(packetName)) {
            return removeEntitySources(entitySources, packetBytes);
        }
        if (ENTITY_REFERENCE_PACKETS.contains(packetName)) {
            int entityId = readFirstPayloadVarInt(packetBytes);
            String sourceKey = entitySources == null ? null : entitySources.get(entityId);
            return sourceKey == null ? "packet:" + packetName : sourceKey;
        }
        return compactSourceKey(resolveSourceKey(packet));
    }

    public static String compactSourceKey(String detailedKey) {
        if (detailedKey == null || detailedKey.isBlank()) {
            return "packet:<unknown>";
        }
        if (detailedKey.startsWith("custom_payload:")) {
            return modSourceKey(namespaceOf(detailedKey.substring("custom_payload:".length())));
        }
        for (String prefix : List.of("block_entity:", "entity:", "block:", "sound:")) {
            if (detailedKey.startsWith(prefix)) {
                return modSourceKey(namespaceOf(detailedKey.substring(prefix.length())));
            }
        }
        return detailedKey;
    }

    private static String resolveBlockEntityTypeKey(ClientboundBlockEntityDataPacket packet) {
        BlockEntityType<?> blockEntityType = packet.getType();
        if (blockEntityType == null) {
            return "<unknown>";
        }
        String typeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityType);
        return typeKey == null ? blockEntityType.toString() : typeKey;
    }

    private static String removeEntitySources(EntitySourceTable entitySources, byte[] packetBytes) {
        if (entitySources == null) {
            return "packet:ClientboundRemoveEntitiesPacket";
        }
        VarInt packetId = readVarInt(packetBytes, 0);
        VarInt count = readVarInt(packetBytes, packetId.nextOffset());
        if (!packetId.valid() || !count.valid() || count.value() < 0 || count.value() > MAX_TRACKED_ENTITIES) {
            return "packet:ClientboundRemoveEntitiesPacket";
        }
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        int offset = count.nextOffset();
        for (int index = 0; index < count.value(); index++) {
            VarInt entityId = readVarInt(packetBytes, offset);
            if (!entityId.valid()) {
                break;
            }
            offset = entityId.nextOffset();
            String source = entitySources.remove(entityId.value());
            if (source != null) {
                sources.add(source);
            }
        }
        return combinedCompactSources(sources, "packet:ClientboundRemoveEntitiesPacket");
    }

    private static String combinedCompactSources(Collection<String> compactSources, String fallback) {
        LinkedHashSet<String> namespaces = new LinkedHashSet<>();
        for (String source : compactSources) {
            if (source != null && source.startsWith("mod:")) {
                namespaces.add(source.substring("mod:".length()));
            }
        }
        if (namespaces.isEmpty()) {
            return fallback;
        }
        if (namespaces.size() == 1) {
            return modSourceKey(namespaces.iterator().next());
        }
        List<String> sorted = new ArrayList<>(namespaces);
        Collections.sort(sorted);
        return "mods:" + String.join(",", sorted);
    }

    private static String multiSourceKey(String prefix, Iterable<String> resourceKeys) {
        if (resourceKeys == null) {
            return "packet:<unknown>";
        }
        LinkedHashSet<String> namespaces = new LinkedHashSet<>();
        LinkedHashSet<String> omittedNamespaces = new LinkedHashSet<>();
        for (String resourceKey : resourceKeys) {
            String namespace = namespaceOf(resourceKey);
            if (namespace == null || namespaces.contains(namespace)) {
                continue;
            }
            if (namespaces.size() < MAX_MULTI_SOURCE_NAMESPACES) {
                namespaces.add(namespace);
            } else {
                omittedNamespaces.add(namespace);
            }
        }
        if (namespaces.isEmpty()) {
            return "packet:<unknown>";
        }
        List<String> sorted = new ArrayList<>(namespaces);
        Collections.sort(sorted);
        String suffix = omittedNamespaces.isEmpty() ? "" : ",+" + omittedNamespaces.size();
        return prefix + ":" + String.join(",", sorted) + suffix;
    }

    private static String firstKey(List<String> resourceKeys) {
        return resourceKeys == null || resourceKeys.isEmpty() ? null : resourceKeys.get(0);
    }

    private static EntitySourceTable entitySources(ChannelHandlerContext context, boolean create) {
        Channel channel = context == null ? null : context.channel();
        if (channel == null) {
            return null;
        }
        EntitySourceTable existing = channel.attr(ENTITY_SOURCES).get();
        if (existing != null || !create) {
            return existing;
        }
        EntitySourceTable created = new EntitySourceTable();
        EntitySourceTable raced = channel.attr(ENTITY_SOURCES).setIfAbsent(created);
        return raced == null ? created : raced;
    }

    private static int readFirstPayloadVarInt(byte[] packetBytes) {
        VarInt packetId = readVarInt(packetBytes, 0);
        return packetId.valid() ? readVarInt(packetBytes, packetId.nextOffset()).valueOr(-1) : -1;
    }

    private static VarInt readVarInt(byte[] bytes, int offset) {
        if (bytes == null || offset < 0 || offset >= bytes.length) {
            return VarInt.invalid(offset);
        }
        int value = 0;
        int position = 0;
        for (int index = offset; index < bytes.length && index < offset + 5; index++) {
            int current = bytes[index] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return new VarInt(value, index + 1, true);
            }
            position += 7;
        }
        return VarInt.invalid(offset);
    }

    private static String modSourceKey(String namespace) {
        return "mod:" + (namespace == null ? "<unknown>" : namespace);
    }

    private static String namespaceOf(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            return null;
        }
        String value = resourceKey.trim().toLowerCase(Locale.ROOT);
        int separator = value.indexOf(':');
        return separator <= 0 ? null : value.substring(0, separator);
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "<unknown>";
        }
        int lastDotIndex = className.lastIndexOf('.');
        return lastDotIndex < 0 ? className : className.substring(lastDotIndex + 1);
    }

    private record VarInt(int value, int nextOffset, boolean valid) {
        private static VarInt invalid(int offset) {
            return new VarInt(-1, offset, false);
        }

        private int valueOr(int fallback) {
            return this.valid ? this.value : fallback;
        }
    }

    // Owned by one Netty channel; no mutable world access is needed for movement attribution.
    private static final class EntitySourceTable {
        private final Map<Integer, String> sources = new HashMap<>();

        private void put(int entityId, String sourceKey) {
            if (entityId < 0 || sourceKey == null || !sourceKey.startsWith("mod:")) {
                return;
            }
            if (this.sources.size() >= MAX_TRACKED_ENTITIES && !this.sources.containsKey(entityId)) {
                this.sources.clear();
            }
            this.sources.put(entityId, sourceKey);
        }

        private String get(int entityId) {
            return entityId < 0 ? null : this.sources.get(entityId);
        }

        private String remove(int entityId) {
            return entityId < 0 ? null : this.sources.remove(entityId);
        }
    }
}
