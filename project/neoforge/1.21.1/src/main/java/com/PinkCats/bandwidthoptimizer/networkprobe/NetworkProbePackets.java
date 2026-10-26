package com.PinkCats.bandwidthoptimizer.networkprobe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.Packet;
import io.netty.channel.*;
import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Only native PLAY framing; diagnostic data bypasses BO optimization, not MC encryption. */
public final class NetworkProbePackets {
    public static final ResourceLocation ID=ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID,"network_probe_v3");
    private static boolean registered;
    private NetworkProbePackets() {}
    public static synchronized void register(IEventBus bus) {
        if(registered) return; registered=true;
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("3").optional()
                .playBidirectional(NetworkProbeBytePayload.TYPE,NetworkProbeBytePayload.STREAM_CODEC,
                        (payload,context)->{}));
    }
    public static boolean canSend(ServerPlayer player) { return NetworkRegistry.hasChannel(player.connection,ID); }
    public static boolean isProbe(Object packet) { return packet instanceof ClientboundCustomPayloadPacket c && c.payload() instanceof NetworkProbeBytePayload
                || packet instanceof ServerboundCustomPayloadPacket s && s.payload() instanceof NetworkProbeBytePayload; }
    public static ChannelFuture send(Channel channel,boolean serverSide,byte[] bytes) {
        if(bytes.length>ProbeFrame.MAX_BYTES) throw new IllegalArgumentException("Probe too large");
        NetworkProbeBytePayload payload=new NetworkProbeBytePayload(bytes);
        return channel.writeAndFlush(serverSide?new ClientboundCustomPayloadPacket(payload):new ServerboundCustomPayloadPacket(payload));
    }
    public static void consume(ChannelHandlerContext context,List<Object> out,int start) {
        for(int i=start;i<out.size();) {
            Object packet=out.get(i);
            if(packet instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket)
                NetworkProbeService.closed(context.channel(),"backend_login_replaced_session");
            if(!isProbe(packet)) { i++; continue; }
            out.remove(i);
            boolean serverSide=packet instanceof ServerboundCustomPayloadPacket;
            NetworkProbeBytePayload payload=serverSide
                    ?(NetworkProbeBytePayload)((ServerboundCustomPayloadPacket)packet).payload()
                    :(NetworkProbeBytePayload)((ClientboundCustomPayloadPacket)packet).payload();
            NetworkProbeService.receive(context.channel(),serverSide,payload.bytes());
        }
    }
}
