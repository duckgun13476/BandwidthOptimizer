package com.PinkCats.bandwidthoptimizer.report;

import com.google.gson.Gson;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import java.util.List;

public final class EntitySyncDetailRegressionMain {
    public static void main(String[] args) throws Exception {
        System.setProperty("bandwidthoptimizer.transport.sourceRankReportIntervalMillis", "0");
        var counters = new EntitySyncDetailCounters();
        counters.record(new EntitySyncDetailCounters.Detail("metadata", "mod:vehicle", List.of("0", "2"), 0));
        counters.record(new EntitySyncDetailCounters.Detail("attributes", "<unknown>", List.of("mod:mass"), 2));
        var first = counters.snapshot(100, false);
        require(first.packets() == 2 && first.unknownTypePackets() == 1 && first.omittedFields() == 2, "coverage");
        require(first.entries().size() == 3 && first.entries().stream().allMatch(e -> e.occurrences() == 1), "individual occurrences");
        require(counters.snapshot(100, true).equals(first), "read does not drain");
        require(counters.snapshot(100, false).windowPackets() == 0 && counters.snapshot(100, false).packets() == 2, "window reset");
        for (int i = 0; i < 5000; i++) counters.record(new EntitySyncDetailCounters.Detail("attributes", "mod:t", List.of("mod:a" + i), 0));
        var bounded = counters.snapshot(Integer.MAX_VALUE, false);
        require(bounded.trackedKeys() == 4096 && bounded.entries().size() <= 200 && bounded.omittedFields() == 909, "fixed capacity and omission accounting");
        require(counters.snapshot(0, false).entries().isEmpty(), "zero export");
        require(new Gson().fromJson(new Gson().toJson(bounded), EntitySyncDetailCounters.Snapshot.class).equals(bounded), "JSON roundtrip");

        // Actual Forge1.20.1 packet objects, no server/world lookup or network restart.
        var channel = new EmbeddedChannel(new io.netty.channel.ChannelDuplexHandler());
        var ctx = channel.pipeline().firstContext();
        var otherChannel = new EmbeddedChannel(new io.netty.channel.ChannelDuplexHandler());
        var other = otherChannel.pipeline().firstContext();
        var metadataClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
        Packet<?> metadata = (Packet<?>) metadataClass.getConstructor(int.class, List.class).newInstance(7, List.of());
        var dataValueClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
        var serializerClass = Class.forName("net.minecraft.network.syncher.EntityDataSerializer");
        Object field = dataValueClass.getConstructor(int.class, serializerClass, Object.class).newInstance(2, null, (byte) 1);
        Packet<?> populatedMetadata = (Packet<?>) metadataClass.getConstructor(int.class, List.class).newInstance(7, List.of(field));
        var tracker = new EntitySyncSourceTracker();
        require(tracker.observe("ClientboundSetEntityDataPacket", null, new byte[]{1, 7}).equals("<unknown>"), "before spawn unknown");
        tracker.observe("ClientboundAddEntityPacket", "minecraft:pig", new byte[]{1, 7});
        require(tracker.observe("ClientboundSetEntityDataPacket", null, new byte[]{1, 7}).equals("minecraft:pig"), "spawn type");
        tracker.observe("ClientboundRemoveEntitiesPacket", null, new byte[]{1, 1, 7});
        require(tracker.observe("ClientboundSetEntityDataPacket", null, new byte[]{1, 7}).equals("<unknown>"), "remove invalidates");
        tracker.observe("ClientboundAddEntityPacket", "mod:vehicle", new byte[]{1, 7});
        require(tracker.observe("ClientboundSetEntityDataPacket", null, new byte[]{1, 7}).equals("mod:vehicle"), "ID reuse");
        tracker.observe("ClientboundLoginPacket", null, new byte[]{1, 0, 0, 0, 9});
        require(tracker.observe("ClientboundSetEntityDataPacket", null, new byte[]{1, 7}).equals("<unknown>"), "login clears");
        tracker.observe("ClientboundAddEntityPacket", "minecraft:pig", new byte[]{1, 7});
        tracker.observe("ClientboundRespawnPacket", null, new byte[]{1});
        require(tracker.observe("ClientboundSetEntityDataPacket", null, new byte[]{1, 7}).equals("<unknown>"), "respawn clears old dimension");
        require(tracker.observe("ClientboundUpdateAttributesPacket", null, new byte[]{1, 9}).equals("minecraft:player"), "respawn retains own player");
        var cappedTracker = new EntitySyncSourceTracker();
        for (int i = 0; i <= EntitySyncSourceTracker.MAX_ENTITIES; i++) cappedTracker.observe("ClientboundAddEntityPacket", "mod:t", encodedId(i));
        require(cappedTracker.observe("ClientboundSetEntityDataPacket", null, encodedId(0)).equals("<unknown>"), "capacity clears stale identities");
        require(cappedTracker.observe("ClientboundSetEntityDataPacket", null, encodedId(EntitySyncSourceTracker.MAX_ENTITIES)).equals("mod:t"), "capacity keeps current identity");
        cappedTracker.observe("ClientboundRemoveEntitiesPacket", null, new byte[]{1, 1});
        require(cappedTracker.observe("ClientboundSetEntityDataPacket", null, encodedId(EntitySyncSourceTracker.MAX_ENTITIES)).equals("<unknown>"), "malformed removal cannot preserve stale attribution");
        tracker.observe("ClientboundStartConfigurationPacket", null, new byte[]{1});
        tracker.observe("ClientboundAddEntityPacket", "minecraft:pig", new byte[]{1, 7});
        ctx.channel().attr(io.netty.util.AttributeKey.<EntitySyncSourceTracker>valueOf("bandwidthoptimizer.report.detailed_entities")).set(tracker);
        var detail = ChannelTransportPacketRankSourceResolver.resolveEntitySyncDetail(ctx, populatedMetadata, new byte[]{1, 7});
        require(detail.entityType().equals("minecraft:pig") && detail.fields().equals(List.of("2")), "real packet field ID");
        require(ChannelTransportPacketRankSourceResolver.resolveEntitySyncDetail(other, metadata, new byte[]{1, 7}).entityType().equals("<unknown>"), "channel isolation");
        var captures = new java.util.ArrayList<ChannelTransportPacketRankCaptureManager.OutboundPacketCapture>();
        for (int i = 0; i < 4; i++) captures.add(ChannelTransportPacketRankCaptureManager.beginOutboundPacketCapture(ctx, "PLAY", populatedMetadata, new byte[]{1, 7, 2}, new byte[]{1, 7, 2}, false));
        ChannelTransportPacketRankCaptureManager.recordDirectPassthrough(ctx, "PLAY", populatedMetadata, new byte[]{1, 7, 2});
        ChannelTransportPacketRankCaptureManager.completeSingleDirectFallbackCapture(captures.get(0), 3);
        ChannelTransportPacketRankCaptureManager.completeDirectFallbackCapture(List.of(captures.get(1)));
        // Single-entry and shared-batch completion use the same recorded metadata.
        var frame = new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec.WrappedTransportFrameMetadata(
                com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec.FrameKind.BATCH, 4, 6, 2, 4, null);
        ChannelTransportPacketRankCaptureManager.completeBatchTransportCaptureMetadata(captures.subList(2, 4), frame);
        var single = ChannelTransportPacketRankCaptureManager.beginOutboundPacketCapture(ctx, "PLAY", populatedMetadata, new byte[]{1, 7, 2}, new byte[]{1, 7, 2}, false);
        ChannelTransportPacketRankCaptureManager.completeSingleTransportCapture(single,
                new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec.WrappedTransportFrame(
                        com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec.FrameKind.SINGLE, new byte[2], 3, 1, 2, null));
        var batchSingle = ChannelTransportPacketRankCaptureManager.beginOutboundPacketCapture(ctx, "PLAY", populatedMetadata, new byte[]{1, 7, 2}, new byte[]{1, 7, 2}, false);
        ChannelTransportPacketRankCaptureManager.completeBatchTransportCaptureMetadata(List.of(batchSingle),
                new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec.WrappedTransportFrameMetadata(
                        com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec.FrameKind.BATCH, 3, 3, 1, 3, null));
        // An admitted but never completed capture must not be counted.
        ChannelTransportPacketRankCaptureManager.beginOutboundPacketCapture(ctx, "PLAY", populatedMetadata, new byte[]{1, 7, 2}, new byte[]{1, 7, 2}, false);
        var attributes = (Packet<?>) Class.forName("net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket")
                .getConstructor(int.class, java.util.Collection.class).newInstance(7, List.of());
        ChannelTransportPacketRankCaptureManager.recordDirectPassthrough(ctx, "PLAY", attributes, new byte[]{1, 7, 0});
        var report = ChannelTransportSourceRankCore.snapshotForReport(100);
        var entry = report.entries().stream().filter(e -> e.sourceKey().equals("entity_metadata:minecraft:pig")).findFirst().orElseThrow();
        require(entry.packets() == 7 && entry.rawBytes() == 21 && entry.actualBytes() == 18 && entry.estimatedPackets() == 2, "all completion paths, no duplicate bytes");
        require(report.entries().stream().anyMatch(e -> e.sourceKey().equals("entity_attributes:minecraft:pig") && e.packets() == 1), "attributes type attribution including empty packet");
        require(report.entitySync().packets() == 8 && report.entitySync().entries().get(0).occurrences() == 7, "only completed captures count");
        channel.finishAndReleaseAll();
        otherChannel.finishAndReleaseAll();
        System.out.println("Entity sync detail regression passed.");
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static byte[] encodedId(int value) {
        var buffer = new java.io.ByteArrayOutputStream();
        buffer.write(1);
        do {
            int next = value & 127;
            value >>>= 7;
            buffer.write(next | (value == 0 ? 0 : 128));
        } while (value != 0);
        return buffer.toByteArray();
    }
}
