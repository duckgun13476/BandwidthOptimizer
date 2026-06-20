package com.PinkCats.bandwidthoptimizer.compat.voxy;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.core.SectionPos;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoxyChunkBoundCompat {
    private static final int DEFAULT_HAS_BLOCK_GEOMETRY_FLAG_INDEX = 0;
    private static final long OPEN_SECTION_MAX_MASK_MESH_BYTES = 48L * 1024L;
    private static final String SODIUM_RENDER_SECTION_FLAGS_CLASS =
            "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags";
    private static final String VOXY_RENDER_SECTION_MANAGER_CLASS =
            "me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager.class";
    private static final byte[] VOXY_NATIVE_MASK_FIX_MARKER =
            "voxy$shouldMaskLod".getBytes(StandardCharsets.UTF_8);
    private static final Set<Long> ACTIVE_MASKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> ALLOWED_MASKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> INVALIDATED_MASKS = ConcurrentHashMap.newKeySet();
    private static final Map<Long, Long> MESH_BYTES_BY_SECTION = new ConcurrentHashMap<>();
    private static final AtomicBoolean READ_FAILED = new AtomicBoolean();
    private static final AtomicBoolean MESH_READ_FAILED = new AtomicBoolean();
    private static final AtomicBoolean UPSTREAM_FIX_LOGGED = new AtomicBoolean();
    private static volatile Class<?> infoClass;
    private static volatile Field flagsField;
    private static volatile Field visibilityDataField;
    private static volatile Integer hasBlockGeometryMask;
    private static volatile Boolean upstreamFixPresent;

    private VoxyChunkBoundCompat() {
    }

    public static void updateSection(int sectionX, int sectionY, int sectionZ, Object info) {
        if (!isCompatEnabled()) {
            return;
        }
        SectionInfo newInfo = readSectionInfo(info);
        long sectionPosition = SectionPos.asLong(sectionX, sectionY, sectionZ);
        if (shouldMaskLod(sectionPosition, newInfo.flags, newInfo.visibilityData)) {
            ALLOWED_MASKS.add(sectionPosition);
            return;
        }
        ALLOWED_MASKS.remove(sectionPosition);
        if ((newInfo.flags & hasBlockGeometryMask()) == 0) {
            MESH_BYTES_BY_SECTION.remove(sectionPosition);
        }
        if (ACTIVE_MASKS.contains(sectionPosition)) {
            INVALIDATED_MASKS.add(sectionPosition);
        }
    }

    public static void recordMeshSize(Object renderSection, Map<?, ?> meshes) {
        if (!isCompatEnabled() || renderSection == null || meshes == null) {
            return;
        }
        try {
            long sectionPosition = SectionPos.asLong(
                    invokeInt(renderSection, "getChunkX"),
                    invokeInt(renderSection, "getChunkY"),
                    invokeInt(renderSection, "getChunkZ")
            );
            long meshBytes = 0L;
            for (Object mesh : meshes.values()) {
                meshBytes += readMeshBytes(mesh);
            }
            MESH_BYTES_BY_SECTION.put(sectionPosition, meshBytes);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (MESH_READ_FAILED.compareAndSet(false, true)) {
                Bandwidthoptimizer.LOGGER.warn("[BO-VOXY] Failed to read Sodium chunk mesh size", exception);
            }
        }
    }

    public static boolean allowMaskAdd(long sectionPosition) {
        if (!isCompatEnabled()) {
            return true;
        }
        if (!ALLOWED_MASKS.contains(sectionPosition)) {
            return false;
        }
        ACTIVE_MASKS.add(sectionPosition);
        return true;
    }

    public static boolean allowMaskRemove(long sectionPosition) {
        if (!isCompatEnabled()) {
            return true;
        }
        if (!ACTIVE_MASKS.remove(sectionPosition)) {
            return false;
        }
        return true;
    }

    public static Set<Long> drainInvalidatedMasks() {
        if (!isCompatEnabled()) {
            return Collections.emptySet();
        }
        Set<Long> drained = Set.copyOf(INVALIDATED_MASKS);
        INVALIDATED_MASKS.removeAll(drained);
        return drained;
    }

    private static boolean isCompatEnabled() {
        if (!hasVoxyNativeMaskFix()) {
            return true;
        }
        if (UPSTREAM_FIX_LOGGED.compareAndSet(false, true)) {
            Bandwidthoptimizer.LOGGER.info("[BO-VOXY] Native Voxy chunk bound fix detected; BO compat filter is pass-through");
        }
        ACTIVE_MASKS.clear();
        ALLOWED_MASKS.clear();
        INVALIDATED_MASKS.clear();
        MESH_BYTES_BY_SECTION.clear();
        return false;
    }

    // Avoid managing chunk bounds when Voxy already ships the native fix.
    private static boolean hasVoxyNativeMaskFix() {
        Boolean cached = upstreamFixPresent;
        if (cached != null) {
            return cached;
        }
        synchronized (VoxyChunkBoundCompat.class) {
            if (upstreamFixPresent == null) {
                upstreamFixPresent = containsClassMarker(VOXY_RENDER_SECTION_MANAGER_CLASS, VOXY_NATIVE_MASK_FIX_MARKER);
            }
            return upstreamFixPresent;
        }
    }

    // Scan class bytes instead of loading Voxy's mixin class.
    private static boolean containsClassMarker(String resourceName, byte[] marker) {
        try (InputStream stream = VoxyChunkBoundCompat.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (stream == null) {
                return false;
            }
            byte[] bytes = stream.readAllBytes();
            return contains(bytes, marker);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean contains(byte[] bytes, byte[] marker) {
        if (bytes.length < marker.length) {
            return false;
        }
        for (int i = 0; i <= bytes.length - marker.length; i++) {
            boolean matched = true;
            for (int j = 0; j < marker.length; j++) {
                if (bytes[i + j] != marker[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldMaskLod(long sectionPosition, int flags, long visibilityData) {
        if ((flags & hasBlockGeometryMask()) == 0) {
            return false;
        }
        if (visibilityData == 0L) {
            return true;
        }
        Long meshBytes = MESH_BYTES_BY_SECTION.get(sectionPosition);
        return meshBytes != null && meshBytes > 0L && meshBytes <= OPEN_SECTION_MAX_MASK_MESH_BYTES;
    }

    // Sodium stores flag indexes here, not shifted masks.
    private static int hasBlockGeometryMask() {
        Integer cached = hasBlockGeometryMask;
        if (cached != null) {
            return cached;
        }
        synchronized (VoxyChunkBoundCompat.class) {
            if (hasBlockGeometryMask == null) {
                hasBlockGeometryMask = 1 << readHasBlockGeometryFlagIndex();
            }
            return hasBlockGeometryMask;
        }
    }

    private static int readHasBlockGeometryFlagIndex() {
        try {
            Class<?> flagsClass = Class.forName(
                    SODIUM_RENDER_SECTION_FLAGS_CLASS,
                    false,
                    VoxyChunkBoundCompat.class.getClassLoader()
            );
            int flagIndex = flagsClass.getField("HAS_BLOCK_GEOMETRY").getInt(null);
            if (flagIndex >= 0 && flagIndex < Integer.SIZE) {
                return flagIndex;
            }
        } catch (ReflectiveOperationException ignored) {
            // Missing Sodium constants should not break Voxy fallback; current supported Sodium uses index 0.
        }
        return DEFAULT_HAS_BLOCK_GEOMETRY_FLAG_INDEX;
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

    private static int invokeInt(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return ((Number) method.invoke(target)).intValue();
    }

    private static long readMeshBytes(Object mesh) throws ReflectiveOperationException {
        if (mesh == null) {
            return 0L;
        }
        Object vertexData = mesh.getClass().getMethod("getVertexData").invoke(mesh);
        if (vertexData == null) {
            return 0L;
        }
        Method getLength = vertexData.getClass().getMethod("getLength");
        return ((Number) getLength.invoke(vertexData)).longValue();
    }

    private record SectionInfo(int flags, long visibilityData) {
    }
}
