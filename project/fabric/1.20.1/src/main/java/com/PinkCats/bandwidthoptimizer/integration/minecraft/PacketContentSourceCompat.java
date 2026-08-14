package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.core.registries.BuiltInRegistries;
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
            return one(BuiltInRegistries.ENTITY_TYPE.getKey(addEntityPacket.getType()));
        }
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdatePacket) {
            return one(BuiltInRegistries.BLOCK.getKey(blockUpdatePacket.getBlockState().getBlock()));
        }
        if (packet instanceof ClientboundSoundPacket soundPacket) {
            return one(BuiltInRegistries.SOUND_EVENT.getKey(soundPacket.getSound().value()));
        }
        if (packet instanceof ClientboundUpdateRecipesPacket updateRecipesPacket) {
            return updateRecipesPacket.getRecipes().stream().map(recipe -> recipe.getId().toString()).toList();
        }
        if (packet instanceof ClientboundRecipePacket recipePacket) {
            return recipePacket.getRecipes().stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private static List<String> one(Object key) {
        return key == null ? List.of() : List.of(key.toString());
    }
}
