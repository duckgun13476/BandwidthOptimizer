package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.util.ArrayList;
import java.util.List;

public final class ChannelTransportInlineFrameWriter {

    private ChannelTransportInlineFrameWriter() {
    }

    public static boolean writeIndependentPacketBodies(
            ChannelHandlerContext context,
            ByteBuf inlineOutput,
            List<byte[]> payloads,
            PacketBodyEncoder encoder
    ) {
        if (context == null || inlineOutput == null || payloads == null || payloads.isEmpty() || encoder == null) {
            return false;
        }
        if (payloads.size() == 1) {
            return encoder.encode(inlineOutput, payloads.get(0));
        }

        List<ByteBuf> encodedPacketBodies = new ArrayList<>(payloads.size());
        try {
            for (byte[] payload : payloads) {
                ByteBuf packetBody = context.alloc().buffer();
                if (!encoder.encode(packetBody, payload)) {
                    packetBody.release();
                    return false;
                }
                encodedPacketBodies.add(packetBody);
            }

            int lastIndex = encodedPacketBodies.size() - 1;
            for (int index = 0; index < lastIndex; index++) {
                ByteBuf packetBody = encodedPacketBodies.get(index);
                context.write(packetBody).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                encodedPacketBodies.set(index, null);
            }
            ByteBuf finalPacketBody = encodedPacketBodies.get(lastIndex);
            inlineOutput.writeBytes(finalPacketBody, finalPacketBody.readerIndex(), finalPacketBody.readableBytes());
            return true;
        } finally {
            for (ByteBuf packetBody : encodedPacketBodies) {
                if (packetBody != null) {
                    packetBody.release();
                }
            }
        }
    }

    @FunctionalInterface
    public interface PacketBodyEncoder {
        boolean encode(ByteBuf output, byte[] payload);
    }
}
