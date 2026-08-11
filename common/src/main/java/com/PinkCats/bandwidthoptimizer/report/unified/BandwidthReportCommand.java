package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.report.traffic.PlayerTrafficPeriodArchive;
import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

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
            StringBuilder message = new StringBuilder(result.message());
            if (result.viewerUrl() != null && !result.viewerUrl().isBlank()) {
                message.append(" View: ").append(result.viewerUrl());
            } else if (result.localPath() != null) {
                message.append(" File: ").append(result.localPath());
            }
            if (result.success()) {
                CommandSourceCompat.sendSuccess(source, Component.literal(message.toString()), false);
            } else {
                source.sendFailure(Component.literal(message.toString()));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }
}
