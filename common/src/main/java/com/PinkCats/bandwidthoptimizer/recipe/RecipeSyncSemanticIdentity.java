package com.PinkCats.bandwidthoptimizer.recipe;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.RecipeSyncSemanticCompat;
import net.minecraft.network.protocol.Packet;

final class RecipeSyncSemanticIdentity {

    private RecipeSyncSemanticIdentity() {}

    static String resolve(Packet<?> packet, byte[] rawPacketBytes) {
        String rawHash = RecipeSyncDeltaCodec.sha256(rawPacketBytes);
        try {
            String semanticHash = RecipeSyncSemanticCompat.semanticHash(packet, rawPacketBytes);
            return RecipeSyncDeltaCodec.isHash(semanticHash) ? semanticHash : rawHash;
        } catch (RuntimeException ignored) {
            return rawHash;
        }
    }

    static RecipeSyncStructuralView structuralView(Packet<?> packet, byte[] rawPacketBytes) {
        try {
            return RecipeSyncSemanticCompat.structuralView(packet, rawPacketBytes);
        } catch (LinkageError | RuntimeException ignored) {
            return null;
        }
    }
}
