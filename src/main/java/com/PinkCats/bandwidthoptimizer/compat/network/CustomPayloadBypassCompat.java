package com.PinkCats.bandwidthoptimizer.compat.network;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;

public final class CustomPayloadBypassCompat {

    public static final Set<ResourceLocation> CHANNELS = buildChannels();

    private CustomPayloadBypassCompat() {
    }

    public static boolean shouldBypass(ResourceLocation id) {
        return CHANNELS.contains(id);
    }

    private static Set<ResourceLocation> buildChannels() {
        LinkedHashSet<ResourceLocation> channels = new LinkedHashSet<>();
        channels.addAll(OPACCompatAssessment.BYPASS_CUSTOM_PAYLOAD_CHANNELS);
        channels.addAll(XaeroCompatAssessment.BYPASS_CUSTOM_PAYLOAD_CHANNELS);
        channels.addAll(YSMCompatAssessment.BYPASS_CUSTOM_PAYLOAD_CHANNELS);
        return Set.copyOf(channels);
    }
}
