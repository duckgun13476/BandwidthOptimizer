package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.report.unified.BandwidthReportCommandLinkRegression;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;

import java.util.Set;
import java.util.stream.Collectors;

public final class BandwidthOptimizerCommandTreeRegressionMain {
    private BandwidthOptimizerCommandTreeRegressionMain() {
    }

    public static void main(String[] args) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        BandwidthOptimizerCommand.register(dispatcher);

        CommandNode<CommandSourceStack> root = requireChild(dispatcher.getRoot(), "bandwidthoptimizer");
        check(childNames(root).equals(Set.of("stats", "reload", "proxy", "debug")),
                "server command root must contain only stats, reload, proxy, and debug; hud is client-side");

        CommandNode<CommandSourceStack> alias = requireChild(dispatcher.getRoot(), "bo");
        check(alias.getRedirect() == root, "bo must redirect to the full bandwidthoptimizer command tree");

        CommandNode<CommandSourceStack> stats = requireChild(root, "stats");
        check(stats.getCommand() != null, "stats must directly upload the report");
        check(stats.getChildren().isEmpty(), "stats must not expose internal subcommands");

        CommandNode<CommandSourceStack> reload = requireChild(root, "reload");
        check(reload.getCommand() != null, "reload must directly refresh server configuration");

        CommandNode<CommandSourceStack> proxy = requireChild(root, "proxy");
        check(proxy.getCommand() != null, "proxy must directly list active channels");
        check(childNames(proxy).equals(Set.of("add", "remove")), "proxy must expose only add and remove mutations");
        CommandNode<CommandSourceStack> addChannel = requireChild(proxy, "add").getChild("channel");
        CommandNode<CommandSourceStack> removeChannel = requireChild(proxy, "remove").getChild("channel");
        check(addChannel != null, "proxy add must require an exact channel id");
        check(removeChannel != null, "proxy remove must require an exact channel id");
        check(argumentConsumesChannelId(addChannel), "proxy add must accept an unquoted namespace:path id");
        check(argumentConsumesChannelId(removeChannel), "proxy remove must accept an unquoted namespace:path id");

        CommandNode<CommandSourceStack> debug = requireChild(root, "debug");
        CommandNode<CommandSourceStack> debugStats = requireChild(debug, "stats");
        check(childNames(debugStats).equals(Set.of("total", "players", "reset", "vanilla")),
                "internal traffic controls must remain under debug stats");
        check(root.getChild("report") == null, "legacy top-level report command remains registered");

        BandwidthReportCommandLinkRegression.verify();
        System.out.println("BandwidthOptimizer command tree regression passed.");
    }

    private static CommandNode<CommandSourceStack> requireChild(CommandNode<CommandSourceStack> parent, String name) {
        CommandNode<CommandSourceStack> child = parent.getChild(name);
        if (child == null) {
            throw new IllegalStateException("Missing command node: " + name);
        }
        return child;
    }

    private static Set<String> childNames(CommandNode<CommandSourceStack> node) {
        return node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean argumentConsumesChannelId(CommandNode<CommandSourceStack> node) {
        if (!(node instanceof ArgumentCommandNode<?, ?> argument)) {
            return false;
        }
        StringReader reader = new StringReader("example:channel");
        try {
            argument.getType().parse(reader);
            return !reader.canRead();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
