package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ChannelTransportAdaptiveBypass {

    private static final int UNPROFITABLE_THRESHOLD = 16;
    private static final int MAX_ENTRIES = 4096;
    private static final int EXPENSIVE_SCAN_THRESHOLD = 64;
    private static final long BYPASS_TTL_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long SATURATED_SCAN_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final ConcurrentHashMap<Key, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Object ADMISSION_LOCK = new Object();
    private static final ArrayDeque<Key> ADMISSION_ORDER = new ArrayDeque<>(MAX_ENTRIES);
    private static long nextSaturatedScanNanos;

    private ChannelTransportAdaptiveBypass() {}

    public static boolean shouldBypassBeforeWrap(String protocolName, PacketFlow packetFlow, Packet<?> packet, byte[] packetBytes) {
        Key key = keyOf(protocolName, packetFlow, packet, packetBytes);
        if (key.payloadChannel().isEmpty()) {
            return false;
        }
        Entry entry = ENTRIES.get(key);
        long now = System.nanoTime();
        if (entry == null) {
            return false;
        }
        if (entry.bypassUntilNanos > now) {
            return true;
        }
        return false;
    }

    public static void recordCarrierResult(
            String protocolName,
            PacketFlow packetFlow,
            Packet<?> packet,
            byte[] packetBytes,
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame
    ) {
        if (packetBytes == null || wrappedFrame == null) {
            return;
        }
        Key key = keyOf(protocolName, packetFlow, packet, packetBytes);
        if (key.payloadChannel().isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        Entry entry = getOrAdmitEntry(key, now);
        if (entry == null) {
            return;
        }
        if (isUnprofitableStableCarrier(packetBytes.length, wrappedFrame)) {
            int nextCount = Math.min(entry.unprofitableCount + 1, UNPROFITABLE_THRESHOLD);
            entry.unprofitableCount = nextCount;
            if (nextCount >= UNPROFITABLE_THRESHOLD) {
                entry.bypassUntilNanos = now + BYPASS_TTL_NANOS;
            }
        } else {
            entry.unprofitableCount = 0;
            entry.bypassUntilNanos = 0L;
        }
    }

    private static boolean isUnprofitableStableCarrier(int packetBytes, ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame) {
        if (packetBytes <= 0 || wrappedFrame.transportFrameLength() < packetBytes) {
            return false;
        }
        ChannelTransportOperationTelemetry telemetry = wrappedFrame.telemetry();
        return telemetry != null
                && telemetry.exactAdditionCount() == 0
                && telemetry.templateAdditionCount() == 0
                && telemetry.exactRemovalCount() == 0
                && telemetry.templateRemovalCount() == 0;
    }

    private static Entry getOrAdmitEntry(Key key, long now) {
        Entry existing = ENTRIES.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (ADMISSION_LOCK) {
            existing = ENTRIES.get(key);
            if (existing != null) {
                return existing;
            }
            if (ENTRIES.size() >= MAX_ENTRIES && !makeAdmissionRoom(now)) {
                return null;
            }
            Entry admitted = new Entry();
            ENTRIES.put(key, admitted);
            ADMISSION_ORDER.addLast(key);
            return admitted;
        }
    }

    private static boolean makeAdmissionRoom(long now) {
        if (now < nextSaturatedScanNanos) {
            return false;
        }
        int scanned = removeOldestInactive(now);
        if (scanned > 0) {
            nextSaturatedScanNanos = scanned >= EXPENSIVE_SCAN_THRESHOLD
                    ? now + SATURATED_SCAN_RETRY_NANOS
                    : 0L;
            return true;
        }
        nextSaturatedScanNanos = now + SATURATED_SCAN_RETRY_NANOS;
        return false;
    }

    private static int removeOldestInactive(long now) {
        Iterator<Key> iterator = ADMISSION_ORDER.iterator();
        int scanned = 0;
        while (iterator.hasNext()) {
            scanned++;
            Key candidateKey = iterator.next();
            Entry candidate = ENTRIES.get(candidateKey);
            if (candidate == null) {
                iterator.remove();
                continue;
            }
            if (candidate.bypassUntilNanos > now) {
                continue;
            }
            if (ENTRIES.remove(candidateKey, candidate)) {
                iterator.remove();
                return scanned;
            }
        }
        return 0;
    }

    private static Key keyOf(String protocolName, PacketFlow packetFlow, Packet<?> packet, byte[] packetBytes) {
        return new Key(
                normalize(protocolName),
                packetFlow == null ? "" : packetFlow.name(),
                packet == null ? "<unknown-packet>" : packet.getClass().getName(),
                normalize(CustomPayloadPacketCompat.payloadChannel(packet)),
                tryReadLeadingVarInt(packetBytes),
                sizeBucket(packetBytes == null ? 0 : packetBytes.length)
        );
    }

    private static int sizeBucket(int size) {
        if (size <= 0) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(size - 1);
    }

    private static int tryReadLeadingVarInt(byte[] packetBytes) {
        if (packetBytes == null || packetBytes.length == 0) {
            return -1;
        }
        int value = 0;
        int shift = 0;
        int limit = Math.min(5, packetBytes.length);
        for (int index = 0; index < limit; index++) {
            int current = packetBytes[index] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private record Key(
            String protocolName,
            String flowName,
            String packetClassName,
            String payloadChannel,
            int packetId,
            int sizeBucket
    ) {
        private Key {
            protocolName = Objects.requireNonNullElse(protocolName, "");
            flowName = Objects.requireNonNullElse(flowName, "");
            packetClassName = Objects.requireNonNullElse(packetClassName, "");
            payloadChannel = Objects.requireNonNullElse(payloadChannel, "");
        }
    }

    private static final class Entry {
        private volatile int unprofitableCount;
        private volatile long bypassUntilNanos;
    }
}
