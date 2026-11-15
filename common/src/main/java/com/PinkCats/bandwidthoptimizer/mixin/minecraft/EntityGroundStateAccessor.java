package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityGroundStateAccessor {
    @Accessor("onGround")
    boolean bandwidthoptimizer$getOnGround();
}
