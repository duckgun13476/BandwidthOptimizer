package com.PinkCats.bandwidthoptimizer.channel.packet;

import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEnterPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEndPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;

import java.util.Set;

public final class ChannelTransportBypassPacketList {

    private static final Set<Class<?>> PACKET_CLASSES = Set.of(
            ClientboundAddEntityPacket.class,
            ClientboundBlockUpdatePacket.class,
            ClientboundContainerSetSlotPacket.class,
            ClientboundDamageEventPacket.class,
            ClientboundEntityEventPacket.class,
            ClientboundForgetLevelChunkPacket.class,
            ClientboundHurtAnimationPacket.class,
            ClientboundMoveEntityPacket.PosRot.class,
            ClientboundMoveEntityPacket.Rot.class,
            ClientboundPlayerCombatEnterPacket.class,
            ClientboundPlayerCombatEndPacket.class,
            ClientboundPlayerInfoUpdatePacket.class,
            ClientboundPlayerPositionPacket.class,
            ClientboundRemoveEntitiesPacket.class,
            ClientboundRotateHeadPacket.class,
            ClientboundSetChunkCacheCenterPacket.class,
            ClientboundSetChunkCacheRadiusPacket.class,
            ClientboundSetEntityDataPacket.class,
            ClientboundSetEntityMotionPacket.class,
            ClientboundSetEquipmentPacket.class,
            ClientboundSetExperiencePacket.class,
            ClientboundSetHealthPacket.class,
            ClientboundSetTimePacket.class,
            ClientboundSoundPacket.class,
            ClientboundTeleportEntityPacket.class
    );
    private static final Set<String> CLIENTBOUND_KEEP_ALIVE_PACKET_CLASS_NAMES = Set.of(
            "net.minecraft.network.protocol.common.ClientboundKeepAlivePacket",
            "net.minecraft.network.protocol.game.ClientboundKeepAlivePacket"
    );

    private ChannelTransportBypassPacketList() {}

    // Some packet is useless when use intregated algorithm
    public static boolean shouldBypassTransparentTransport(Packet<?> packet) {
        return packet instanceof BundleDelimiterPacket<?>
                || (packet != null && (
                PACKET_CLASSES.contains(packet.getClass())
                        || CLIENTBOUND_KEEP_ALIVE_PACKET_CLASS_NAMES.contains(packet.getClass().getName())
        ));
    }
}
