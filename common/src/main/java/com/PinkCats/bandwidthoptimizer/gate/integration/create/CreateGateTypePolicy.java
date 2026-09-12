package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import java.util.Set;

public final class CreateGateTypePolicy {

    public enum BackgroundRecoveryGroup {
        NONE,
        TRANSFER,
        WORKER,
        GENERAL
    }

    enum ContraptionReferenceKind {
        PISTON,
        BEARING,
        CLOCKWORK,
        PULLEY,
        REGISTRY_ONLY
    }

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
            "gantry_pinion"
    );
    private static final Set<String> TRANSFER_BLOCK_ENTITY_TYPES = Set.of(
            "andesite_funnel",
            "brass_funnel",
            "belt",
            "chute",
            "smart_chute"
    );
    private static final Set<String> WORKER_BLOCK_ENTITY_TYPES = Set.of(
            "deployer",
            "mechanical_arm",
            "mechanical_crafter",
            "mechanical_drill",
            "mechanical_mixer",
            "mechanical_press",
            "mechanical_saw"
    );

    private CreateGateTypePolicy() {}

    static boolean isCreateBlockEntity(String typeKey) {
        return typeKey != null && typeKey.startsWith("create:");
    }

    static boolean shouldGate(String typeKey) {
        return isCreateBlockEntity(typeKey) && !isImmediateControl(typeKey);
    }

    static boolean shouldHoldWhileBackground(String typeKey) {
        return shouldGate(typeKey) && !isMovingContraptionController(typeKey);
    }

    static boolean isTransferBlockEntity(String typeKey) {
        return shouldHoldWhileBackground(typeKey)
                && TRANSFER_BLOCK_ENTITY_TYPES.contains(path(typeKey));
    }

    static boolean isWorkerBlockEntity(String typeKey) {
        return shouldHoldWhileBackground(typeKey)
                && WORKER_BLOCK_ENTITY_TYPES.contains(path(typeKey));
    }

    static boolean shouldHoldGeneralWhileBackground(String typeKey) {
        return shouldHoldWhileBackground(typeKey)
                && !isTransferBlockEntity(typeKey)
                && !isWorkerBlockEntity(typeKey);
    }

    public static BackgroundRecoveryGroup backgroundRecoveryGroup(String typeKey) {
        if (!CreateBlockEntityUpdateGate.isEnabled() || !shouldHoldWhileBackground(typeKey)) {
            return BackgroundRecoveryGroup.NONE;
        }
        if (isTransferBlockEntity(typeKey)) {
            return BackgroundRecoveryGroup.TRANSFER;
        }
        if (isWorkerBlockEntity(typeKey)) {
            return BackgroundRecoveryGroup.WORKER;
        }
        return BackgroundRecoveryGroup.GENERAL;
    }

    static boolean isSoundClassified(String typeKey) {
        return isCreateBlockEntity(typeKey)
                && SOUND_CLASSIFIED_BLOCK_ENTITY_TYPES.contains(path(typeKey));
    }

    static boolean isMovingContraptionController(String typeKey) {
        return isCreateBlockEntity(typeKey)
                && MOVING_CONTRAPTION_CONTROLLER_TYPES.contains(path(typeKey));
    }

    static ContraptionReferenceKind contraptionReferenceKind(String typeKey) {
        return switch (path(typeKey)) {
            case "mechanical_piston" -> ContraptionReferenceKind.PISTON;
            case "windmill_bearing", "mechanical_bearing" -> ContraptionReferenceKind.BEARING;
            case "clockwork_bearing" -> ContraptionReferenceKind.CLOCKWORK;
            case "rope_pulley", "hose_pulley", "elevator_pulley" -> ContraptionReferenceKind.PULLEY;
            default -> ContraptionReferenceKind.REGISTRY_ONLY;
        };
    }

    static boolean isVisibleRawBypassController(String typeKey) {
        return isCreateBlockEntity(typeKey)
                && "mechanical_piston".equals(path(typeKey));
    }

    static boolean allowLookDirectionForGatedUpdate(String typeKey, boolean chunkBootstrapActive) {
        return isVisibleRawBypassController(typeKey) || !chunkBootstrapActive;
    }

    static double soundSendDistanceBlocks(String typeKey) {
        if ("steam_whistle".equals(path(typeKey))) {
            return 64.0D;
        }
        if ("cuckoo_clock".equals(path(typeKey))) {
            return 32.0D;
        }
        return 16.0D;
    }

    private static boolean isImmediateControl(String typeKey) {
        return isCreateBlockEntity(typeKey)
                && IMMEDIATE_CONTROL_BLOCK_ENTITY_TYPES.contains(path(typeKey));
    }

    private static String path(String typeKey) {
        if (typeKey == null) {
            return "";
        }
        int namespaceSeparator = typeKey.indexOf(':');
        return namespaceSeparator < 0 ? typeKey : typeKey.substring(namespaceSeparator + 1);
    }
}
