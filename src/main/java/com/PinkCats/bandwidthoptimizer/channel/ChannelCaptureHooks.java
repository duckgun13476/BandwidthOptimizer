package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

import java.util.concurrent.atomic.AtomicReference;

// 这个类是“通道主桩函数入口”。
// mixin 只负责把时机接进来，真正的抓包逻辑都放在这里，后面透明通用层也会从这里继续扩展。
public final class ChannelCaptureHooks {

    // 保存最近一次出站编码包快照，方便后续调试和继续接传输逻辑。
    private static final AtomicReference<ChannelCapturedFrame> LAST_OUTBOUND_FRAME = new AtomicReference<>();
    // 保存最近一次入站待解码包快照，方便后续调试和继续接传输逻辑。
    private static final AtomicReference<ChannelCapturedFrame> LAST_INBOUND_FRAME = new AtomicReference<>();

    // 工具类不允许实例化。
    private ChannelCaptureHooks() {
    }

    // 这个函数在出站方向使用。
    // 作用是在原版 PacketEncoder 写完一个包之后，把这一包刚生成的编码字节保存下来。
    public static void captureOutboundEncodedPacket(ChannelHandlerContext context, Packet<?> packet, ByteBuf encodedBuffer, int startIndexInclusive) {
        if (context == null || packet == null || encodedBuffer == null) {
            return;
        }

        int endIndexExclusive = encodedBuffer.writerIndex();
        if (endIndexExclusive <= startIndexInclusive) {
            return;
        }

        byte[] encodedBytes = copyBytes(encodedBuffer, startIndexInclusive, endIndexExclusive);
        LAST_OUTBOUND_FRAME.set(new ChannelCapturedFrame(
                "OUTBOUND",
                readProtocolName(context),
                packet.getClass().getName(),
                tryReadLeadingVarInt(encodedBytes),
                encodedBytes.length,
                encodedBytes,
                System.currentTimeMillis()
        ));
    }

    // 这个函数在入站方向使用。
    // 作用是在原版 PacketDecoder 还没把字节还原成 Packet 对象之前，先保存当前待解码字节。
    public static void captureInboundPreDecode(ChannelHandlerContext context, ByteBuf encodedBuffer) {
        if (context == null || encodedBuffer == null || !encodedBuffer.isReadable()) {
            return;
        }

        byte[] encodedBytes = copyBytes(encodedBuffer, encodedBuffer.readerIndex(), encodedBuffer.writerIndex());
        LAST_INBOUND_FRAME.set(new ChannelCapturedFrame(
                "INBOUND",
                readProtocolName(context),
                "<pre-decode>",
                tryReadLeadingVarInt(encodedBytes),
                encodedBytes.length,
                encodedBytes,
                System.currentTimeMillis()
        ));
    }

    // 这个函数返回最近一次出站快照，给调试和后续传输层使用。
    public static ChannelCapturedFrame lastOutboundFrame() {
        return LAST_OUTBOUND_FRAME.get();
    }

    // 这个函数返回最近一次入站快照，给调试和后续传输层使用。
    public static ChannelCapturedFrame lastInboundFrame() {
        return LAST_INBOUND_FRAME.get();
    }

    // 这个函数清空当前保存的出站和入站快照，方便测试从空状态开始。
    public static void clearCapturedFrames() {
        LAST_OUTBOUND_FRAME.set(null);
        LAST_INBOUND_FRAME.set(null);
    }

    // 这个函数把 ByteBuf 指定范围复制成独立 byte[]，避免后面 Netty 复用或释放缓冲区影响我们。
    private static byte[] copyBytes(ByteBuf buffer, int startIndexInclusive, int endIndexExclusive) {
        int length = Math.max(endIndexExclusive - startIndexInclusive, 0);
        byte[] bytes = new byte[length];
        if (length > 0) {
            buffer.getBytes(startIndexInclusive, bytes);
        }
        return bytes;
    }

    // 这个函数读取当前连接上的协议名，方便知道这份快照属于哪个协议阶段。
    private static String readProtocolName(ChannelHandlerContext context) {
        Object protocol = context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol == null ? "null" : String.valueOf(protocol);
    }

    // 这个函数尝试从编码包字节开头读出 packet id。
    // 如果字节不完整，或者前面的 VarInt 读不出来，就返回 -1。
    private static int tryReadLeadingVarInt(byte[] encodedBytes) {
        int value = 0;
        int position = 0;
        for (int index = 0; index < encodedBytes.length && index < 5; index++) {
            int current = encodedBytes[index] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        return -1;
    }
}
