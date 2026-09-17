package com.PinkCats.bandwidthoptimizer.recipe;

import com.PinkCats.bandwidthoptimizer.Config;

public final class RecipeSyncRuntimeConfig {

    private RecipeSyncRuntimeConfig() {}

    public static boolean isEnabled() {
        return Config.enablePersistentRecipeDelta;
    }
}
