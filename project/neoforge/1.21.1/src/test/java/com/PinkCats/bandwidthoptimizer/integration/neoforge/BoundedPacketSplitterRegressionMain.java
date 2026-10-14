package com.PinkCats.bandwidthoptimizer.integration.neoforge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.ProtocolInfoBuilder;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.connection.ConnectionType;

public final class BoundedPacketSplitterRegressionMain {
    public static void main(String[] args) throws Exception {
        var decorate = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY,ConnectionType.OTHER);
        var nativeCodec = new ProtocolInfoBuilder<ClientGamePacketListener,RegistryFriendlyByteBuf>(ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)
                .addPacket(GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION,ClientboundSetEntityMotionPacket.STREAM_CODEC).build(decorate).codec();
        for(int id:new int[]{Integer.MIN_VALUE,0,Integer.MAX_VALUE}) {
            var packet = new ClientboundSetEntityMotionPacket(id,new Vec3(-3.9,3.9,0));
            check(BoundedPacketSplitterCompat.canSkip(packet,nativeCodec),"native scalar codec");
            ByteBuf out = Unpooled.buffer();
            try { nativeCodec.encode(out,packet); check(out.readableBytes()<=16,"all ID and velocity bounds"); }
            finally {out.release();}
            var custom = new ProtocolInfoBuilder<ClientGamePacketListener,RegistryFriendlyByteBuf>(ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)
                    .addPacket(GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION,StreamCodec.of((buffer,value)->buffer.writeZero(3*1024*1024),ClientboundSetEntityMotionPacket.STREAM_CODEC::decode)).build(decorate);
            check(!BoundedPacketSplitterCompat.canSkip(packet,custom.codec()),"substituted serializer");
            check(!BoundedPacketSplitterCompat.hasProvenNativeCodec(packet.getClass(), StreamCodec.ofMember(
                    (ClientboundSetEntityMotionPacket value,ByteBuf buffer)->buffer.writeZero(3*1024*1024),
                    buffer->packet)),"static codec replacement call sites");
            var decorated = new ProtocolInfoBuilder<ClientGamePacketListener,RegistryFriendlyByteBuf>(ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)
                    .addPacket(GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION,ClientboundSetEntityMotionPacket.STREAM_CODEC)
                    .build(buffer->{buffer.writeZero(32);return decorate.apply(buffer);});
            check(!BoundedPacketSplitterCompat.canSkip(packet,decorated.codec()),"decorator side effects");
        }
        check(!BoundedPacketSplitterCompat.canSkip(new Object(),nativeCodec),"unknown packet");
        check(!BoundedPacketSplitterCompat.canSkip(null,nativeCodec),"null packet");
        System.out.println("BoundedPacketSplitterRegressionMain PASS");
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
