package com.PinkCats.bandwidthoptimizer.network.message;

import com.PinkCats.bandwidthoptimizer.Config;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundServerConfigPacket(Config.RuntimeConfig config) {

    public static ClientboundServerConfigPacket current() {
        return new ClientboundServerConfigPacket(Config.currentLocalRuntimeConfig());
    }

    public static void encode(ClientboundServerConfigPacket packet, FriendlyByteBuf buffer) {
        Config.RuntimeConfig config = packet.config();
        buffer.writeBoolean(config.enableBatchReferenceDedup());
        buffer.writeBoolean(config.enableBatchSha256Dictionary());
        buffer.writeBoolean(config.enableBatchTemplateDictionary());
        buffer.writeBoolean(config.enableBatchZstd());
        buffer.writeBoolean(config.enableBatchStreamingZstd());
        buffer.writeBoolean(config.enableAsyncPlayBatchEncoding());
        buffer.writeVarInt(config.batchZstdLevel());
        buffer.writeVarInt(config.batchStreamingZstdLevel());
        buffer.writeVarInt(config.batchSha256DictionaryMaxPacketBytes());
        buffer.writeVarInt(config.batchSha256DictionaryMaxEntries());
        buffer.writeVarInt(config.batchSha256DictionaryMaxPayloadBytes());
        buffer.writeVarInt(config.batchTemplateDictionaryMaxPacketBytes());
        buffer.writeVarInt(config.batchTemplateDictionaryMaxEntries());
        buffer.writeVarInt(config.batchTemplateDictionaryMaxPayloadBytes());
        buffer.writeVarInt(config.batchTemplateDictionaryMaxDiffRuns());
        buffer.writeVarInt(config.batchTemplateDictionaryMaxChangedBytes());
        buffer.writeBoolean(config.enableOptimizerStatsLogs());
        buffer.writeBoolean(config.enableTestMode());
        buffer.writeVarInt(config.statsLogIntervalMinutes());
    }

    public static ClientboundServerConfigPacket decode(FriendlyByteBuf buffer) {
        return new ClientboundServerConfigPacket(new Config.RuntimeConfig(
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readVarInt()
        ));
    }

    public static void handle(ClientboundServerConfigPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> Config.applyRuntimeConfig(packet.config())
        );
        context.setPacketHandled(true);
    }
}
