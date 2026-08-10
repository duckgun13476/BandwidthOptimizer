package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IdleGateForegroundEntityViewPolicy {

    private static final byte SEND = 1;
    private static final byte DEFER = 2;
    private static final int MAX_CACHED_ENTITIES_PER_PLAYER = 4_096;
    private static final double ALWAYS_SEND_DISTANCE_SQR = 8.0D * 8.0D;
    private static final double LOOK_DOT_THRESHOLD_SQR = 0.35D * 0.35D;
    private static final ConcurrentHashMap<UUID, PlayerViewCache> CACHES = new ConcurrentHashMap<>();

    private IdleGateForegroundEntityViewPolicy() {}

    public static boolean shouldDefer(ServerPlayer player, int entityId) {
        if (player == null || player.getId() == entityId) {
            return false;
        }
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null || level.getServer() == null || !level.getServer().isSameThread()) {
            return false;
        }
        return CACHES.computeIfAbsent(player.getUUID(), ignored -> new PlayerViewCache())
                .shouldDefer(player, level, entityId);
    }

    public static void discard(ServerPlayer player) {
        if (player != null) {
            CACHES.remove(player.getUUID());
        }
    }

    private static boolean isImmediatelyVisible(ViewState view, AABB bounds) {
        double nearestX = clamp(view.eye().x, bounds.minX, bounds.maxX);
        double nearestY = clamp(view.eye().y, bounds.minY, bounds.maxY);
        double nearestZ = clamp(view.eye().z, bounds.minZ, bounds.maxZ);
        double nearDx = nearestX - view.eye().x;
        double nearDy = nearestY - view.eye().y;
        double nearDz = nearestZ - view.eye().z;
        if (nearDx * nearDx + nearDy * nearDy + nearDz * nearDz <= ALWAYS_SEND_DISTANCE_SQR) {
            return true;
        }

        double centerX = (bounds.minX + bounds.maxX) * 0.5D;
        double centerY = (bounds.minY + bounds.maxY) * 0.5D;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;
        if (isPointInView(view, centerX, centerY, centerZ)) {
            return true;
        }
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? bounds.minX : bounds.maxX;
            double y = (corner & 2) == 0 ? bounds.minY : bounds.maxY;
            double z = (corner & 4) == 0 ? bounds.minZ : bounds.maxZ;
            if (isPointInView(view, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPointInView(ViewState view, double x, double y, double z) {
        double dx = x - view.eye().x;
        double dy = y - view.eye().y;
        double dz = z - view.eye().z;
        double distanceSqr = dx * dx + dy * dy + dz * dz;
        double projected = view.look().x * dx + view.look().y * dy + view.look().z * dz;
        return projected > 0.0D && projected * projected >= LOOK_DOT_THRESHOLD_SQR * distanceSqr;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record ViewState(Vec3 eye, Vec3 look) {}

    private static final class PlayerViewCache {
        private final Int2ByteOpenHashMap decisions = new Int2ByteOpenHashMap();
        private ServerLevel level;
        private long gameTime = Long.MIN_VALUE;
        private ViewState view;

        private boolean shouldDefer(ServerPlayer player, ServerLevel level, int entityId) {
            long currentGameTime = level.getGameTime();
            if (this.level != level || this.gameTime != currentGameTime) {
                this.level = level;
                this.gameTime = currentGameTime;
                this.decisions.clear();
                Vec3 look = player.getLookAngle();
                this.view = look == null || look.lengthSqr() < 1.0E-12D
                        ? null
                        : new ViewState(player.getEyePosition(), look.normalize());
            }
            byte cached = this.decisions.getOrDefault(entityId, (byte) 0);
            if (cached != 0) {
                return cached == DEFER;
            }
            if (this.decisions.size() >= MAX_CACHED_ENTITIES_PER_PLAYER) {
                return false;
            }
            Entity entity = level.getEntity(entityId);
            boolean defer = this.view != null
                    && entity != null
                    && entity != player
                    && !isImmediatelyVisible(this.view, entity.getBoundingBox());
            this.decisions.put(entityId, defer ? DEFER : SEND);
            return defer;
        }
    }
}
