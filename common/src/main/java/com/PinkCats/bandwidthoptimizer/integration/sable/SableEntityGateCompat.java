package com.PinkCats.bandwidthoptimizer.integration.sable;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;

import java.lang.reflect.Method;

/** Entity gates must not rebuild or combine Sable's coordinate-aware packets. */
public final class SableEntityGateCompat {
    private static final Access ACCESS = discover();

    private SableEntityGateCompat() {}

    public static boolean shouldPassThrough(ServerPlayer player, int entityId) {
        if (!ACCESS.present()) {
            return false;
        }
        ServerLevel level = player == null ? null : ServerPlayerLevelCompat.serverLevel(player);
        // Encoder fallbacks must not query live entity/sublevel collections off the server thread.
        if (level == null || level.getServer() == null || !level.getServer().isSameThread()) {
            return true;
        }
        return shouldPassThrough(level.getEntity(entityId));
    }

    public static boolean shouldPassThrough(Entity entity) {
        return shouldPassThrough(entity, ACCESS);
    }

    static boolean shouldPassThrough(Entity entity, Access access) {
        if (!access.present()) {
            return false;
        }
        // An unresolved entity or unsupported installed API cannot prove vanilla semantics.
        if (entity == null || access.helper() == null) {
            return true;
        }
        if (access.isRelated(entity)) {
            return true;
        }
        Entity rootVehicle = entity.getRootVehicle();
        return rootVehicle != entity && access.isRelated(rootVehicle);
    }

    private static Access discover() {
        try {
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sable.getField("HELPER").get(null);
            if (helper == null) {
                return new Access(true, null, null, null);
            }
            return new Access(true, helper,
                    helper.getClass().getMethod("getContaining", Entity.class),
                    helper.getClass().getMethod("getTrackingSubLevel", Entity.class));
        } catch (ClassNotFoundException absent) {
            return new Access(false, null, null, null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unsupported) {
            return new Access(true, null, null, null);
        }
    }

    record Access(boolean present, Object helper, Method containing, Method tracking) {
        boolean isRelated(Entity entity) {
            try {
                return containing.invoke(helper, entity) != null
                        || tracking.invoke(helper, entity) != null;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException unsupported) {
                return true;
            }
        }
    }
}
