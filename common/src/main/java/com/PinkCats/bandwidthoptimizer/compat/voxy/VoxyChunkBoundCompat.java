package com.PinkCats.bandwidthoptimizer.compat.voxy;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.core.SectionPos;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoxyChunkBoundCompat {
    private static final int TERRAIN_RENDER_PASS_FLAG = 1;
    private static final Set<Long> ACTIVE_MASKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> ALLOWED_MASKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> INVALIDATED_MASKS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean READ_FAILED = new AtomicBoolean();
    private static volatile Class<?> infoClass;
    private static volatile Field flagsField;
    private static volatile Field visibilityDataField;

    private VoxyChunkBoundCompat() {
    }

    public static void updateSection(int sectionX, int sectionY, int sectionZ, Object info) {
        SectionInfo newInfo = readSectionInfo(info);
        long sectionPosition = SectionPos.asLong(sectionX, sectionY, sectionZ);
        if (shouldMaskLod(newInfo.flags, newInfo.visibilityData)) {
            ALLOWED_MASKS.add(sectionPosition);
            return;
        }
        ALLOWED_MASKS.remove(sectionPosition);
        if (ACTIVE_MASKS.contains(sectionPosition)) {
            INVALIDATED_MASKS.add(sectionPosition);
        }
    }

    public static boolean allowMaskAdd(long sectionPosition) {
        if (!ALLOWED_MASKS.contains(sectionPosition)) {
            return false;
        }
        ACTIVE_MASKS.add(sectionPosition);
        return true;
    }

    public static boolean allowMaskRemove(long sectionPosition) {
        if (!ACTIVE_MASKS.remove(sectionPosition)) {
            return false;
        }
        return true;
    }

    public static Set<Long> drainInvalidatedMasks() {
        Set<Long> drained = Set.copyOf(INVALIDATED_MASKS);
        INVALIDATED_MASKS.removeAll(drained);
        return drained;
    }

    private static boolean shouldMaskLod(int flags, long visibilityData) {
        return (flags & TERRAIN_RENDER_PASS_FLAG) != 0 && visibilityData == 0L;
    }

    private static SectionInfo readSectionInfo(Object info) {
        if (info == null) {
            return new SectionInfo(0, 0L);
        }
        try {
            Field flags = flagsField;
            Field visibility = visibilityDataField;
            if (flags == null || visibility == null || info.getClass() != infoClass) {
                synchronized (VoxyChunkBoundCompat.class) {
                    if (flagsField == null || visibilityDataField == null || info.getClass() != infoClass) {
                        infoClass = info.getClass();
                        flagsField = infoClass.getField("flags");
                        visibilityDataField = infoClass.getField("visibilityData");
                    }
                    flags = flagsField;
                    visibility = visibilityDataField;
                }
            }
            return new SectionInfo(flags.getInt(info), visibility.getLong(info));
        } catch (ReflectiveOperationException exception) {
            if (READ_FAILED.compareAndSet(false, true)) {
                Bandwidthoptimizer.LOGGER.warn("[BO-VOXY] Failed to read Sodium BuiltSectionInfo", exception);
            }
            return new SectionInfo(0, 0L);
        }
    }

    private record SectionInfo(int flags, long visibilityData) {
    }
}
