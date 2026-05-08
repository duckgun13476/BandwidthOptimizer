package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ChannelTransportPacketRankCommand {

    private static final int DEFAULT_CAPTURE_TICKS = 400;

    private ChannelTransportPacketRankCommand() {}


    public static ArgumentBuilder<CommandSourceStack, ?> buildCommand() {
        return Commands.literal("packetrank")
                .executes(context -> root(context.getSource()))
                .then(Commands.literal("run")
                        .executes(context -> run(context.getSource(), DEFAULT_CAPTURE_TICKS))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(20))
                                .executes(context -> run(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "ticks")
                                ))))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())));
    }

    private static int root(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "Packet rank commands: /bandwidthoptimizer test packetrank run [ticks] | status"
                        + " ; defaultTicks=" + DEFAULT_CAPTURE_TICKS
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    // packet rank analysis
    private static int run(CommandSourceStack source, int captureTicks) {
        if (!DebugRuntimeConfig.isAnalysisEnabled()) {
            source.sendFailure(Component.literal(
                    "Packet rank capture is disabled. Set bandwidthoptimizer.debug.analysis=true first."
            ));
            return 0;
        }
        ChannelTransportPacketRankCaptureManager.StartResult startResult =
                ChannelTransportPacketRankCaptureManager.startCapture(source.getServer(), source, captureTicks);
        ChannelTransportPacketRankCaptureManager.StatusSnapshot statusSnapshot = startResult.statusSnapshot();
        if (!startResult.started()) {
            source.sendFailure(Component.literal(
                    "Packet rank capture is already running. remainingTicks="
                            + statusSnapshot.remainingTicks()
                            + ", capturedPackets="
                            + statusSnapshot.capturedPacketCount()
                            + ", capturedRawBytes="
                            + statusSnapshot.capturedRawPacketBytes()
            ));
            return 0;
        }

        CommandSourceCompat.sendSuccess(source, Component.literal(
                "Packet rank capture started. ticks="
                        + captureTicks
                        + ", outputDir=bandwidthoptimizer-native/transport-packet-rank"
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    // packet rank status
    private static int status(CommandSourceStack source) {
        if (!DebugRuntimeConfig.isAnalysisEnabled()) {
            CommandSourceCompat.sendSuccess(source, Component.literal("Packet rank capture is disabled."), false);
            return Command.SINGLE_SUCCESS;
        }
        ChannelTransportPacketRankCaptureManager.StatusSnapshot statusSnapshot =
                ChannelTransportPacketRankCaptureManager.snapshotCurrentStatus(source.getServer());
        if (!statusSnapshot.running()) {
            CommandSourceCompat.sendSuccess(source, Component.literal("Packet rank capture is idle."), false);
            return Command.SINGLE_SUCCESS;
        }

        CommandSourceCompat.sendSuccess(source, Component.literal(
                "Packet rank capture running. remainingTicks="
                        + statusSnapshot.remainingTicks()
                        + ", capturedPackets="
                        + statusSnapshot.capturedPacketCount()
                        + ", capturedRawBytes="
                        + statusSnapshot.capturedRawPacketBytes()
        ), false);
        return Command.SINGLE_SUCCESS;
    }
}
