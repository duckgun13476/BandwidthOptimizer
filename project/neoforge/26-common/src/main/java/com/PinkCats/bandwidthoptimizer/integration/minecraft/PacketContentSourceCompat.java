package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;

import java.util.List;

public final class PacketContentSourceCompat {
    private PacketContentSourceCompat() {}

    public static List<String> resourceKeys(Packet<?> packet) {
        if (packet instanceof ClientboundAddEntityPacket addEntityPacket) {
            return one(BuiltInRegistries.ENTITY_TYPE.getKey(addEntityPacket.getType()));
        }
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdatePacket) {
            return one(BuiltInRegistries.BLOCK.getKey(blockUpdatePacket.getBlockState().getBlock()));
        }
        if (packet instanceof ClientboundSoundPacket soundPacket) {
            return one(BuiltInRegistries.SOUND_EVENT.getKey(soundPacket.getSound().value()));
        }
        if (packet instanceof ClientboundUpdateRecipesPacket updateRecipesPacket) {
            return updateRecipesPacket.itemSets().keySet().stream().map(key -> key.identifier().toString()).toList();
        }
        if (packet instanceof ClientboundRegistryDataPacket registryDataPacket) {
            return registryDataPacket.entries().stream().map(entry -> entry.id().toString()).toList();
        }
        return List.of();
    }

    public static com.PinkCats.bandwidthoptimizer.report.EntitySyncDetailCounters.Detail entitySyncDetail(Packet<?> packet, String kind, String type) {
        int max = com.PinkCats.bandwidthoptimizer.report.EntitySyncDetailCounters.MAX_FIELDS;
        java.util.List<String> fields = new java.util.ArrayList<>();
        int count = 0;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket metadata) {
            var items = metadata.packedItems();
            if (items != null) {
                count = items.size();
                for (int i = 0; i < Math.min(count, max); i++) {
                    var item = items.get(i);
                    fields.add(Integer.toString(item.id()));
                }
            }
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket attributes) {
            var values = attributes.getValues();
            count = values.size();
            for (int i = 0; i < Math.min(count, max); i++) {
                var value = values.get(i);
                var key = BuiltInRegistries.ATTRIBUTE.getKey(value.attribute().value());
                fields.add(key == null ? "<unknown>" : key.toString());
            }
        }
        return new com.PinkCats.bandwidthoptimizer.report.EntitySyncDetailCounters.Detail(kind, type, fields, Math.max(count - max, 0));
    }

    private static List<String> one(Object key) {
        return key == null ? List.of() : List.of(key.toString());
    }
}
