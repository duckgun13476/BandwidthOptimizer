package com.PinkCats.bandwidthoptimizer.report.unified;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

public final class BandwidthReportCommandLinkRegression {
    private BandwidthReportCommandLinkRegression() {
    }

    public static void verify() {
        String url = "https://bostats.torqueflux.com/report/test-id";
        Component link = BandwidthReportCommand.viewerLink(url);
        ClickEvent click = link.getStyle().getClickEvent();
        check(click != null, "HTTPS viewer URL must be clickable");
        check(click.getAction() == ClickEvent.Action.OPEN_URL, "viewer link must use OPEN_URL");
        check(url.equals(click.getValue()), "viewer link target changed");
        check(BandwidthReportCommand.viewerLink("file:///tmp/report").getStyle().getClickEvent() == null,
                "non-web viewer URL must remain plain text");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
