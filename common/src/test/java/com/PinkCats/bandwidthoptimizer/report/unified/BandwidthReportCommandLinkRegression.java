package com.PinkCats.bandwidthoptimizer.report.unified;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;

public final class BandwidthReportCommandLinkRegression {
    private BandwidthReportCommandLinkRegression() {
    }

    public static void main(String[] args) {
        verify();
        System.out.println("Bandwidth report command link regression passed.");
    }

    public static void verify() {
        String url = "https://bostats.torqueflux.com/report/test-id";
        Component link = BandwidthReportCommand.viewerLink(url);
        ClickEvent click = link.getStyle().getClickEvent();
        check(click != null, "HTTPS viewer URL must be clickable");
        check("OPEN_URL".equals(clickActionName(click)), "viewer link must use OPEN_URL");
        check(url.equals(clickTarget(click)), "viewer link target changed");
        check(BandwidthReportCommand.viewerLink("file:///tmp/report").getStyle().getClickEvent() == null,
                "non-web viewer URL must remain plain text");
    }

    private static String clickActionName(ClickEvent click) {
        if ("OpenUrl".equals(click.getClass().getSimpleName())) {
            return "OPEN_URL";
        }
        Object action = invoke(click, "getAction", "getAction");
        return action instanceof Enum<?> value ? value.name() : String.valueOf(action);
    }

    private static String clickTarget(ClickEvent click) {
        Object target = invoke(click, "getValue", "uri");
        return String.valueOf(target);
    }

    private static Object invoke(ClickEvent click, String legacyMethod, String currentMethod) {
        try {
            Method method;
            try {
                method = click.getClass().getMethod(legacyMethod);
            } catch (NoSuchMethodException ignored) {
                method = click.getClass().getMethod(currentMethod);
            }
            return method.invoke(click);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to inspect click event " + click.getClass().getName(), exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
