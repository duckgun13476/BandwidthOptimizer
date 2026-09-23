package com.PinkCats.bandwidthoptimizer.recipe;

import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.RecipeSyncSemanticCompat;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import io.netty.channel.Channel;
import net.minecraft.network.protocol.Packet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

public final class RecipeSyncRunAllProbe {

    private static final int MAX_EVENTS = 32;
    private static final Path MARKER =
            BandwidthOptimizerOutputPaths.resolve("bo-runall-recipe-sync.marker");
    private static final AtomicInteger EVENTS = new AtomicInteger();
    private static final Object WRITE_LOCK = new Object();

    private RecipeSyncRunAllProbe() {}

    static void record(
            Channel channel,
            ChunkHotspotFrameOp operation,
            boolean semanticIdentity,
            int originalBytes,
            int payloadBytes,
            String rawHash,
            String semanticHash,
            byte[] originalPacketBytes,
            RecipeSyncStructuralView structuralView
    ) {
        if (!ExperientRuntimeFlags.isEnabled()) {
            return;
        }
        int event = EVENTS.getAndIncrement();
        if (event >= MAX_EVENTS) {
            return;
        }
        RecipeSyncDeltaCodec.SingleRecordSample sample = RecipeSyncDeltaCodec.sampleSingleRecordReplacement(
                originalPacketBytes, structuralView, originalBytes);
        String line = "event=" + event
                + "\tchannel=" + (channel == null ? "" : channel.id().asShortText())
                + "\toperation=" + operation
                + "\tsemantic_identity=" + semanticIdentity
                + "\toriginal_bytes=" + originalBytes
                + "\tpayload_bytes=" + payloadBytes
                + "\traw_hash=" + safeHash(rawHash)
                + "\tsemantic_hash=" + safeHash(semanticHash)
                + "\tstructural=" + (structuralView != null)
                + "\tsingle_record_bytes=" + sample.recordBytes()
                + "\tsingle_recipe_delta_bytes=" + sample.deltaBytes()
                + System.lineSeparator();
        writeLine(event, line);
    }

    public static void recordDecoded(Channel channel, Packet<?> packet, byte[] restoredPacketBytes) {
        if (!ExperientRuntimeFlags.isEnabled() || !RecipeSyncNegotiationGate.isRecipePacket(packet)
                || restoredPacketBytes == null || restoredPacketBytes.length == 0) {
            return;
        }
        int event = EVENTS.getAndIncrement();
        if (event >= MAX_EVENTS) {
            return;
        }
        String semanticHash;
        boolean semanticValid;
        try {
            semanticHash = RecipeSyncSemanticCompat.semanticHash(packet, restoredPacketBytes);
            semanticValid = RecipeSyncDeltaCodec.isHash(semanticHash);
        } catch (RuntimeException exception) {
            semanticHash = "";
            semanticValid = false;
        }
        String line = "event=" + event
                + "\tstage=client_decoded"
                + "\tchannel=" + (channel == null ? "" : channel.id().asShortText())
                + "\tpacket_class=" + packet.getClass().getName()
                + "\toriginal_bytes=" + restoredPacketBytes.length
                + "\traw_hash=" + RecipeSyncDeltaCodec.sha256(restoredPacketBytes)
                + "\tsemantic_hash=" + safeHash(semanticHash)
                + "\tsemantic_valid=" + semanticValid
                + System.lineSeparator();
        writeLine(event, line);
    }

    private static void writeLine(int event, String line) {
        synchronized (WRITE_LOCK) {
            try {
                Path parent = MARKER.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (event == 0) {
                    Files.writeString(MARKER, line, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    Files.writeString(MARKER, line, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (IOException ignored) {
                // The runAll verifier reports a missing marker.
            }
        }
    }

    private static String safeHash(String value) {
        return RecipeSyncDeltaCodec.isHash(value) ? value : "";
    }
}
