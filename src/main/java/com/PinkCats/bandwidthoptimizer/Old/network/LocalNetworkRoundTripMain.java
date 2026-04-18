package com.PinkCats.bandwidthoptimizer.Old.network;

import com.PinkCats.bandwidthoptimizer.Old.network.attachment.AttachmentDataFlow;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientToServerAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class LocalNetworkRoundTripMain {

    private static final String DEFAULT_PAYLOAD = "local-plain-text";
    private static final int DEFAULT_START_VALUE = 1;

    private LocalNetworkRoundTripMain() {
    }

    public static void main(String[] args) {
        String payload = args.length > 0 ? String.join(" ", args) : DEFAULT_PAYLOAD;
        int startValue = DEFAULT_START_VALUE;
        String correlationId = UUID.randomUUID().toString();
        String serverName = "local-server";
        String clientName = "local-client";

        System.out.println("=== Local Network Round Trip Test ===");
        System.out.println("Plaintext payload: " + payload);
        System.out.println("Plaintext UTF-8 bytes: " + hex(payload.getBytes(StandardCharsets.UTF_8)));

        ServerToClientAttachmentPacket outboundPacket = AttachmentDataFlow.beforeServerSend(
                clientName,
                new ServerToClientAttachmentPacket(
                        correlationId,
                        "local-test",
                        startValue,
                        payload
                )
        );

        byte[] encodedServerToClient = encodeServerToClient(outboundPacket);
        System.out.println("Encoded S2C bytes: " + hex(encodedServerToClient));

        ServerToClientAttachmentPacket decodedClientPacket = AttachmentDataFlow.beforeClientHandle(
                decodeServerToClient(encodedServerToClient),
                clientName
        );
        System.out.println("Client decoded plaintext: " + decodedClientPacket.payload());

        ClientToServerAttachmentPacket responsePacket = AttachmentDataFlow.beforeClientSend(
                AttachmentDataFlow.createClientResponse(decodedClientPacket, clientName),
                clientName
        );

        byte[] encodedClientToServer = encodeClientToServer(responsePacket);
        System.out.println("Encoded C2S bytes: " + hex(encodedClientToServer));

        ClientToServerAttachmentPacket decodedServerPacket = AttachmentDataFlow.beforeServerHandle(
                decodeClientToServer(encodedClientToServer),
                serverName
        );

        System.out.println("Server received key: " + decodedServerPacket.key());
        System.out.println("Server received value: " + decodedServerPacket.value());
        System.out.println("Server received plaintext: " + decodedServerPacket.payload());
        System.out.println("=== Test Completed ===");
    }

    private static byte[] encodeServerToClient(ServerToClientAttachmentPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerToClientAttachmentPacket.encode(packet, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static ServerToClientAttachmentPacket decodeServerToClient(byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return ServerToClientAttachmentPacket.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] encodeClientToServer(ClientToServerAttachmentPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientToServerAttachmentPacket.encode(packet, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static ClientToServerAttachmentPacket decodeClientToServer(byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return ClientToServerAttachmentPacket.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
