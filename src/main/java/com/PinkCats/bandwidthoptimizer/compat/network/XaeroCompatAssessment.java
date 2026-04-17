package com.PinkCats.bandwidthoptimizer.compat.network;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class XaeroCompatAssessment {

    public static final Set<ResourceLocation> BYPASS_CUSTOM_PAYLOAD_CHANNELS = Set.of(
            id("xaeroworldmap", "main"),
            id("xaerominimap", "main")
    );

    private XaeroCompatAssessment() {
    }

    public static boolean shouldBypassCustomPayload(ResourceLocation id) {
        return BYPASS_CUSTOM_PAYLOAD_CHANNELS.contains(id);
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
