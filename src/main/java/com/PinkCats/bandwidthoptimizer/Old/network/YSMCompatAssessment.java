package com.PinkCats.bandwidthoptimizer.Old.network;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class YSMCompatAssessment {

    public static final Set<ResourceLocation> BYPASS_CUSTOM_PAYLOAD_CHANNELS = Set.of(
            id("2_6_0")
    );

    private YSMCompatAssessment() {
    }

    public static boolean shouldBypassCustomPayload(ResourceLocation id) {
        return BYPASS_CUSTOM_PAYLOAD_CHANNELS.contains(id);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("yes_steve_model", path);
    }
}
