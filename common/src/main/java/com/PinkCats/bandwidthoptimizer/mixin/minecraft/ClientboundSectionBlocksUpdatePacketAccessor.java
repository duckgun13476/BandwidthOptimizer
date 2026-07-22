package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public interface ClientboundSectionBlocksUpdatePacketAccessor {

    // Fix in some server (Arclight Mohist Youer) mapping failed
    @Accessor("sectionPos")
    SectionPos bandwidthoptimizer$getSectionPos();

    @Accessor("positions")
    short[] bandwidthoptimizer$getPositions();

}
