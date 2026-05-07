package com.PinkCats.bandwidthoptimizer.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;

public final class ChannelSimulate {

    private static final int DEFAULT_VALUE = 123456789;
    private static final int DEFAULT_COMPRESSION_THRESHOLD = 1;
    private static final int FIXED_SECOND_VALUE = 7;

    private ChannelSimulate() {
    }

    // Runs the simulator from the command line so the full encode/compress/decode/handle path can be tested without launching two Minecraft clients.
    public static void main(String[] args) {
        SimulationResult result = runDefaultSimulation();
        printResult(result);
    }

    // Runs the current built-in simulation parameters so the class can be started directly from the IDE or Gradle without extra arguments.
    public static SimulationResult runDefaultSimulation() {
        return simulateClientboundPlayIntRoundTrip(DEFAULT_VALUE, DEFAULT_COMPRESSION_THRESHOLD);
    }

    // Executes one clientbound PLAY round trip using vanilla packet serialization, vanilla compression, vanilla framing and vanilla packet reconstruction.
    public static SimulationResult simulateClientboundPlayIntRoundTrip(int value, int compressionThreshold) {
        // 阶段0：这里只有 Packet 对象。
        // NEB/旧版主桩就在这一层：Connection.send(...) / sendPacket(...)。
        ClientboundSetChunkCacheCenterPacket outboundPacket = createTestPacket(value);

        // 阶段1：原版单包编码完成 = packet id + 原始包体。
        // 这是理想的新发送主桩位置：PacketEncoder 之后，压缩之前。
        EncodedPacket encodedPacket = encodeClientboundPlayPacket(outboundPacket);

        // 阶段2：Netty 出站字节层。
        // 这里会经过压缩和长度前缀，最终得到 wireBytes。
        // 旧版 wire 监控抓的就是这一层。
        // 正版加密不在这个函数里模拟；真实游戏里它在这一层之后、真正写 socket 之前。
        // 也就是大致在：PacketEncoder -> 压缩 -> 长度前缀 -> 加密 -> socket。
        OutboundWireSnapshot outboundWireSnapshot = runOutboundWirePipeline(encodedPacket.encodedBytes(), compressionThreshold);

        // 阶段3：Netty 入站字节层回拆。
        // 这里会解帧、解压，恢复出原始编码包字节。
        // 这是理想的新接收主桩位置：splitter/decompress 之后，PacketDecoder 之前。
        // 正版解密在真实游戏里发生在这一层之前、splitter 之前。
        // 也就是大致在：socket -> 解密 -> 长度拆帧 -> 解压 -> PacketDecoder。
        byte[] decompressedPacketBytes = runInboundWirePipeline(outboundWireSnapshot.wireBytes(), compressionThreshold);

        // 阶段4：原版按 packet id + 包体重建 Packet 对象。
        Packet<ClientGamePacketListener> decodedPacket = decodeClientboundPlayPacket(decompressedPacketBytes);

        // 阶段5：单机语义校验。
        // 真实游戏里下一步会继续进 Connection.channelRead0(...) -> packet.handle(listener)。
        int observedValue = readDecodedPacketValue(decodedPacket);

        return new SimulationResult(
                outboundPacket.getClass().getName(),
                encodedPacket.packetId(),
                value,
                compressionThreshold,
                encodedPacket.encodedBytes(),
                outboundWireSnapshot.compressedPayloadBytes(),
                outboundWireSnapshot.wireBytes(),
                decompressedPacketBytes,
                decodedPacket.getClass().getName(),
                observedValue
        );
    }

    // Creates a simple vanilla packet whose x field carries the test integer while the z field stays constant for stable comparisons.
    private static ClientboundSetChunkCacheCenterPacket createTestPacket(int value) {
        return new ClientboundSetChunkCacheCenterPacket(value, FIXED_SECOND_VALUE);
    }

