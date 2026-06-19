package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class BlockEntityTypeKeyCompat {
    private BlockEntityTypeKeyCompat() {}

    public static ResourceLocation keyOf(BlockEntityType<?> blockEntityType) {
        Identifier identifier = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType);
        return identifier == null ? null : ResourceLocation.fromIdentifier(identifier);
    }
}
