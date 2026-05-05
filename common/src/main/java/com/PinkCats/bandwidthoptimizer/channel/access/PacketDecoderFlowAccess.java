package com.PinkCats.bandwidthoptimizer.channel.access;

import net.minecraft.network.protocol.PacketFlow;

public interface PacketDecoderFlowAccess {

    // return MC vanilla PacketDecoder Flow
    PacketFlow bandwidthoptimizer$getPacketFlow();
}
