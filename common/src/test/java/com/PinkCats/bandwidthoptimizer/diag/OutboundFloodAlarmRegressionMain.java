package com.PinkCats.bandwidthoptimizer.diag;

import java.util.ArrayList;

public final class OutboundFloodAlarmRegressionMain {
    public static void main(String[] args) {
        var alarm = new OutboundFloodAlarm();
        var lines = new ArrayList<String>();
        for (int second = 100; second < 110; second++) {
            alarm.submitted("payloadChannel=vss:main", 8 * 1024 * 1024, second);
            alarm.sample(second, false, 100 * OutboundFloodAlarm.MIB, lines::add);
        }
        check(lines.size() == 1 && lines.get(0).contains("vss:main")
                && lines.get(0).contains("submittedLogicalBytes=50331648"), "source/bytes/pressure duration");
        for (int i = 0; i < 150; i++) alarm.sample(109, false, 100 * OutboundFloodAlarm.MIB, lines::add);
        check(lines.size() == 1, "warning cooldown");
        var normal = new OutboundFloodAlarm();
        normal.submitted("large", 100 * 1024 * 1024, 100);
        for (int i = 100; i < 110; i++) normal.sample(i, true, 100 * OutboundFloodAlarm.MIB, lines::add);
        check(lines.size() == 1, "writable burst must not alarm");
        normal.sample(110, false, 100 * OutboundFloodAlarm.MIB, lines::add);
        normal.sample(116, false, 100 * OutboundFloodAlarm.MIB, lines::add);
        check(lines.size() == 1, "expired source must not be blamed");
        for (int i = 0; i < 10000; i++) normal.submitted("source" + i, 1, 120);
        check(normal.sourceCount() <= 64, "bounded source state");
        check(normal.describe(120, 0).contains("unattributedLogicalBytes=9936"), "lost attribution not explicit");
        for (int i = 0; i < 20; i++) normal.submitted("packet payloadChannel=vss:main", 2577531, 121);
        check(normal.describe(121, 32 * OutboundFloodAlarm.MIB).contains("source=vss:main"),
                "late heavy source hidden by full table");
        check(!normal.describe(132, 0).contains("vss:main"), "expired producer attributed");
        normal.submitted("late payloadChannel=small:main", 100, 132);
        check(normal.sourceCount() == 1, "expired source slots not reclaimed");
        alarm.sample(170, true, 0, lines::add);
        for (int i = 171; i <= 176; i++) {
            alarm.submitted("payloadChannel=vss:main", 8 * 1024 * 1024, i);
            alarm.sample(i, false, 100 * OutboundFloodAlarm.MIB, lines::add);
        }
        check(lines.size() == 2, "recovery and retrigger");
        var channel = new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        var key = io.netty.util.AttributeKey.<OutboundFloodAlarm>valueOf("bo:outbound_flood_alarm");
        com.PinkCats.bandwidthoptimizer.channel.ChannelOutboundFloodWarning.record(channel.pipeline().lastContext(),
                net.minecraft.network.protocol.PacketFlow.SERVERBOUND, "vss:main", 100);
        check(channel.attr(key).get() == null, "serverbound must not allocate alarm");
        com.PinkCats.bandwidthoptimizer.channel.ChannelOutboundFloodWarning.record(channel.pipeline().lastContext(),
                net.minecraft.network.protocol.PacketFlow.CLIENTBOUND, "vss:main", 100);
        check(channel.attr(key).get() != null, "automatic runtime hook missing");
        channel.finishAndReleaseAll();
        check(channel.attr(key).get() == null, "closed connection retained alarm state");
        System.out.println("Outbound flood alarm regression passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
