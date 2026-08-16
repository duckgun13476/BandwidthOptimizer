package com.PinkCats.bandwidthoptimizer.integration.voxy;

import net.minecraft.core.SectionPos;

import java.lang.reflect.Field;
import java.util.Set;

public final class VoxyVisualHandoffRegressionMain {

    private VoxyVisualHandoffRegressionMain() {}

    public static void main(String[] args) throws Exception {
        setStatic("voxyPresent", true);
        setStatic("upstreamFixPresent", false);
        VoxyChunkBoundCompat.clearVisualHandoffs();

        int chunkX = 7;
        int chunkZ = -3;
        long section = SectionPos.asLong(chunkX, 4, chunkZ);
        mutableSet("ALLOWED_MASKS").add(section);
        require(VoxyChunkBoundCompat.allowMaskAdd(section), "failed to seed active Voxy mask");

        VoxyChunkBoundCompat.beginVisualHandoff(chunkX, chunkZ, 41L);
        require(VoxyChunkBoundCompat.drainInvalidatedMasks().contains(section),
                "PREPARE did not invalidate the active depth mask");
        require(!VoxyChunkBoundCompat.allowMaskAdd(section),
                "PREPARE allowed the depth mask before the replacement mesh upload");

        VoxyChunkBoundCompat.beginChunkApply(chunkX, chunkZ);
        VoxyChunkBoundCompat.recordSectionBuildSubmitted(chunkX, 4, chunkZ, 100);
        VoxyChunkBoundCompat.completeChunkApply(chunkX, chunkZ);
        VoxyChunkBoundCompat.acceptSectionUpload(chunkX, 4, chunkZ, 99, 1, 0L, 1024L);
        require(VoxyChunkBoundCompat.drainReadyMasks().isEmpty(),
                "an older Sodium upload ended the visual handoff");
        require(!VoxyChunkBoundCompat.allowMaskAdd(section),
                "an older Sodium upload released the held section");

        VoxyChunkBoundCompat.acceptSectionUpload(chunkX, 4, chunkZ, 100, 1, 0L, 1024L);
        require(VoxyChunkBoundCompat.drainReadyMasks().contains(section),
                "the matching Sodium upload did not restore the depth mask");
        require(VoxyChunkBoundCompat.allowMaskAdd(section),
                "the matching Sodium upload left the section blocked");

        VoxyChunkBoundCompat.beginVisualHandoff(chunkX, chunkZ, 42L);
        VoxyChunkBoundCompat.beginVisualHandoff(chunkX, chunkZ, 43L);
        VoxyChunkBoundCompat.cancelVisualHandoff(chunkX, chunkZ);
        VoxyChunkBoundCompat.acceptSectionUpload(chunkX, 4, chunkZ, 101, 1, 0L, 1024L);
        require(VoxyChunkBoundCompat.allowMaskAdd(section),
                "Forget retained a stale visual handoff generation");

        VoxyChunkBoundCompat.clearVisualHandoffs();
        System.out.println("Voxy visual handoff regression passed");
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> mutableSet(String fieldName) throws Exception {
        Field field = VoxyChunkBoundCompat.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Set<Long>) field.get(null);
    }

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = VoxyChunkBoundCompat.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
