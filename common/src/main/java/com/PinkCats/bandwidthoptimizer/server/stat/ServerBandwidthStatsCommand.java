package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

public final class ServerBandwidthStatsCommand {

    private static final int DEFAULT_PLAYER_LIMIT = 10;

    private ServerBandwidthStatsCommand() {
    }

    public static ArgumentBuilder<CommandSourceStack, ?> buildCommand() {
        return Commands.literal("stats")
                .requires(source -> source.hasPermission(2))
                .executes(context -> total(context.getSource()))
                .then(Commands.literal("total")
                        .executes(context -> total(context.getSource())))
                .then(Commands.literal("players")
                        .executes(context -> players(context.getSource(), DEFAULT_PLAYER_LIMIT))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 100))
                                .executes(context -> players(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("reset")
                        .executes(context -> reset(context.getSource())));
    }


    private static int total(CommandSourceStack source) {
        ServerBandwidthStatsRegistry.TotalsSnapshot totals =
                ServerBandwidthStatsPersistence.snapshotTotals(source.getServer());
        ServerBandwidthStatsRegistry.TotalsSnapshot sessionTotals =
                ServerBandwidthStatsRegistry.snapshotSessionTotals();
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO stats total: channels=" + totals.activeChannels()
                        + ", players=" + totals.boundPlayers()
                        + ", outRaw=" + formatBytes(totals.outboundRawEncodedBytes())
                        + "/" + totals.outboundRawEncodedPackets() + " packets"
                        + ", outVanilla=" + formatBytes(totals.outboundVanillaCompressedEstimateBytes())
                        + ", outTransport=" + formatBytes(totals.outboundTransportFrameBytes())
                        + "/" + totals.outboundTransportFrames() + " frames"
                        + ", outBypass=" + formatBytes(totals.outboundBypassBytes())
                        + "/" + totals.outboundBypassPackets() + " packets"
                        + ", outWire=" + formatBytes(totals.outboundWireBytes())
                        + ", inWire=" + formatBytes(totals.inboundWireBytes())
                        + ", estSaved=" + formatBytes(totals.outboundSavedBytes())
                        + ", sessionOfflineReuse=" + formatBytes(sessionTotals.serverOfflineReuseConfirmedSavedBytes())
                        + "/" + sessionTotals.serverOfflineReuseConfirmedFrames() + " frames"
                        + ", sessionTemporaryReuse=" + formatBytes(sessionTotals.serverTemporaryReuseSavedBytes())
                        + ", sessionCreateGate=" + formatBytes(sessionTotals.serverCreateGateSavedBytes())
                        + "/" + ratioText(
                                sessionTotals.serverCreateGateSavedBytes(),
                                sessionTotals.serverCreateGateObservedBytes()
                        )
                        + ", estRatio=" + ratioText(
                                totals.outboundWireBytes(),
                                totals.outboundVanillaCompressedEstimateBytes()
                        )
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int players(CommandSourceStack source, int limit) {
        List<ChannelBandwidthStats.Snapshot> snapshots =
                ServerBandwidthStatsPersistence.snapshotPlayers(source.getServer(), limit);
        if (snapshots.isEmpty()) {
            CommandSourceCompat.sendSuccess(source, Component.literal("BO stats players: no persisted players."), false);
            return Command.SINGLE_SUCCESS;
        }

        int safeLimit = Math.max(limit, 1);
        StringBuilder builder = new StringBuilder("BO stats players top ").append(Math.min(safeLimit, snapshots.size())).append(":");
        int emitted = 0;
        for (ChannelBandwidthStats.Snapshot snapshot : snapshots) {
            if (emitted >= safeLimit) {
                break;
            }
            emitted++;
            builder.append("\n#").append(emitted)
                    .append(' ')
                    .append(playerLabel(snapshot))
                    .append(" outWire=").append(formatBytes(snapshot.outboundWireBytes()))
                    .append(", inWire=").append(formatBytes(snapshot.inboundWireBytes()))
                    .append(", outRaw=").append(formatBytes(snapshot.outboundRawEncodedBytes()))
                    .append(", outVanilla=").append(formatBytes(snapshot.outboundVanillaCompressedEstimateBytes()))
                    .append(", outTransport=").append(formatBytes(snapshot.outboundTransportFrameBytes()))
                    .append(", outBypass=").append(formatBytes(snapshot.outboundBypassBytes()))
                    .append(", estRatio=").append(ratioText(
                            snapshot.outboundWireBytes(),
                            snapshot.outboundVanillaCompressedEstimateBytes()
                    ));
        }

        CommandSourceCompat.sendSuccess(source, Component.literal(builder.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int reset(CommandSourceStack source) {
        ServerBandwidthStatsRegistry.resetAll();
        ServerBandwidthStatsPersistence.resetAll(source.getServer());
        CommandSourceCompat.sendSuccess(source, Component.literal("BO stats reset complete."), true);
        return Command.SINGLE_SUCCESS;
    }

    private static String playerLabel(ChannelBandwidthStats.Snapshot snapshot) {
        if (snapshot.playerName() != null && !snapshot.playerName().isBlank()) {
            return snapshot.playerName();
        }
        String channelId = snapshot.channelId();
        if (channelId == null || channelId.length() <= 12) {
            return "channel=" + channelId;
        }
        return "channel=" + channelId.substring(0, 12);
    }

    private static String formatBytes(long bytes) {
        long absoluteBytes = Math.abs(bytes);
        if (absoluteBytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unitIndex = 0;
        while (Math.abs(value) >= 1024.0D && unitIndex < units.length - 1) {
            value /= 1024.0D;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex]);
    }

    private static String ratioText(long currentBytes, long baselineBytes) {
        if (baselineBytes <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f%%", (double) currentBytes * 100.0D / (double) baselineBytes);
    }
}
