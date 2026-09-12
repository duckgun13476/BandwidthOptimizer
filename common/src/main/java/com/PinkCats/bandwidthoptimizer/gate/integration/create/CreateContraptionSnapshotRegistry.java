package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import com.PinkCats.bandwidthoptimizer.mixin.minecraft.EntityLevelAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public final class CreateContraptionSnapshotRegistry {

    private static final long MAX_STALE_TICKS = 2L;
    private static final long PRUNE_INTERVAL_TICKS = 20L;
    private static final Map<Level, SnapshotIndex> LEVELS = new WeakHashMap<>();
    private static final ClassValue<Optional<Method>> GANTRY_FACING_METHOD = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            Class<?> current = type;
            while (current != null) {
                try {
                    Method method = current.getDeclaredMethod("getFacing");
                    method.setAccessible(true);
                    return Optional.of(method);
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                } catch (LinkageError | SecurityException ignored) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
    };

    private CreateContraptionSnapshotRegistry() {}

    public static void trackControlled(Entity entity, BlockPos controllerPos) {
        if (!canTrack(entity) || controllerPos == null) {
            return;
        }
        track(entity, controllerPos.asLong(), 0L, 1);
    }

    public static void trackGantry(Entity entity) {
        if (!canTrack(entity)) {
            return;
        }
        Object anchorValue = CreateContraptionReferenceResolver.readAnchorVector(entity);
        Object contraption = CreateContraptionReferenceResolver.readContraption(entity);
        Direction facing = readGantryFacing(contraption);
        if (!(anchorValue instanceof Vec3 anchor) || facing == null) {
            return;
        }
        BlockPos carriagePos = new BlockPos(
                Mth.floor(anchor.x + 0.5D),
                Mth.floor(anchor.y + 0.5D),
                Mth.floor(anchor.z + 0.5D)
        );
        BlockPos shaftPos = carriagePos.relative(facing.getOpposite());
        track(entity, carriagePos.asLong(), shaftPos.asLong(), 2);
    }

    static Snapshot find(Level level, BlockPos controllerPos) {
        if (level == null || controllerPos == null || level.isClientSide()) {
            return null;
        }
        SnapshotIndex index = LEVELS.get(level);
        return index == null ? null : index.find(controllerPos.asLong(), level.getGameTime());
    }

    static void prune() {
        Iterator<Map.Entry<Level, SnapshotIndex>> iterator = LEVELS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Level, SnapshotIndex> entry = iterator.next();
            Level level = entry.getKey();
            if (level == null) {
                iterator.remove();
                continue;
            }
            entry.getValue().prune(level.getGameTime());
        }
    }

    private static boolean canTrack(Entity entity) {
        return entity != null && !entity.isRemoved() && !levelOf(entity).isClientSide();
    }

    private static void track(Entity entity, long firstController, long secondController, int controllerCount) {
        Level level = levelOf(entity);
        AABB bounds = entity.getBoundingBox();
        if (bounds == null) {
            return;
        }
        LEVELS.computeIfAbsent(level, ignored -> new SnapshotIndex())
                .track(entity.getId(), firstController, secondController, controllerCount, bounds, level.getGameTime());
    }

    private static Level levelOf(Entity entity) {
        return ((EntityLevelAccessor) entity).bandwidthoptimizer$getLevel();
    }

    private static Direction readGantryFacing(Object contraption) {
        if (contraption == null) {
            return null;
        }
        Method method = GANTRY_FACING_METHOD.get(contraption.getClass()).orElse(null);
        if (method == null) {
            return null;
        }
        try {
            Object value = method.invoke(contraption);
            return value instanceof Direction direction ? direction : null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    static final class SnapshotIndex {
        private final Map<Long, Bucket> byController = new HashMap<>();
        private final Map<Integer, EntityEntry> byEntity = new HashMap<>();
        private long nextPruneTick;

        void track(
                int entityId,
                long firstController,
                long secondController,
                int controllerCount,
                AABB bounds,
                long tick
        ) {
            EntityEntry entry = this.byEntity.get(entityId);
            if (entry == null) {
                entry = new EntityEntry();
                this.byEntity.put(entityId, entry);
            } else {
                removeFromPreviousBuckets(entityId, entry, firstController, secondController, controllerCount);
            }
            entry.update(firstController, secondController, controllerCount, bounds, tick);
            this.byController.computeIfAbsent(firstController, ignored -> new Bucket()).put(entityId, entry);
            if (controllerCount > 1 && secondController != firstController) {
                this.byController.computeIfAbsent(secondController, ignored -> new Bucket()).put(entityId, entry);
            }
            if (tick >= this.nextPruneTick) {
                prune(tick);
            }
        }

        Snapshot find(long controllerPosition, long tick) {
            Bucket bucket = this.byController.get(controllerPosition);
            if (bucket == null) {
                return null;
            }
            Snapshot snapshot = bucket.aggregate(tick);
            if (bucket.isEmpty()) {
                this.byController.remove(controllerPosition);
            }
            return snapshot;
        }

        void prune(long tick) {
            if (tick < this.nextPruneTick) {
                return;
            }
            this.nextPruneTick = tick + PRUNE_INTERVAL_TICKS;
            Iterator<Map.Entry<Integer, EntityEntry>> iterator = this.byEntity.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, EntityEntry> entry = iterator.next();
                EntityEntry entityEntry = entry.getValue();
                if (!isFresh(entityEntry, tick)) {
                    removeFromPreviousBuckets(entry.getKey(), entityEntry, 0L, 0L, 0);
                    iterator.remove();
                }
            }
        }

        private void removeFromPreviousBuckets(
                int entityId,
                EntityEntry previous,
                long firstController,
                long secondController,
                int controllerCount
        ) {
            removePreviousBucketIfChanged(
                    entityId,
                    previous.firstController,
                    firstController,
                    secondController,
                    controllerCount);
            if (previous.controllerCount > 1 && previous.secondController != previous.firstController) {
                removePreviousBucketIfChanged(
                        entityId,
                        previous.secondController,
                        firstController,
                        secondController,
                        controllerCount);
            }
        }

        private void removePreviousBucketIfChanged(
                int entityId,
                long previousController,
                long firstController,
                long secondController,
                int controllerCount
        ) {
            if ((controllerCount > 0 && previousController == firstController)
                    || (controllerCount > 1 && previousController == secondController)) {
                return;
            }
            Bucket bucket = this.byController.get(previousController);
            if (bucket != null) {
                bucket.remove(entityId);
                if (bucket.isEmpty()) {
                    this.byController.remove(previousController);
                }
            }
        }
    }

    private static final class Bucket {
        private final Map<Integer, EntityEntry> entries = new HashMap<>();
        private Snapshot aggregate;
        private boolean dirty = true;

        void put(int entityId, EntityEntry entry) {
            this.entries.put(entityId, entry);
            this.dirty = true;
        }

        void remove(int entityId) {
            this.dirty |= this.entries.remove(entityId) != null;
        }

        boolean isEmpty() {
            return this.entries.isEmpty();
        }

        Snapshot aggregate(long tick) {
            Iterator<Map.Entry<Integer, EntityEntry>> iterator = this.entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (!isFresh(iterator.next().getValue(), tick)) {
                    iterator.remove();
                    this.dirty = true;
                }
            }
            if (!this.dirty) {
                return this.aggregate;
            }
            AABB combined = null;
            for (EntityEntry entry : this.entries.values()) {
                combined = combined == null
                        ? entry.bounds
                        : CreateBlockEntityUpdateGate.union(combined, entry.bounds);
            }
            this.aggregate = combined == null ? null : new Snapshot(combined);
            this.dirty = false;
            return this.aggregate;
        }
    }

    private static boolean isFresh(EntityEntry entry, long tick) {
        return entry != null && tick - entry.lastSeenTick <= MAX_STALE_TICKS;
    }

    private static final class EntityEntry {
        private long firstController;
        private long secondController;
        private int controllerCount;
        private AABB bounds;
        private long lastSeenTick;

        private void update(
                long firstController,
                long secondController,
                int controllerCount,
                AABB bounds,
                long lastSeenTick
        ) {
            this.firstController = firstController;
            this.secondController = secondController;
            this.controllerCount = controllerCount;
            this.bounds = bounds;
            this.lastSeenTick = lastSeenTick;
        }
    }

    record Snapshot(AABB bounds) {
        Vec3 center() {
            return CreateBlockEntityUpdateGate.centerOf(this.bounds);
        }

        Vec3[] points() {
            return CreateBlockEntityUpdateGate.cornersOf(this.bounds);
        }
    }
}
