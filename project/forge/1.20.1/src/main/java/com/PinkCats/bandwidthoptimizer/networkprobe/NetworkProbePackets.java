package com.PinkCats.bandwidthoptimizer.networkprobe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.Packet;
import io.netty.channel.*;
import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundCustomPayloadPacketAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ServerboundCustomPayloadPacketAccessor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** Only native PLAY framing; diagnostic data bypasses BO optimization, not MC encryption. */
public final class NetworkProbePackets {
    public static final ResourceLocation ID=ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID,"network_probe_v3");
    private static boolean registered;
    private NetworkProbePackets() {}
    private static SimpleChannel registration;
    public static synchronized void register() {
        if(registered) return; registered=true;
        registration=NetworkRegistry.newSimpleChannel(ID,()->"3",NetworkRegistry.acceptMissingOr("3"),NetworkRegistry.acceptMissingOr("3"));
    }
    public static boolean canSendClient(net.minecraft.network.Connection connection) { return registration!=null && registration.isRemotePresent(connection); }
    public static boolean canSend(ServerPlayer player) { return registration!=null && registration.isRemotePresent(player.connection.connection); }
    public static boolean isProbe(Object packet) { return packet instanceof ClientboundCustomPayloadPacket c && ID.equals(c.getIdentifier())
                || packet instanceof ServerboundCustomPayloadPacket s && ID.equals(s.getIdentifier()); }
    public static ChannelFuture send(Channel channel,boolean serverSide,byte[] bytes) {
        if(bytes.length>ProbeFrame.MAX_BYTES) throw new IllegalArgumentException("Probe too large");
        FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        Packet<?> packet=serverSide?new ClientboundCustomPayloadPacket(ID,buffer):new ServerboundCustomPayloadPacket(ID,buffer);
        try { return channel.writeAndFlush(packet).addListener(f -> buffer.release()); }
        catch(RuntimeException failure) { buffer.release(); throw failure; }
    }
    public static void consume(ChannelHandlerContext context,List<Object> out,int start) {
        for(int i=start;i<out.size();) {
            Object packet=out.get(i);
            if(packet instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket)
                NetworkProbeService.closed(context.channel(),"backend_login_replaced_session");
            if(!isProbe(packet)) { i++; continue; }
            out.remove(i);
            boolean serverSide=packet instanceof ServerboundCustomPayloadPacket;
            FriendlyByteBuf buffer=serverSide
                    ?((ServerboundCustomPayloadPacketAccessor)packet).bandwidthoptimizer$data()
                    :((ClientboundCustomPayloadPacketAccessor)packet).bandwidthoptimizer$data();
            try {
                if(buffer.readableBytes()>ProbeFrame.MAX_BYTES) { NetworkProbeService.closed(context.channel(),"oversized_probe_frame"); continue; }
                byte[] bytes=new byte[buffer.readableBytes()];
                buffer.getBytes(buffer.readerIndex(),bytes);
                NetworkProbeService.receive(context.channel(),serverSide,bytes);
            } finally { buffer.release(); }
        }
    }
}
