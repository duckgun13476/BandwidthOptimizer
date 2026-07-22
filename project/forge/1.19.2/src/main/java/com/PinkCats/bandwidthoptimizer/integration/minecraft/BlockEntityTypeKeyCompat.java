package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class BlockEntityTypeKeyCompat {
    private BlockEntityTypeKeyCompat() {}

    public static ResourceLocation keyOf(BlockEntityType<?> blockEntityType) {
        return Registry.BLOCK_ENTITY_TYPE.getKey(blockEntityType);
    }
}
