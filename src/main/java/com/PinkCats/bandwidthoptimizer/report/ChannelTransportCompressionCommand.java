package com.PinkCats.bandwidthoptimizer.report;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ChannelTransportCompressionCommand {

    private static final int DEFAULT_CAPTURE_TICKS = 200;

    private ChannelTransportCompressionCommand() {}

    public static ArgumentBuilder<CommandSourceStack, ?> buildCommand() {
        return Commands.literal("transportreport")
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
        source.sendSuccess(() -> Component.literal(
                "Transport report commands: /bandwidthoptimizer test transportreport run [ticks] | status"
                        + " ; defaultTicks=" + DEFAULT_CAPTURE_TICKS
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int run(CommandSourceStack source, int captureTicks) {
        ChannelTransportCompressionCaptureManager.StartResult startResult =
                ChannelTransportCompressionCaptureManager.startCapture(source.getServer(), source, captureTicks);
        ChannelTransportCompressionCaptureManager.StatusSnapshot statusSnapshot = startResult.statusSnapshot();
        if (!startResult.started()) {
            source.sendFailure(Component.literal(
                    "Transport report is already running. remainingTicks="
                            + statusSnapshot.remainingTicks()
                            + ", capturedPackets="
                            + statusSnapshot.capturedPacketCount()
                            + ", capturedRawBytes="
                            + statusSnapshot.capturedRawPacketBytes()
            ));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Transport report capture started. ticks="
                        + captureTicks
                        + ", outputDir=run/transport-report"
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandSourceStack source) {
        ChannelTransportCompressionCaptureManager.StatusSnapshot statusSnapshot =
                ChannelTransportCompressionCaptureManager.snapshotCurrentStatus(source.getServer());
        if (!statusSnapshot.running()) {
            source.sendSuccess(() -> Component.literal("Transport report is idle."), false);
            return Command.SINGLE_SUCCESS;
        }

        source.sendSuccess(() -> Component.literal(
                "Transport report running. remainingTicks="
                        + statusSnapshot.remainingTicks()
                        + ", capturedPackets="
                        + statusSnapshot.capturedPacketCount()
                        + ", capturedRawBytes="
                        + statusSnapshot.capturedRawPacketBytes()
        ), false);
        return Command.SINGLE_SUCCESS;
    }
}
