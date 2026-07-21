package com.PinkCats.bandwidthoptimizer.gate.compat.create;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

final class CreateGateTypePolicy {

    private static final Set<String> SOUND_CLASSIFIED_BLOCK_ENTITY_TYPES = Set.of(
            "cuckoo_clock",
            "deployer",
            "mechanical_arm",
            "mechanical_crafter",
            "mechanical_press",
            "steam_whistle"
    );
    private static final Set<String> IMMEDIATE_CONTROL_BLOCK_ENTITY_TYPES = Set.of(
            "analog_lever",
            "desk_bell",
            "elevator_contact",
            "factory_panel",
            "lectern_controller",
            "redstone_link",
            "redstone_requester",
            "sliding_door",
            "stock_ticker"
    );
    private static final Set<String> MOVING_CONTRAPTION_CONTROLLER_TYPES = Set.of(
            "mechanical_piston",
            "windmill_bearing",
            "mechanical_bearing",
            "clockwork_bearing",
            "rope_pulley",
            "hose_pulley",
            "elevator_pulley",
            "gantry_shaft",
            "gantry_pinion",
            "cart_assembler",
            "contraption_controls"
    );

    private CreateGateTypePolicy() {}

    static boolean isCreateBlockEntity(ResourceLocation typeKey) {
        return typeKey != null && "create".equals(typeKey.getNamespace());
    }

    static boolean shouldGate(ResourceLocation typeKey) {
        return isCreateBlockEntity(typeKey) && !isImmediateControl(typeKey);
    }

    static boolean isSoundClassified(ResourceLocation typeKey) {
        return isCreateBlockEntity(typeKey)
                && SOUND_CLASSIFIED_BLOCK_ENTITY_TYPES.contains(typeKey.getPath());
    }

    static boolean isMovingContraptionController(ResourceLocation typeKey) {
        return isCreateBlockEntity(typeKey)
                && MOVING_CONTRAPTION_CONTROLLER_TYPES.contains(typeKey.getPath());
    }

    static boolean isVisibleRawBypassController(ResourceLocation typeKey) {
        return isCreateBlockEntity(typeKey)
                && "mechanical_piston".equals(typeKey.getPath());
    }

    static boolean allowLookDirectionForGatedUpdate(ResourceLocation typeKey, boolean chunkBootstrapActive) {
        return isVisibleRawBypassController(typeKey) || !chunkBootstrapActive;
    }

    static double soundSendDistanceBlocks(ResourceLocation typeKey) {
        if (typeKey != null && "steam_whistle".equals(typeKey.getPath())) {
            return 64.0D;
        }
        if (typeKey != null && "cuckoo_clock".equals(typeKey.getPath())) {
            return 32.0D;
        }
        return 16.0D;
    }

    private static boolean isImmediateControl(ResourceLocation typeKey) {
        return isCreateBlockEntity(typeKey)
                && IMMEDIATE_CONTROL_BLOCK_ENTITY_TYPES.contains(typeKey.getPath());
    }
}
