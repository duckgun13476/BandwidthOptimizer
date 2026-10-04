package com.PinkCats.bandwidthoptimizer.integration.sable;

import net.minecraft.world.entity.Entity;

public final class SableEntityGateCompatRegressionMain {
    public static void main(String[] args) throws Exception {
        Helper helper = new Helper();
        var access = new SableEntityGateCompat.Access(true, helper,
                Helper.class.getMethod("getContaining", Entity.class),
                Helper.class.getMethod("getTrackingSubLevel", Entity.class));
        // The fixture checks the optional API boundary without bootstrapping a game or loader.
        require(!access.isRelated(null), "unrelated API result remains gate eligible");
        helper.contained = true;
        require(access.isRelated(null), "plot containment bypasses gating");
        require(helper.trackingQueries == 1, "containment short circuits tracking lookup");
        helper.contained = false;
        helper.tracked = true;
        require(access.isRelated(null), "tracking relation bypasses gating");
        helper.tracked = false;
        require(!access.isRelated(null), "relation removal restores gate eligibility");
        helper.fail = true;
        require(access.isRelated(null), "API failure conservatively passes");
        require(SableEntityGateCompat.shouldPassThrough(null, access), "unresolved Sable entity passes");
        require(SableEntityGateCompat.shouldPassThrough(null,
                new SableEntityGateCompat.Access(true, null, null, null)), "unsupported installed Sable passes");
        require(!SableEntityGateCompat.shouldPassThrough(null,
                new SableEntityGateCompat.Access(false, null, null, null)), "Sable absent leaves gating unchanged");
        System.out.println("Sable entity gate compatibility regression passed.");
    }

    public static final class Helper {
        boolean contained;
        boolean tracked;
        int trackingQueries;
        boolean fail;
        public Object getContaining(Entity entity) {
            if (fail) throw new IllegalStateException("fixture failure");
            return contained ? this : null;
        }
        public Object getTrackingSubLevel(Entity entity) {
            trackingQueries++;
            return tracked ? this : null;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
