package com.PinkCats.bandwidthoptimizer.networkprobe;

import io.netty.channel.Channel;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;

/** Exact-channel OS metrics via public epoll API or the guarded Windows socket adapter. */
public final class TcpSocketMetrics {
    private TcpSocketMetrics() {}
    public static JsonObject sample(Channel channel) {
        JsonObject result=new JsonObject();
        result.addProperty("scope","local_socket_only; proxy_other_sender_unobserved");
        result.addProperty("packet_loss_rate","unknown");
        if (!channel.isActive()) { result.addProperty("status","closed"); return result; }
        if (WindowsTcpInfo.applicable() && channel instanceof io.netty.channel.socket.nio.NioSocketChannel) {
            JsonObject windows=WindowsTcpInfo.sample(channel);
            for(var entry:windows.entrySet()) result.add(entry.getKey(),entry.getValue());
            return result;
        }
        if (!channel.getClass().getName().equals("io.netty.channel.epoll.EpollSocketChannel")) {
            result.addProperty("status","unsupported");
            result.addProperty("reason","safe_socket_tcp_info_api_unavailable");
            return result;
        }
        try {
            Object info=channel.getClass().getMethod("tcpInfo").invoke(channel);
            result.addProperty("status","supported");
            read(info,result,"rtt","rtt_us");
            read(info,result,"rttvar","rtt_variance_us");
            read(info,result,"rto","retransmission_timeout_us");
            read(info,result,"rcvSpace","receive_space_bytes");
            read(info,result,"sndMss","send_mss_bytes");
            read(info,result,"sndCwnd","send_congestion_window_segments");
            read(info,result,"unacked","unacked_segments");
            read(info,result,"retrans","currently_retransmitted_segments");
            read(info,result,"totalRetrans","total_retransmitted_segments");
            LinuxTcpInfo.append(channel,result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            result.addProperty("status","unavailable");
            result.addProperty("reason",failure.getClass().getSimpleName());
        }
        return result;
    }
    private static void read(Object info, JsonObject output, String method, String field) throws ReflectiveOperationException {
        Method accessor=info.getClass().getMethod(method);
        output.addProperty(field,(Number)accessor.invoke(info));
    }
}
