package com.PinkCats.bandwidthoptimizer.chunk.snapshot;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ChunkSnapshotSemanticKeyResolver {

    private ChunkSnapshotSemanticKeyResolver() {}

    public static String resolveSemanticKey(ChunkPacketDescriptor descriptor, Packet<?> packet) {
        if (descriptor == null || descriptor.hotspotKind() == null)
            return "default";

        return switch (descriptor.hotspotKind()) {
            case FULL_CHUNK -> "full";
            case LIGHT_UPDATE -> "light";
            case SECTION_BLOCKS_UPDATE -> resolveSectionSemanticKey(packet);
            case BLOCK_UPDATE, BLOCK_ENTITY_UPDATE -> resolveBlockSemanticKey(packet);
        };
    }

    private static String resolveSectionSemanticKey(Packet<?> packet) {
        SectionPos sectionPos = readObjectAccessorValue(packet, SectionPos.class, "sectionPos", "getSectionPos");
        if (sectionPos == null)
            return "section:unknown";
        return "section:" + sectionPos.x() + "," + sectionPos.y() + "," + sectionPos.z();
    }

    private static String resolveBlockSemanticKey(Packet<?> packet) {
        BlockPos blockPos = readObjectAccessorValue(packet, BlockPos.class, "getPos", "pos");
        if (blockPos == null)
            return "block:unknown";
        return "block:" + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
    }

    private static <T> T readObjectAccessorValue(Object target, Class<T> expectedType, String... accessorNames) {
        if (target == null || expectedType == null || accessorNames == null)
            return null;

        for (String accessorName : accessorNames) {
            T methodValue = readObjectMethod(target, expectedType, accessorName);
            if (methodValue != null)
                return methodValue;

            T fieldValue = readObjectField(target, expectedType, accessorName);
            if (fieldValue != null)
                return fieldValue;
        }
        return null;
    }

    private static <T> T readObjectMethod(Object target, Class<T> expectedType, String methodName) {
        Method method = findNoArgMethod(target == null ? null : target.getClass(), methodName);
        if (method == null || !expectedType.isAssignableFrom(method.getReturnType())) {
            return null;
        }

        try {
            Object value = method.invoke(target);
            if (expectedType.isInstance(value)) {
                return expectedType.cast(value);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static <T> T readObjectField(Object target, Class<T> expectedType, String fieldName) {
        Field field = findField(target == null ? null : target.getClass(), fieldName);
        if (field == null || !expectedType.isAssignableFrom(field.getType())) {
            return null;
        }

        try {
            Object value = field.get(target);
            if (expectedType.isInstance(value)) {
                return expectedType.cast(value);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String methodName) {
        if (type == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0 && methodName.equals(method.getName())) {
                method.setAccessible(true);
                return method;
            }
        }

        Class<?> currentType = type;
        while (currentType != null) {
            for (Method method : currentType.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && methodName.equals(method.getName())) {
                    method.setAccessible(true);
                    return method;
                }
            }
            currentType = currentType.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> type, String fieldName) {
        if (type == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        Class<?> currentType = type;
        while (currentType != null) {
            try {
                Field field = currentType.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                currentType = currentType.getSuperclass();
            }
        }
        return null;
    }
}