    // Serializes a clientbound PLAY packet through the same packet id lookup and packet body write methods used by vanilla.
    private static EncodedPacket encodeClientboundPlayPacket(Packet<? super ClientGamePacketListener> packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            int packetId = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
            if (packetId < 0) {
                throw new IllegalStateException("Packet is not registered in the client bound PLAY protocol: " + packet.getClass().getName());
            }

            buffer.writeVarInt(packetId);
            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> typedPacket = (Packet<ClientGamePacketListener>) packet;
            typedPacket.write(buffer);
            return new EncodedPacket(packetId, copyBytes(buffer));
        } finally {
            buffer.release();
        }
    }

    // Reconstructs a clientbound PLAY packet from its original encoded bytes through the vanilla protocol packet factory.
    private static Packet<ClientGamePacketListener> decodeClientboundPlayPacket(byte[] encodedPacketBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedPacketBytes));
        try {
            int packetId = buffer.readVarInt();
            Packet<?> packet = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, packetId, buffer);
            if (packet == null) {
                throw new IllegalStateException("Vanilla decode returned null for clientbound PLAY packet id " + packetId);
            }
            if (buffer.readableBytes() != 0) {
                throw new IllegalStateException("Vanilla decode left unread bytes for packet id " + packetId + ": " + buffer.readableBytes());
            }

            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> typedPacket = (Packet<ClientGamePacketListener>) packet;
            return typedPacket;
        } finally {
            buffer.release();
        }
    }

    // Pushes one encoded vanilla packet through the original compression and frame handlers and captures both the compressed payload and final wire bytes.
    private static OutboundWireSnapshot runOutboundWirePipeline(byte[] encodedPacketBytes, int compressionThreshold) {
        OutboundByteCaptureHandler compressedPayloadCapture = new OutboundByteCaptureHandler();
        EmbeddedChannel outboundChannel = new EmbeddedChannel(
                new Varint21LengthFieldPrepender(),
                compressedPayloadCapture,
                new CompressionEncoder(compressionThreshold)
        );

        try {
            if (!outboundChannel.writeOutbound(Unpooled.wrappedBuffer(encodedPacketBytes))) {
                throw new IllegalStateException("Outbound wire pipeline did not produce a framed packet.");
            }

            ByteBuf wireFrame = outboundChannel.readOutbound();
            if (wireFrame == null) {
                throw new IllegalStateException("Outbound wire pipeline returned a null framed packet.");
            }

            try {
                return new OutboundWireSnapshot(
                        compressedPayloadCapture.capturedBytes(),
                        copyBytes(wireFrame)
                );
            } finally {
                wireFrame.release();
            }
        } finally {
            outboundChannel.finishAndReleaseAll();
        }
    }

    // Pushes wire bytes through the original frame splitter and decompressor so the next step receives the exact packet bytes that vanilla would hand to packet decode.
    private static byte[] runInboundWirePipeline(byte[] wireBytes, int compressionThreshold) {
        InboundByteCaptureHandler decompressedPacketCapture = new InboundByteCaptureHandler();
        EmbeddedChannel inboundChannel = new EmbeddedChannel(
                new Varint21FrameDecoder(),
                new CompressionDecoder(compressionThreshold, true),
                decompressedPacketCapture
        );

        try {
            if (!inboundChannel.writeInbound(Unpooled.wrappedBuffer(wireBytes))) {
                throw new IllegalStateException("Inbound wire pipeline did not produce a decompressed packet frame.");
            }

            ByteBuf decompressedPacket = inboundChannel.readInbound();
            if (decompressedPacket == null) {
                throw new IllegalStateException("Inbound wire pipeline returned a null decompressed packet frame.");
            }

            try {
                byte[] capturedBytes = decompressedPacketCapture.capturedBytes();
                if (capturedBytes.length == 0) {
                    throw new IllegalStateException("Inbound capture handler did not observe the decompressed packet frame.");
                }
                return capturedBytes;
            } finally {
                decompressedPacket.release();
            }
        } finally {
            inboundChannel.finishAndReleaseAll();
        }
    }


    // Reads the semantic test value back from the vanilla-decoded packet object without pulling in the full game listener/bootstrap graph.
    private static int readDecodedPacketValue(Packet<ClientGamePacketListener> packet) {
        if (packet instanceof ClientboundSetChunkCacheCenterPacket chunkCacheCenterPacket) {
            return chunkCacheCenterPacket.getX();
        }
        throw new IllegalStateException("Unsupported decoded packet type: " + packet.getClass().getName());
    }

    // Prints every byte checkpoint so later transparent transport work can compare semantic equality and on-wire differences against the vanilla path.
    private static void printResult(SimulationResult result) {
        System.out.println("=== ChannelSimulate ===");
        System.out.println("Packet class: " + result.outboundPacketClass());
        System.out.println("Original int: " + result.originalValue());
        System.out.println("Compression threshold: " + result.compressionThreshold());
        System.out.println("Packet id: " + result.packetId());
        System.out.println("Encoded packet bytes: " + result.encodedPacketBytes().length);
        System.out.println(ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(result.encodedPacketBytes())));
        System.out.println("Compressed payload bytes: " + result.compressedPayloadBytes().length);
        System.out.println(ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(result.compressedPayloadBytes())));
        System.out.println("Wire bytes: " + result.wireBytes().length);
        System.out.println(ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(result.wireBytes())));
        System.out.println("Decompressed packet bytes: " + result.decompressedPacketBytes().length);
        System.out.println(ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(result.decompressedPacketBytes())));
        System.out.println("Decoded packet class: " + result.decodedPacketClass());
        System.out.println("Observed int: " + result.observedValue());
        System.out.println("Semantic match: " + (result.originalValue() == result.observedValue()));
    }

    // Copies readable bytes out of a ByteBuf so intermediate pipeline snapshots stay valid after Netty releases the original buffers.
    private static byte[] copyBytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    // Captures the outbound bytes between compression and length prefix so the simulator can inspect the compressed payload separately from the final wire frame.
    private static final class OutboundByteCaptureHandler extends ChannelOutboundHandlerAdapter {
        private byte[] capturedBytes = new byte[0];

        @Override
        public void write(ChannelHandlerContext context, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ByteBuf buffer) {
                this.capturedBytes = copyBytes(buffer);
            }
            super.write(context, msg, promise);
        }

        private byte[] capturedBytes() {
            return this.capturedBytes.clone();
        }
    }

    // Captures the inbound bytes after decompression so the simulator can verify the frame handed back to vanilla packet decode is unchanged.
    private static final class InboundByteCaptureHandler extends ChannelInboundHandlerAdapter {
        private byte[] capturedBytes = new byte[0];

        @Override
        public void channelRead(ChannelHandlerContext context, Object msg) throws Exception {
            if (msg instanceof ByteBuf buffer) {
                this.capturedBytes = copyBytes(buffer);
            }
            super.channelRead(context, msg);
        }

        private byte[] capturedBytes() {
            return this.capturedBytes.clone();
        }
    }

    // Stores the encoded packet id and bytes exactly as vanilla serialization produced them before any transport-level compression or framing.
    private record EncodedPacket(int packetId, byte[] encodedBytes) {
    }

    // Stores the outbound transport checkpoints so the simulator can compare pre-frame compressed payload bytes with final wire bytes.
    private record OutboundWireSnapshot(byte[] compressedPayloadBytes, byte[] wireBytes) {
    }

    // Stores every stage of the round trip so later transparent transport experiments can compare byte-level output and final semantics.
    public record SimulationResult(
            String outboundPacketClass,
            int packetId,
            int originalValue,
            int compressionThreshold,
            byte[] encodedPacketBytes,
            byte[] compressedPayloadBytes,
            byte[] wireBytes,
            byte[] decompressedPacketBytes,
            String decodedPacketClass,
            int observedValue
    ) {
    }
}
