package com.PinkCats.bandwidthoptimizer.compat.network;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class OPACCompatAssessment {

    public static final Set<ResourceLocation> BYPASS_CUSTOM_PAYLOAD_CHANNELS = Set.of(
            id("main")
    );

    private OPACCompatAssessment() {
    }

    public static boolean shouldBypassCustomPayload(ResourceLocation id) {
        return BYPASS_CUSTOM_PAYLOAD_CHANNELS.contains(id);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("openpartiesandclaims", path);
    }
}
