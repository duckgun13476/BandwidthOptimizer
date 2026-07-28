package com.PinkCats.bandwidthoptimizer.channel.packet;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEnterPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEndPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.Set;

public final class ChannelTransportBypassPacketList {

    // These packet can't use in velocity
    private static final Set<Class<?>> PACKET_CLASSES = Set.of(
            ClientboundAddEntityPacket.class,
            ClientboundBlockUpdatePacket.class,
            ClientboundCommandsPacket.class,
            ClientboundCommandSuggestionsPacket.class,
            ClientboundEntityEventPacket.class,
            ClientboundForgetLevelChunkPacket.class,
            ClientboundLoginPacket.class,
            ClientboundMoveEntityPacket.Pos.class,
            ClientboundMoveEntityPacket.PosRot.class,
            ClientboundMoveEntityPacket.Rot.class,
            ClientboundPlayerCombatEnterPacket.class,
            ClientboundPlayerCombatEndPacket.class,
            ClientboundPlayerPositionPacket.class,
            ClientboundRemoveEntitiesPacket.class,
            ClientboundRotateHeadPacket.class,
            ClientboundSetChunkCacheCenterPacket.class,
            ClientboundSetChunkCacheRadiusPacket.class,
            ClientboundSetEntityMotionPacket.class,
            ClientboundSetExperiencePacket.class,
            ClientboundSetHealthPacket.class,
            ClientboundSetTimePacket.class,
            ClientboundSoundPacket.class,
            ClientboundTeleportEntityPacket.class,
            ServerboundAcceptTeleportationPacket.class,
            ServerboundCommandSuggestionPacket.class,
            ServerboundMovePlayerPacket.Pos.class,
            ServerboundMovePlayerPacket.PosRot.class,
            ServerboundMovePlayerPacket.Rot.class,
            ServerboundMovePlayerPacket.StatusOnly.class,
            ServerboundPlayerActionPacket.class,
            ServerboundSwingPacket.class,
            ServerboundUseItemOnPacket.class,
            ServerboundUseItemPacket.class
    );
    private static final Set<String> PACKET_CLASS_NAMES = Set.of(
            "net.minecraft.network.protocol.BundleDelimiterPacket",
            "net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket",
            "net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket",
            "net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket",
            "net.minecraft.network.protocol.game.ClientboundRecipePacket",
            "net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket",
            "net.minecraft.network.protocol.game.ClientboundDamageEventPacket",
            "net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket",
            "net.minecraft.network.protocol.game.ServerboundChatCommandPacket",
            "net.minecraft.network.protocol.game.ServerboundChatPacket"
    );
    private static final Set<String> CLIENTBOUND_KEEP_ALIVE_PACKET_CLASS_NAMES = Set.of(
            "net.minecraft.network.protocol.common.ClientboundKeepAlivePacket",
            "net.minecraft.network.protocol.game.ClientboundKeepAlivePacket"
    );

    private ChannelTransportBypassPacketList() {}

    // Some packet is useless when use intregated algorithm
    public static boolean shouldBypassTransparentTransport(Packet<?> packet) {
        return packet != null && (
                PACKET_CLASSES.contains(packet.getClass())
                        || PACKET_CLASS_NAMES.contains(packet.getClass().getName())
                        || CLIENTBOUND_KEEP_ALIVE_PACKET_CLASS_NAMES.contains(packet.getClass().getName())
        );
    }
}
