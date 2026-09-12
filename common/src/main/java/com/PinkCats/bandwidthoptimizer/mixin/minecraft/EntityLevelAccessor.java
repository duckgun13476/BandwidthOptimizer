package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityLevelAccessor {

    @Accessor("level")
    Level bandwidthoptimizer$getLevel();
}
