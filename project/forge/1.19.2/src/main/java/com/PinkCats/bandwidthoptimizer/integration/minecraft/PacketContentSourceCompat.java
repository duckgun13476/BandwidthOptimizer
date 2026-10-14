package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.core.Registry;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;

import java.util.List;

public final class PacketContentSourceCompat {
    private PacketContentSourceCompat() {}

    public static List<String> resourceKeys(Packet<?> packet) {
        if (packet instanceof ClientboundAddEntityPacket addEntityPacket) {
            return one(Registry.ENTITY_TYPE.getKey(addEntityPacket.getType()));
        }
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdatePacket) {
            return one(Registry.BLOCK.getKey(blockUpdatePacket.getBlockState().getBlock()));
        }
        if (packet instanceof ClientboundSoundPacket soundPacket) {
            return one(Registry.SOUND_EVENT.getKey(soundPacket.getSound()));
        }
        if (packet instanceof ClientboundUpdateRecipesPacket updateRecipesPacket) {
            return updateRecipesPacket.getRecipes().stream().map(recipe -> recipe.getId().toString()).toList();
        }
        if (packet instanceof ClientboundRecipePacket recipePacket) {
            return recipePacket.getRecipes().stream().map(Object::toString).toList();
        }
        return List.of();
    }

    public static com.PinkCats.bandwidthoptimizer.report.EntitySyncDetailCounters.Detail entitySyncDetail(Packet<?> packet, String kind, String type) {
        int max = com.PinkCats.bandwidthoptimizer.report.EntitySyncDetailCounters.MAX_FIELDS;
        java.util.List<String> fields = new java.util.ArrayList<>();
        int count = 0;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket metadata) {
            var items = metadata.getUnpackedData();
            if (items != null) {
                count = items.size();
                for (int i = 0; i < Math.min(count, max); i++) {
                    var item = items.get(i);
                    fields.add(Integer.toString(item.getAccessor().getId()));
                }
            }
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket attributes) {
            var values = attributes.getValues();
            count = values.size();
            for (int i = 0; i < Math.min(count, max); i++) {
                var value = values.get(i);
                var key = Registry.ATTRIBUTE.getKey(value.getAttribute());
                fields.add(key == null ? "<unknown>" : key.toString());
            }
        }
        return new com.PinkCats.bandwidthoptimizer.report.EntitySyncDetailCounters.Detail(kind, type, fields, Math.max(count - max, 0));
    }

    private static List<String> one(Object key) {
        return key == null ? List.of() : List.of(key.toString());
    }
}
