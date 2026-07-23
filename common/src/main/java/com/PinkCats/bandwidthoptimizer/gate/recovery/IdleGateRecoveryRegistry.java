package com.PinkCats.bandwidthoptimizer.gate.recovery;

import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateGateTypePolicy;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateGeneralBlockEntityRecoveryPolicy;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateTransferBlockEntityRecoveryPolicy;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateWorkerBlockEntityRecoveryPolicy;
import com.PinkCats.bandwidthoptimizer.gate.integration.farm_and_charm.FarmAndCharmSaturationRecoveryPolicy;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.BlockEntityTypeKeyCompat;
import io.netty.channel.Channel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IdleGateRecoveryRegistry {

    private static final FarmAndCharmSaturationRecoveryPolicy FARM_AND_CHARM = new FarmAndCharmSaturationRecoveryPolicy();
    private static final AttributeRecoveryPolicy ATTRIBUTES = new AttributeRecoveryPolicy();
    private static final EntityMotionRecoveryPolicy ENTITY_MOTION = new EntityMotionRecoveryPolicy();
    private static final VanillaBlockStateRecoveryPolicy VANILLA_BLOCK_STATES = new VanillaBlockStateRecoveryPolicy();
    private static final CreateTransferBlockEntityRecoveryPolicy CREATE_TRANSFER = new CreateTransferBlockEntityRecoveryPolicy();
    private static final CreateWorkerBlockEntityRecoveryPolicy CREATE_WORKER = new CreateWorkerBlockEntityRecoveryPolicy();
    private static final CreateGeneralBlockEntityRecoveryPolicy CREATE_GENERAL = new CreateGeneralBlockEntityRecoveryPolicy();
    private static final IdleGateRecoveryPolicy[] POLICIES = {
            VANILLA_BLOCK_STATES,
            FARM_AND_CHARM,
            ATTRIBUTES,
            ENTITY_MOTION,
            CREATE_TRANSFER,
            CREATE_WORKER,
            CREATE_GENERAL
    };
    private static final ConcurrentHashMap<UUID, ServerPlayer> PENDING_RESTORES = new ConcurrentHashMap<>();

    private IdleGateRecoveryRegistry() {}

    public static boolean tryCapture(Channel channel, Packet<?> packet, Object listener) {
        if (channel == null || packet == null || listener != null) {
            return false;
        }
        IdleGateServerState.PlayerIdleState state = IdleGateServerState.snapshot(channel);
        if (!state.mode().suppressesWorldPresentation()) {
            return false;
        }
        return tryCaptureSpecialized(channel, packet);
    }

    public static boolean tryCaptureBackground(
            Channel channel,
            Packet<?> packet,
            IdleGateServerState.PlayerIdleState state
    ) {
        if (channel == null
                || packet == null
                || state == null
                || !state.mode().suppressesWorldPresentation()) {
            return false;
        }
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdatePacket) {
            return VANILLA_BLOCK_STATES.tryCaptureBlockUpdate(channel, blockUpdatePacket);
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket attributesPacket) {
            return ATTRIBUTES.tryCapture(channel, attributesPacket);
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket removeEntitiesPacket) {
            ENTITY_MOTION.observeRemoval(channel, removeEntitiesPacket);
            return false;
        }
        if (packet instanceof ClientboundMoveEntityPacket moveEntityPacket) {
            return ENTITY_MOTION.tryCaptureMovement(channel, moveEntityPacket);
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket) {
            return ENTITY_MOTION.tryCaptureMotion(channel, motionPacket);
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionBlocksUpdatePacket) {
            return VANILLA_BLOCK_STATES.tryCaptureSectionBlocksUpdate(channel, sectionBlocksUpdatePacket);
        }
        return tryCaptureSpecialized(channel, packet);
    }

    public static void discardChunk(ServerPlayer player, net.minecraft.world.level.ChunkPos chunkPos) {
        VANILLA_BLOCK_STATES.discardChunk(player, chunkPos);
    }

    private static boolean tryCaptureSpecialized(Channel channel, Packet<?> packet) {
        if (packet instanceof ClientboundBlockEntityDataPacket blockEntityPacket) {
            if (VANILLA_BLOCK_STATES.tryCaptureBlockEntityData(channel, blockEntityPacket)) {
                return true;
            }
            String typeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityPacket.getType());
            return switch (CreateGateTypePolicy.backgroundRecoveryGroup(typeKey)) {
                case TRANSFER -> CREATE_TRANSFER.tryCaptureBackground(channel, blockEntityPacket, typeKey);
                case WORKER -> CREATE_WORKER.tryCaptureBackground(channel, blockEntityPacket, typeKey);
                case GENERAL -> CREATE_GENERAL.tryCaptureBackground(channel, blockEntityPacket, typeKey);
                case NONE -> false;
            };
        }
        if (!packet.getClass().getName().endsWith(".ClientboundCustomPayloadPacket")) {
            return false;
        }
        return FARM_AND_CHARM.tryCaptureBackground(channel, packet);
    }

    public static void requestRestore(ServerPlayer player) {
        if (player != null) {
            PENDING_RESTORES.put(player.getUUID(), player);
        }
    }

    public static void onServerTick() {
        for (ServerPlayer player : PENDING_RESTORES.values()) {
            if (player == null || !PENDING_RESTORES.remove(player.getUUID(), player)) {
                continue;
            }
            for (IdleGateRecoveryPolicy policy : POLICIES) {
                policy.restore(player);
            }
        }
        for (IdleGateRecoveryPolicy policy : POLICIES) {
            policy.onServerTick();
        }
    }

    public static void discard(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PENDING_RESTORES.remove(player.getUUID());
        for (IdleGateRecoveryPolicy policy : POLICIES) {
            policy.discard(player);
        }
    }
}
