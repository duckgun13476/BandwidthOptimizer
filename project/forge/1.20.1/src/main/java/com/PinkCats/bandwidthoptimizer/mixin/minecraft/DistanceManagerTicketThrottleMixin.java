package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerTicketThrottleMixin {
    @Unique
    private static final String PROPERTY = "bandwidthoptimizer.chunk.playerTicketThrottleLimit";
    @Unique
    private static final int DEFAULT_LIMIT = 8;
    @Unique
    private static final int MAX_LIMIT = 32;

    // Raise player ticket concurrency after teleport.
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;<init>(Ljava/util/List;Ljava/util/concurrent/Executor;I)V"
            ),
            index = 2
    )
    private int bandwidthoptimizer$adjustPlayerTicketThrottleLimit(int original) {
        return readLimit(original);
    }

    // Keep vanilla ordering while allowing more player chunk work per batch.
    @Unique
    private static int readLimit(int original) {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            return Math.max(original, DEFAULT_LIMIT);
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(original, Math.min(MAX_LIMIT, parsed));
        } catch (NumberFormatException ignored) {
            return Math.max(original, DEFAULT_LIMIT);
        }
    }
}
