package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.PinkCats.bandwidthoptimizer.networkprobe.message.NetworkProbeCommandMessages;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ServerGamePacketListenerImplAccessor;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import io.netty.channel.Channel;

public final class NetworkProbeCommand {
    private NetworkProbeCommand() {}
    public static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        return Commands.literal("network")
                .requires(source -> CommandSourceCompat.hasPermission(source,2))
                .then(Commands.literal("test").then(Commands.argument("player",EntityArgument.player())
                        .executes(c -> run(c.getSource(),EntityArgument.getPlayer(c,"player"),8,16))
                        .then(Commands.argument("megabytes",IntegerArgumentType.integer(1,100))
                                .executes(c -> run(c.getSource(),EntityArgument.getPlayer(c,"player"),
                                        IntegerArgumentType.getInteger(c,"megabytes"),16))
                                .then(Commands.argument("mbps",IntegerArgumentType.integer(1,128))
                                        .executes(c -> run(c.getSource(),EntityArgument.getPlayer(c,"player"),
                                                IntegerArgumentType.getInteger(c,"megabytes"),
                                                IntegerArgumentType.getInteger(c,"mbps")))))))
                .then(Commands.literal("cancel").then(Commands.argument("player",EntityArgument.player())
                        .executes(c -> {
                            Channel channel=channel(EntityArgument.getPlayer(c,"player"));
                            if(channel!=null) NetworkProbeService.cancel(channel);
                            CommandSourceCompat.sendSuccess(c.getSource(),NetworkProbeCommandMessages.text("cancel_requested",c.getSource().getEntity() instanceof ServerPlayer),false);
                            return 1;
                        })));
    }
    private static int run(CommandSourceStack source,ServerPlayer player,int mb,int mbps) {
        Channel channel=channel(player);
        boolean playerSource=source.getEntity() instanceof ServerPlayer;
        if(channel==null || !NetworkProbePackets.canSend(player)) { source.sendFailure(NetworkProbeCommandMessages.text("unavailable",playerSource)); return 0; }
        CommandSourceCompat.sendSuccess(source,NetworkProbeCommandMessages.text("notice",playerSource),false);
        player.sendSystemMessage(Component.translatable("command.bandwidthoptimizer.network.notice"));
        NetworkProbeService.start(channel,mb*1_000_000L,mbps*125_000L,
                feedback -> source.getServer().execute(() ->
                        CommandSourceCompat.sendSuccess(source,NetworkProbeCommandMessages.feedback(feedback,playerSource),false)));
        return 1;
    }
    private static Channel channel(ServerPlayer player) {
        var connection=((ServerGamePacketListenerImplAccessor)player.connection).bandwidthoptimizer$getConnection();
        return connection==null?null:((ConnectionAccessor)connection).bandwidthoptimizer$getChannel();
    }
}
