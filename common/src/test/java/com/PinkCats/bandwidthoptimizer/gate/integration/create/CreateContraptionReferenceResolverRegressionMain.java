package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import java.lang.ref.WeakReference;
import java.util.List;

public final class CreateContraptionReferenceResolverRegressionMain {

    private CreateContraptionReferenceResolverRegressionMain() {}

    public static void main(String[] args) {
        if (CreateBlockEntityUpdateGate.resolveCreateContraptionReferences(null) != null) {
            throw new AssertionError("removed block entity must fall back to immediate delivery");
        }

        Object piston = new Object();
        assertReferences("mechanical piston", CreateContraptionReferenceResolver.resolve(
                new PistonController(piston), "create:mechanical_piston"), piston);

        Object bearing = new Object();
        assertReferences("bearing", CreateContraptionReferenceResolver.resolve(
                new BearingController(bearing), "create:mechanical_bearing"), bearing);

        Object hourHand = new Object();
        Object minuteHand = new Object();
        assertReferences("clockwork bearing", CreateContraptionReferenceResolver.resolve(
                new ClockworkController(hourHand, minuteHand), "create:clockwork_bearing"), hourHand, minuteHand);

        Object attached = new Object();
        Object mirror = new Object();
        assertReferences("pulley", CreateContraptionReferenceResolver.resolve(
                new PulleyController(attached, mirror), "create:rope_pulley"), attached, mirror);

        CreateContraptionReferenceResolver.Resolution nearby = CreateContraptionReferenceResolver.resolve(
                new NearbyOnlyController(), "create:gantry_shaft");
        if (!nearby.requiresNearbyScan() || !nearby.references().isEmpty()) {
            throw new AssertionError("gantry controller must retain nearby-entity fallback");
        }

        Object secondPiston = new Object();
        assertReferences("cached inherited piston field", CreateContraptionReferenceResolver.resolve(
                new PistonController(secondPiston), "create:mechanical_piston"), secondPiston);
        System.out.println("Create contraption reference resolver regression passed");
    }

    private static void assertReferences(
            String label,
            CreateContraptionReferenceResolver.Resolution resolution,
            Object... expected
    ) {
        if (resolution.requiresNearbyScan()) {
            throw new AssertionError(label + " unexpectedly requires nearby scan");
        }
        List<Object> actual = resolution.references();
        if (actual.size() != expected.length) {
            throw new AssertionError(label + " expected=" + expected.length + " actual=" + actual.size());
        }
        for (int index = 0; index < expected.length; index++) {
            if (actual.get(index) != expected[index]) {
                throw new AssertionError(label + " reference " + index + " changed identity");
            }
        }
    }

    private static class LinearController {
        protected final Object movedContraption;

        private LinearController(Object movedContraption) {
            this.movedContraption = movedContraption;
        }
    }

    private static final class PistonController extends LinearController {
        private PistonController(Object movedContraption) {
            super(movedContraption);
        }
    }

    private static final class BearingController {
        private final Object movedContraption;

        private BearingController(Object movedContraption) {
            this.movedContraption = movedContraption;
        }

        private Object getMovedContraption() {
            return movedContraption;
        }
    }

    private static final class ClockworkController {
        private final Object hourHand;
        private final Object minuteHand;

        private ClockworkController(Object hourHand, Object minuteHand) {
            this.hourHand = hourHand;
            this.minuteHand = minuteHand;
        }
    }

    private static final class PulleyController {
        private final Object attached;
        private final WeakReference<Object> sharedMirrorContraption;

        private PulleyController(Object attached, Object mirror) {
            this.attached = attached;
            this.sharedMirrorContraption = new WeakReference<>(mirror);
        }

        private Object getAttachedContraption() {
            return attached;
        }
    }

    private static final class NearbyOnlyController {}
}
