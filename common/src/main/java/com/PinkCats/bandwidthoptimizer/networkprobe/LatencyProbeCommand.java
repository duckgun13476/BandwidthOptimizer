package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ServerGamePacketListenerImplAccessor;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class LatencyProbeCommand {
    private static final int BATCH_LIMIT=16;
    private LatencyProbeCommand() {}
    public static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        return Commands.literal("ping").executes(c -> self(c.getSource(),5,true))
                .then(Commands.literal("all").requires(s -> CommandSourceCompat.hasPermission(s,2))
                        .executes(c -> all(c.getSource(),5,true)))
                .then(Commands.argument("player",EntityArgument.player()).requires(s -> CommandSourceCompat.hasPermission(s,2))
                        .executes(c -> target(c.getSource(),EntityArgument.getPlayer(c,"player"),5,true)))
                .then(Commands.literal("watch")
                        .then(Commands.argument("seconds",IntegerArgumentType.integer(1,120))
                                .executes(c -> self(c.getSource(),IntegerArgumentType.getInteger(c,"seconds")*2,false)))
                        .then(Commands.literal("all").requires(s -> CommandSourceCompat.hasPermission(s,2))
                                .then(Commands.argument("seconds",IntegerArgumentType.integer(1,120))
                                        .executes(c -> all(c.getSource(),IntegerArgumentType.getInteger(c,"seconds")*2,false))))
                        .then(Commands.argument("player",EntityArgument.player()).requires(s -> CommandSourceCompat.hasPermission(s,2))
                                .then(Commands.argument("seconds",IntegerArgumentType.integer(1,120))
                                        .executes(c -> target(c.getSource(),EntityArgument.getPlayer(c,"player"),IntegerArgumentType.getInteger(c,"seconds")*2,false)))));
    }
    private static int self(CommandSourceStack source,int samples,boolean list) {
        if(!(source.getEntity() instanceof ServerPlayer player)) {source.sendFailure(text(source,"player_only"));return 0;}
        launch(source,player,samples,list,false,() -> {});return 1;
    }
    private static int target(CommandSourceStack source,ServerPlayer player,int samples,boolean list) {
        launch(source,player,samples,list,true,() -> {});return 1;
    }
    private static int all(CommandSourceStack source,int samples,boolean list) {
        List<ServerPlayer> players=List.copyOf(source.getServer().getPlayerList().getPlayers());
        if(players.isEmpty()) {source.sendFailure(text(source,"no_players"));return 0;}
        send(source,text(source,"queue_started",players.size(),BATCH_LIMIT));
        new LatencyProbeBatch<>(players,BATCH_LIMIT,(player,done) -> launch(source,player,samples,list,true,done),
                () -> send(source,text(source,"queue_finished",players.size()))).start();
        return 1;
    }
    private static void launch(CommandSourceStack source,ServerPlayer player,int samples,boolean list,boolean labelled,Runnable done) {
        String name=player.getName().getString();
        var connection=((ServerGamePacketListenerImplAccessor)player.connection).bandwidthoptimizer$getConnection();
        var channel=connection==null?null:((ConnectionAccessor)connection).bandwidthoptimizer$getChannel();
        if(channel==null || !channel.isActive() || !NetworkProbePackets.canSend(player)) {
            failure(source,labelled,name,text(source,"unavailable"));done.run();return;
        }
        LatencyProbeService.start(channel,samples,status -> source.getServer().execute(() -> {
            if(status.equals("started")) send(source,label(labelled,name,text(source,"started",number(samples/2.0))));
            else {failure(source,labelled,name,text(source,status));done.run();}
        }),result -> source.getServer().execute(() -> {
            try {display(source,result,list,labelled,name);} finally {done.run();}
        }));
    }
    private static void display(CommandSourceStack source,LatencyProbeWindow.Result r,boolean list,boolean labelled,String name) {
        if(r.incomplete()) send(source,label(labelled,name,text(source,"incomplete")));
        if(list) send(source,label(labelled,name,text(source,"list",r.samples().stream().map(n -> n==0?"—":ms(n)).collect(Collectors.joining(", ")))));
        if(r.replies()==0) {send(source,label(labelled,name,text(source,"no_reply",r.sent(),r.timedOut(),r.sent()-r.timedOut())));return;}
        send(source,label(labelled,name,text(source,"summary",number(r.duration()/1e9),ms(r.average()),ms(r.minimum()),ms(r.maximum()),r.replies(),r.sent(),r.timedOut())));
        if(r.spikes().size()>8) send(source,label(labelled,name,text(source,"spike_limit",r.spikes().size())));
        for(var spike:r.spikes().stream().sorted(java.util.Comparator.comparingLong(LatencyProbeWindow.Spike::peak).reversed()).limit(8).sorted(java.util.Comparator.comparingLong(LatencyProbeWindow.Spike::from)).toList())
            send(source,label(labelled,name,text(source,"spike",number(spike.from()/1e9),number(spike.to()/1e9),ms(spike.peak()),spike.samples())));
        if(r.spikes().isEmpty()) send(source,label(labelled,name,text(source,"no_spikes")));
    }
    private static String ms(long nanos) {return number(nanos/1e6);}
    private static String number(double value) {return String.format(Locale.ROOT,"%.1f",value);}
    private static Component label(boolean labelled,String name,Component message) {
        return labelled?Component.literal("["+name+"] ").append(message):message;
    }
    private static Component text(CommandSourceStack source,String name,Object...args) {
        if(source.getEntity() instanceof ServerPlayer) return Component.translatable("command.bandwidthoptimizer.ping."+name,args);
        String english=switch(name) {
            case "player_only" -> "Specify a player or all.";
            case "unavailable" -> "Latency sampling is unavailable on this connection.";
            case "busy" -> "A latency test is already running or the server is busy.";
            case "started" -> "Latency sampling started: %s seconds, one sample every 500 ms.";
            case "list" -> "Recent RTT samples (ms): %s";
            case "summary" -> "Latency sampling: %s s; average %s ms, minimum %s ms, maximum %s ms; replies %s/%s, timed out %s.";
            case "spike" -> "High latency at %s–%s s: peak %s ms (%s samples).";
            case "spike_limit" -> "Showing the 8 highest-latency intervals out of %s.";
            case "no_spikes" -> "No latency samples exceeded 200 ms.";
            case "incomplete" -> "Sampling stopped: connection closed. Results include completed observations only.";
            case "no_reply" -> "No RTT replies: sent %s, timed out %s, still pending %s.";
            case "no_players" -> "There are no online players to test.";
            case "queue_started" -> "Testing %s online players, up to %s at a time.";
            case "queue_finished" -> "Latency tests finished for %s online players.";
            default -> "Latency sampling failed.";
        };
        return Component.literal(String.format(Locale.ROOT,english,args));
    }
    private static void failure(CommandSourceStack source,boolean labelled,String name,Component message) {source.sendFailure(label(labelled,name,message));}
    private static void send(CommandSourceStack source,Component message) {CommandSourceCompat.sendSuccess(source,message,false);}
}
