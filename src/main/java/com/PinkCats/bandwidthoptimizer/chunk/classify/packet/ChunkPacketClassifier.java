package com.PinkCats.bandwidthoptimizer.chunk.classify.packet;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ChunkPacketClassifier {

    private ChunkPacketClassifier() {}

    public static ChunkPacketDescriptor classifyOutboundPlayPacket(String protocolName, Packet<?> packet) {
        if (packet == null || !"PLAY".equalsIgnoreCase(protocolName)) {
            return null;
        }

        ChunkHotspotKind hotspotKind = resolveHotspotKind(packet);
        if (hotspotKind == null) {
            return null;
        }

        return new ChunkPacketDescriptor(
                protocolName,
                packet.getClass().getName(),
                hotspotKind,
                hotspotKind.laneKind(),
                resolveCoordinate(packet, hotspotKind)
        );
    }

    // What kind of chunk is?
    private static ChunkHotspotKind resolveHotspotKind(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            return ChunkHotspotKind.FULL_CHUNK;
        }
        if (packet instanceof ClientboundLightUpdatePacket) {
            return ChunkHotspotKind.LIGHT_UPDATE;
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket) {
            return ChunkHotspotKind.SECTION_BLOCKS_UPDATE;
        }
        if (packet instanceof ClientboundBlockUpdatePacket) {
            return ChunkHotspotKind.BLOCK_UPDATE;
        }
        if (packet instanceof ClientboundBlockEntityDataPacket) {
            return ChunkHotspotKind.BLOCK_ENTITY_UPDATE;
        }
        return null;
    }


    private static ChunkPacketCoordinate resolveCoordinate(Packet<?> packet, ChunkHotspotKind hotspotKind) {
        return switch (hotspotKind) {
            case FULL_CHUNK, LIGHT_UPDATE -> readChunkCoordinateByXZAccessors(packet);
            case SECTION_BLOCKS_UPDATE -> readSectionCoordinate(packet);
            case BLOCK_UPDATE, BLOCK_ENTITY_UPDATE -> readBlockCoordinate(packet);
        };
    }


    private static ChunkPacketCoordinate readChunkCoordinateByXZAccessors(Object packet) {
        Integer chunkX = readIntAccessorValue(packet, "getX", "x");
        Integer chunkZ = readIntAccessorValue(packet, "getZ", "z");
        if (chunkX == null || chunkZ == null) {
            return ChunkPacketCoordinate.unknown();
        }
        return ChunkPacketCoordinate.ofChunk(chunkX, chunkZ);
    }






    private static ChunkPacketCoordinate readBlockCoordinate(Object packet) {
        BlockPos blockPos = readObjectAccessorValue(packet, BlockPos.class, "getPos", "pos");
        if (blockPos == null)
            return ChunkPacketCoordinate.unknown();
        return ChunkPacketCoordinate.ofChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4);
    }

    private static ChunkPacketCoordinate readSectionCoordinate(Object packet) {
        SectionPos sectionPos = readObjectAccessorValue(packet, SectionPos.class, "sectionPos", "getSectionPos");
        if (sectionPos == null)
            return ChunkPacketCoordinate.unknown();
        return ChunkPacketCoordinate.ofChunk(sectionPos.x(), sectionPos.z());
    }





    private static Integer readIntAccessorValue(Object target, String... accessorNames) {
        if (target == null || accessorNames == null)
            return null;

        for (String accessorName : accessorNames) {
            Integer methodValue = readIntMethod(target, accessorName);
            if (methodValue != null)
                return methodValue;

            Integer fieldValue = readIntField(target, accessorName);
            if (fieldValue != null)
                return fieldValue;
        }
        return null;
    }



    private static <T> T readObjectAccessorValue(Object target, Class<T> expectedType, String... accessorNames) {
        if (target == null || expectedType == null || accessorNames == null) {
            return null;
        }

        for (String accessorName : accessorNames) {
            T methodValue = readObjectMethod(target, expectedType, accessorName);
            if (methodValue != null) {
                return methodValue;
            }

            T fieldValue = readObjectField(target, expectedType, accessorName);
            if (fieldValue != null) {
                return fieldValue;
            }
        }
        return null;
    }



    private static Integer readIntMethod(Object target, String methodName) {
        Method method = findNoArgMethod(target.getClass(), methodName);
        if (method == null)
            return null;

        Class<?> returnType = method.getReturnType();
        if (returnType != int.class && returnType != Integer.class)
            return null;

        try {
            Object value = method.invoke(target);
            if (value instanceof Number number)
                return number.intValue();
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Integer readIntField(Object target, String fieldName) {
        Field field = findField(target.getClass(), fieldName);
        if (field == null)
            return null;

        try {
            Object value = field.get(target);
            if (value instanceof Number number) {
                return number.intValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }


    private static <T> T readObjectMethod(Object target, Class<T> expectedType, String methodName) {
        Method method = findNoArgMethod(target.getClass(), methodName);
        if (method == null || !expectedType.isAssignableFrom(method.getReturnType()))
            return null;

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
        Field field = findField(target.getClass(), fieldName);
        if (field == null || !expectedType.isAssignableFrom(field.getType()))
            return null;

        try {
            Object value = field.get(target);
            if (expectedType.isInstance(value))
                return expectedType.cast(value);
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }


    private static Method findNoArgMethod(Class<?> type, String methodName) {
        if (type == null || methodName == null || methodName.isBlank())
            return null;

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
        if (type == null || fieldName == null || fieldName.isBlank())
            return null;

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
