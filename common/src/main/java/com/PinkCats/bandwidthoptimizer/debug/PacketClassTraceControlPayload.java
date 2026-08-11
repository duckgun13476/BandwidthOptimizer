package com.PinkCats.bandwidthoptimizer.debug;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record PacketClassTraceControlPayload(
        boolean enabled,
        int minutes,
        int maxEventsPerMinute,
        List<String> packetClasses
) {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 32 * 1024;

    public PacketClassTraceControlPayload {
        packetClasses = packetClasses == null ? List.of() : List.copyOf(packetClasses);
    }

    public static PacketClassTraceControlPayload enabled(int minutes, int maxEventsPerMinute, Set<String> classes) {
        return new PacketClassTraceControlPayload(true, minutes, maxEventsPerMinute, List.copyOf(classes));
    }

    public static PacketClassTraceControlPayload disabled() {
        return new PacketClassTraceControlPayload(false, 0, 0, List.of());
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.writeByte(FORMAT_VERSION);
                data.writeBoolean(this.enabled);
                data.writeShort(this.minutes);
                data.writeInt(this.maxEventsPerMinute);
                data.writeByte(this.packetClasses.size());
                for (String packetClass : this.packetClasses) {
                    data.writeUTF(packetClass);
                }
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Packet trace control payload is too large");
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode packet trace control payload", exception);
        }
    }

    public static PacketClassTraceControlPayload fromBytes(byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        if (safeBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Packet trace control payload is too large");
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(safeBytes))) {
            int version = data.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported packet trace control version: " + version);
            }
            boolean enabled = data.readBoolean();
            int minutes = data.readUnsignedShort();
            int maxEventsPerMinute = data.readInt();
            int classCount = data.readUnsignedByte();
            List<String> classes = new ArrayList<>(classCount);
            for (int index = 0; index < classCount; index++) {
                classes.add(data.readUTF());
            }
            if (data.available() != 0) {
                throw new IllegalArgumentException("Trailing packet trace control data");
            }
            if (enabled) {
                Set<String> validated = PacketClassTraceDiagnostic.requireExactClassNames(String.join(",", classes));
                return enabled(minutes, maxEventsPerMinute, validated);
            }
            return disabled();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid packet trace control payload", exception);
        }
    }
}
