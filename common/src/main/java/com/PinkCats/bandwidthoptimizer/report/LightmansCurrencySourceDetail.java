package com.PinkCats.bandwidthoptimizer.report;

import net.minecraft.nbt.CompoundTag;
import java.lang.reflect.Field;
import java.util.Map;

/** Reads existing packet snapshots only: no codec invocation, buffer reads, world access or retained payloads. */
public final class LightmansCurrencySourceDetail {
    public static final String CHANNEL = "lightmanscurrency:s_sync_custom_data";
    public static final String PREFIX = "custom_payload:" + CHANNEL;
    private static final String PACKET = "io.github.lightman314.lightmanscurrency.network.message.data.SPacketSyncCustomData";
    private static final ClassValue<PacketFields> PACKETS = new ClassValue<>() {
        @Override protected PacketFields computeValue(Class<?> type) {
            return new PacketFields(field(type, "dataType"), field(type, "data"));
        }
    };
    private static final ClassValue<Field> MAPS = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> type) { return field(type, "dataMap"); }
    };
    private static final ClassValue<Field> VALUES = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> type) { return field(type, "value"); }
    };
    private LightmansCurrencySourceDetail() {}

    public static String resolve(Object payload) {
        if (payload == null || !PACKET.equals(payload.getClass().getName())) return PREFIX;
        return inspect(payload);
    }

    // Kept separate from optional class matching to exercise API shape changes without loading optional mods.
    static String inspect(Object payload) {
        try {
            PacketFields fields = PACKETS.get(payload.getClass());
            if (fields.type == null || fields.data == null) return PREFIX;
            Object type = fields.type.get(payload), data = fields.data.get(payload);
            if (type == null || data == null) return PREFIX;
            Field mapField = MAPS.get(data.getClass());
            if (mapField == null || !(mapField.get(data) instanceof Map<?, ?> map) || map.size() > 256) return PREFIX;
            String category = category(type.toString());
            return PREFIX + "/" + category + "/" + operation(category, map);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Statistics must not affect delivery when the optional API changes.
            return PREFIX;
        }
    }

    private static String category(String id) {
        return switch (id) {
            case "lightmanscurrency:bank_accounts" -> "bank";
            case "lightmanscurrency:trader" -> "trader";
            case "lightmanscurrency:notifications" -> "notifications";
            case "lightmanscurrency:teams" -> "teams";
            case "lightmanscurrency:tax_entries" -> "tax";
            case "lightmanscurrency:tickets" -> "tickets";
            case "lightmanscurrency:ejection_data" -> "ejection";
            case "lightmanscurrency:event_rewards" -> "rewards";
            default -> "other";
        };
    }

    private static String operation(String category, Map<?, ?> map) throws IllegalAccessException {
        if (category.equals("bank")) {
            int count = count(map, "ClearAccounts", "DeleteAccount", "UpdateAccount", "UpdateSelected");
            if (count > 1) return "mixed";
            if (map.containsKey("ClearAccounts")) return "clear";
            if (map.containsKey("DeleteAccount")) return "delete";
            if (map.containsKey("UpdateAccount")) return "account_update";
            if (map.containsKey("UpdateSelected")) return "selection";
        } else if (category.equals("trader")) {
            int count = count(map, "ClearTraders", "DeleteTrader", "UpdateTrader");
            if (count > 1) return "mixed";
            if (map.containsKey("ClearTraders")) return "clear";
            if (map.containsKey("DeleteTrader")) return "delete";
            if (map.containsKey("UpdateTrader")) {
                Object entry = map.get("UpdateTrader");
                if (entry == null) return "unknown";
                Field value = VALUES.get(entry.getClass());
                if (value == null || !(value.get(entry) instanceof CompoundTag tag)) return "unknown";
                if (tag.contains("Type")) return "snapshot";
                int fields = 0;
                String kind = "other_update";
                for (String key : TRADER_FIELDS) {
                    if (tag.contains(key)) { fields++; kind = traderField(key); }
                }
                return fields > 1 ? "mixed" : kind;
            }
        } else if (category.equals("notifications") && map.containsKey("UpdateNotifications")) return "notification_update";
        return "unknown";
    }
    private static final String[] TRADER_FIELDS = { "ItemStorage", "StoredMoney", "Stats", "AddNotification", "Logger" };
    private static String traderField(String field) {
        return switch (field) {
            case "ItemStorage" -> "inventory";
            case "StoredMoney" -> "money";
            case "Stats" -> "statistics";
            case "AddNotification" -> "notification_append";
            case "Logger" -> "notification_update";
            default -> "other_update";
        };
    }
    private static int count(Map<?, ?> map, String... keys) {
        int count = 0;
        for (String key : keys) if (map.containsKey(key)) count++;
        return count;
    }
    private static Field field(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }
    private record PacketFields(Field type, Field data) {}
}
