package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class BlockEntityTypeKeyCompat {
    private BlockEntityTypeKeyCompat() {}

    public static Identifier keyOf(BlockEntityType<?> blockEntityType) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType);
    }
}
