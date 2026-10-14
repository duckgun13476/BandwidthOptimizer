package com.PinkCats.bandwidthoptimizer.report;

import com.google.gson.Gson;
import net.minecraft.nbt.CompoundTag;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class LightmansCurrencySourceDetailRegressionMain {
    public static void main(String[] args) {
        System.setProperty("bandwidthoptimizer.transport.sourceRankReportIntervalMillis", "0");
        check(LightmansCurrencySourceDetail.resolve(null).equals(LightmansCurrencySourceDetail.PREFIX), "optional absent");
        check(LightmansCurrencySourceDetail.resolve(new Object()).equals(LightmansCurrencySourceDetail.PREFIX), "unrelated class");
        check(LightmansCurrencySourceDetail.inspect(new Object()).equals(LightmansCurrencySourceDetail.PREFIX), "changed API");
        expect("bank_accounts", Map.of("UpdateAccount", "private-id", "Account", "private-contents"), "bank/account_update");
        expect("bank_accounts", Map.of("UpdateSelected", "private-id"), "bank/selection");
        expect("bank_accounts", Map.of("ClearAccounts", true, "UpdateAccount", "private-id"), "bank/mixed");
        expect("trader", Map.of("DeleteTrader", 123L), "trader/delete");
        expect("notifications", Map.of("UpdateNotifications", "private-contents", "Player", "private-id"), "notifications/notification_update");
        expect("teams", Map.of(), "teams/unknown");
        expect("unknown-user-controlled-id", Map.of("secret", "private-id"), "other/unknown");
        for (var item : Map.of("ItemStorage", "inventory", "StoredMoney", "money", "Stats", "statistics",
                "AddNotification", "notification_append", "Logger", "notification_update", "Type", "snapshot").entrySet()) {
            CompoundTag tag = new CompoundTag(); tag.putString(item.getKey(), "private-contents"); tag.putLong("ID", 123L);
            String before = tag.toString();
            expect("trader", Map.of("UpdateTrader", new Entry(tag)), "trader/" + item.getValue());
            check(before.equals(tag.toString()), "classification must not mutate NBT");
        }
        CompoundTag mixed = new CompoundTag(); mixed.putInt("Stats", 1); mixed.putInt("AddNotification", 1);
        expect("trader", Map.of("UpdateTrader", new Entry(mixed)), "trader/mixed");
        expect("trader", Map.of("UpdateTrader", new Object()), "trader/unknown");
        Map<String, Object> huge = new HashMap<>();
        for (int i = 0; i < 257; i++) huge.put("private-" + i, i);
        check(LightmansCurrencySourceDetail.inspect(new Payload("lightmanscurrency:trader", new Data(huge))).equals(LightmansCurrencySourceDetail.PREFIX), "bounded inspection");
        Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < 2000; i++) keys.add(LightmansCurrencySourceDetail.inspect(new Payload("private:" + i, new Data(Map.of("private-" + i, i)))));
        check(keys.size() == 1, "unknown values cannot create arbitrary source keys");

        String bank = LightmansCurrencySourceDetail.inspect(new Payload("lightmanscurrency:bank_accounts", new Data(Map.of("UpdateAccount", 1))));
        String trader = LightmansCurrencySourceDetail.inspect(new Payload("lightmanscurrency:trader", new Data(Map.of("DeleteTrader", 2))));
        record(bank, 1000, 50, false); record(trader, 2000, 100, true);
        var snapshot = ChannelTransportSourceRankCore.snapshotForReport(100);
        check(snapshot.packets() == 2 && snapshot.rawBytes() == 3000 && snapshot.actualBytes() == 150, "disjoint packet totals");
        check(snapshot.entries().stream().mapToLong(e -> e.packets()).sum() == 2, "no parent double counting");
        check(snapshot.entries().stream().anyMatch(e -> e.sourceKey().equals(trader) && e.estimatedPackets() == 1), "batch attribution flag preserved");
        check(ChannelTransportPacketRankSourceResolver.compactSourceKey(bank).equals("mod:lightmanscurrency"), "compact mod attribution preserved");
        String json = new Gson().toJson(snapshot);
        check(!json.contains("private") && json.contains("bank/account_update"), "report privacy and detail");
        check(new Gson().fromJson(json, ChannelTransportSourceRankCore.SourceTrafficSnapshot.class).equals(snapshot), "report round trip");
        System.out.println("LightmansCurrency source detail regression passed (read-only, bounded, optional absent, disjoint totals, JSON).");
    }
    private static void expect(String type, Map<String, ?> map, String suffix) {
        String result = LightmansCurrencySourceDetail.inspect(new Payload("lightmanscurrency:" + type, new Data(map)));
        check(result.equals(LightmansCurrencySourceDetail.PREFIX + "/" + suffix), "expected " + suffix + ", got " + result);
    }
    private static void record(String key, int raw, int actual, boolean estimated) {
        ChannelTransportSourceRankCore.recordOutboundPacket(key, "ClientboundCustomPayloadPacket", 25,
                raw, raw, actual, false, estimated, estimated ? "BATCH_SHARE" : "SINGLE", "FULL", 1, "private-channel");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private record Payload(Object dataType, Object data) {}
    private record Data(Map<String, ?> dataMap) {}
    private record Entry(Object value) {}
}
