package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.report.traffic.PlayerTrafficPeriodArchive;
import com.mojang.brigadier.Command;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

public final class BandwidthReportCommand {
    private BandwidthReportCommand() {
    }

    public static int upload(CommandSourceStack source) {
        long now = System.currentTimeMillis();
        BandwidthReportBundle report = new BandwidthReportBundle(
                1,
                UUID.randomUUID().toString(),
                now,
                "server-admin-player-identifiable",
                UnifiedBandwidthReportCollector.collect("server"),
                PlayerTrafficPeriodArchive.snapshotCurrent(),
                null
        );
        CommandSourceCompat.sendSuccess(source, Component.literal("BO report upload started."), false);
        UnifiedBandwidthReportService.uploadWithTrafficHistory(report).whenComplete((result, throwable) -> source.getServer().execute(() -> {
            if (throwable != null) {
                source.sendFailure(Component.literal("BO report failed: " + throwable.getMessage()));
                return;
            }
            if (result.viewerUrl() != null && !result.viewerUrl().isBlank()) {
                CommandSourceCompat.sendSuccess(source, Component.literal(result.message()), false);
                CommandSourceCompat.sendSuccess(source, viewerLink(result.viewerUrl()), false);
            } else if (result.success()) {
                String suffix = result.localPath() == null ? "" : " File: " + result.localPath();
                CommandSourceCompat.sendSuccess(source, Component.literal(result.message() + suffix), false);
            } else {
                String suffix = result.localPath() == null ? "" : " File: " + result.localPath();
                source.sendFailure(Component.literal(result.message() + suffix));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    static Component viewerLink(String viewerUrl) {
        Component link = Component.literal(viewerUrl).withStyle(ChatFormatting.GRAY);
        try {
            URI uri = new URI(viewerUrl);
            String scheme = uri.getScheme();
            if (uri.isAbsolute() && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                return CommandSourceCompat.viewerLink(uri);
            }
        } catch (URISyntaxException ignored) {
        }
        return link;
    }
}
