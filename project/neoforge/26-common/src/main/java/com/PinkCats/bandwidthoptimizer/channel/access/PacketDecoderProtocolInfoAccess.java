package com.PinkCats.bandwidthoptimizer.channel.access;

import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;

public interface PacketDecoderProtocolInfoAccess {

    ProtocolInfo<? extends PacketListener> bandwidthoptimizer$getProtocolInfo();
}
