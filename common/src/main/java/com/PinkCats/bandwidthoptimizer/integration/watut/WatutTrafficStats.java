package com.PinkCats.bandwidthoptimizer.integration.watut;

import java.util.concurrent.atomic.AtomicLong;

/** Payload bytes, not compressed BO carrier/socket bytes. Client and server savings never overlap. */
public final class WatutTrafficStats {
    private static final AtomicLong CLIENT_BYTES = new AtomicLong(), CLIENT_FRAMES = new AtomicLong();
    private static final AtomicLong SERVER_BYTES = new AtomicLong(), SERVER_FRAMES = new AtomicLong();
    private static final AtomicLong IMAGE_BYTES = new AtomicLong(), IMAGE_PACKETS = new AtomicLong();
    private static final AtomicLong STATE_PACKETS = new AtomicLong(), MOUSE_PACKETS = new AtomicLong(), OTHER_PACKETS = new AtomicLong();
    private static final AtomicLong LOGIN_BYTES = new AtomicLong(), LOGIN_FRAMES = new AtomicLong();
    private static volatile long startedAtMillis = System.currentTimeMillis();
    private WatutTrafficStats() {}
    public static void clientSuppressed(long bytes) { add(CLIENT_BYTES, bytes); add(CLIENT_FRAMES, 1); }
    public static void serverSuppressed(long bytes, int recipients) {
        add(SERVER_BYTES, bytes * recipients); add(SERVER_FRAMES, recipients);
    }
    public static void receivedImage(long bytes) { add(IMAGE_BYTES, bytes); add(IMAGE_PACKETS, 1); }
    public static void receivedState() { add(STATE_PACKETS, 1); }
    public static void receivedMouse() { add(MOUSE_PACKETS, 1); }
    public static void receivedOther() { add(OTHER_PACKETS, 1); }
    public static void loginReplay(long bytes) { add(LOGIN_BYTES, bytes); add(LOGIN_FRAMES, 1); }
    public static Snapshot snapshot() {
        return new Snapshot(CLIENT_BYTES.get(), CLIENT_FRAMES.get(), SERVER_BYTES.get(), SERVER_FRAMES.get(),
                IMAGE_BYTES.get(), IMAGE_PACKETS.get(), STATE_PACKETS.get(), MOUSE_PACKETS.get(), OTHER_PACKETS.get(),
                LOGIN_BYTES.get(), LOGIN_FRAMES.get(), startedAtMillis);
    }
    public static void reset() {
        CLIENT_BYTES.set(0); CLIENT_FRAMES.set(0); SERVER_BYTES.set(0); SERVER_FRAMES.set(0);
        IMAGE_BYTES.set(0); IMAGE_PACKETS.set(0); STATE_PACKETS.set(0); MOUSE_PACKETS.set(0); OTHER_PACKETS.set(0);
        LOGIN_BYTES.set(0); LOGIN_FRAMES.set(0); startedAtMillis = System.currentTimeMillis();
    }
    private static void add(AtomicLong target, long bytes) {
        if (bytes > 0) target.updateAndGet(old -> old >= Long.MAX_VALUE - bytes ? Long.MAX_VALUE : old + bytes);
    }
    public record Snapshot(long clientSavedPayloadBytes, long clientSuppressedFrames,
                           long serverSavedPayloadBytes, long serverSuppressedRecipientFrames,
                           long receivedImagePayloadBytes, long receivedImagePackets, long receivedStatePackets,
                           long receivedMousePackets, long receivedOtherPackets,
                           long loginReplayPayloadBytes, long loginReplayRecipientFrames, long startedAtMillis) {}
}
