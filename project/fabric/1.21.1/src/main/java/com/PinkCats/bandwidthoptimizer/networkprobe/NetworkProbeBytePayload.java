package com.PinkCats.bandwidthoptimizer.networkprobe;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record NetworkProbeBytePayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<NetworkProbeBytePayload> TYPE=new Type<>(NetworkProbePackets.ID);
    public static final StreamCodec<RegistryFriendlyByteBuf,NetworkProbeBytePayload> STREAM_CODEC=StreamCodec.of(
            (buffer,payload)->buffer.writeByteArray(payload.bytes),
            buffer->new NetworkProbeBytePayload(buffer.readByteArray(ProbeFrame.MAX_BYTES)));
    public NetworkProbeBytePayload {
        if(bytes==null || bytes.length>ProbeFrame.MAX_BYTES) throw new IllegalArgumentException("Probe too large");
        bytes=bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
