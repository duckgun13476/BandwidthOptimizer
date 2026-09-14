package com.PinkCats.bandwidthoptimizer.util;

import java.lang.ref.WeakReference;

public final class WeakIdentitySetRegressionMain {

    private WeakIdentitySetRegressionMain() {
    }

    public static void main(String[] args) throws Exception {
        WeakIdentitySet<EqualValue> set = new WeakIdentitySet<>();
        EqualValue first = new EqualValue(1);
        EqualValue equalButDistinct = new EqualValue(1);
        set.add(first);

        require(set.contains(first), "the exact instance must be present");
        require(!set.contains(equalButDistinct), "equal values must not share identity markers");
        require(set.remove(first), "the exact instance must be removable");
        require(set.size() == 0, "explicit removal must release the marker");

        EqualValue collectible = new EqualValue(2);
        WeakReference<EqualValue> weakReference = new WeakReference<>(collectible);
        set.add(collectible);
        collectible = null;
        for (int attempt = 0; attempt < 100 && weakReference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(5L);
        }
        require(weakReference.get() == null, "the set must not keep packet instances alive");
        require(set.size() == 0, "collected identity markers must be drained");
        System.out.println("Weak identity set regression passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record EqualValue(int value) {
    }
}
