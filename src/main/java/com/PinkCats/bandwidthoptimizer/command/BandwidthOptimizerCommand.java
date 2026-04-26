package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class BandwidthOptimizerCommand {
    private BandwidthOptimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) { // 这个函数负责注册公共根命令，并把测试命令的权限限制在 test 分支下。
        dispatcher.register(Commands.literal("bandwidthoptimizer")
                .executes(context -> root(context.getSource()))
                .then(Commands.literal("test")
                        .requires(source -> source.hasPermission(2))
                        .then(ChannelTransportCompressionCommand.buildCommand()))
        );
    }

    private static int root(CommandSourceStack source) { // 这个函数负责向调用者展示当前可用命令，普通玩家也能先看到帮助信息。
        source.sendSuccess(() -> Component.literal(
                "BandwidthOptimizer commands: /bandwidthoptimizer hud (client) | /bandwidthoptimizer test transportreport run [ticks]"
        ), false);
        return Command.SINGLE_SUCCESS;
    }
}
