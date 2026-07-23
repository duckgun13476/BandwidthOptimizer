package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class BlockEntityTypeKeyCompat {
    private BlockEntityTypeKeyCompat() {}

    public static String keyOf(BlockEntityType<?> blockEntityType) {
        var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType);
        return key == null ? null : key.toString();
    }
}
