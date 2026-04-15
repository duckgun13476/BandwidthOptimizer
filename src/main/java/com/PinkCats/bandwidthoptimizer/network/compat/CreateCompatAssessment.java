package com.PinkCats.bandwidthoptimizer.network.compat;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class CreateCompatAssessment {

    public static final Set<ResourceLocation> VERIFIED_SAFE_REPLAY = Set.of(
            id("server_speed"),
            id("clientbound_chain_conveyor"),
            id("sync_edge_group"),
            id("contraption_block_changed"),
            id("contraption_relocation"),
            id("contraption_stall"),
            id("contraption_seat_mapping"),
            id("mounted_storage_sync"),
            id("add_train"),
            id("remove_train"),
            id("s_configure_train"),
            id("s_train_prompt")
    );

    public static final Set<ResourceLocation> TIMING_SENSITIVE_BUT_REPLAYABLE = Set.of(
            id("logistics_stock_response"),
            id("sync_rail_graph"),
            id("track_graph_roll_call"),
            id("train_map_sync"),
            id("s_train_hud"),
            id("s_place_arm"),
            id("s_place_ejector"),
            id("s_place_package_port")
    );

    public static final Set<ResourceLocation> PROBABLE_HIGH_RISK = Set.of();

    private CreateCompatAssessment() {
    }

    public static boolean isVerifiedSafeReplay(ResourceLocation id) {
        return VERIFIED_SAFE_REPLAY.contains(id);
    }

    public static boolean isTimingSensitiveButReplayable(ResourceLocation id) {
        return TIMING_SENSITIVE_BUT_REPLAYABLE.contains(id);
    }

    public static boolean isProbableHighRisk(ResourceLocation id) {
        return PROBABLE_HIGH_RISK.contains(id);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("create", path);
    }
}
