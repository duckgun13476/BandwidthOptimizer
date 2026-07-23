package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class ChunkCoordinateCompat {

    private ChunkCoordinateCompat() {}

    public static int x(ChunkPos chunkPos) { return chunkPos.x(); }
    public static int z(ChunkPos chunkPos) { return chunkPos.z(); }
    public static long packedKey(ChunkPos chunkPos) { return chunkPos.pack(); }
    public static long packedKey(int x, int z) { return ChunkPos.pack(x, z); }
    public static String dimensionId(ResourceKey<Level> dimension) { return dimension.identifier().toString(); }
}
