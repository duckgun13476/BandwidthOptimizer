package com.PinkCats.bandwidthoptimizer.integration.watut;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Optional WATUT 1.2.x adapter. All callbacks run on the owning Minecraft server thread. */
public final class WatutServerFrameCompat {
    private static final String PIXELS = "screenCompressedPixelData";
    private static final String COUNT = "screenCompressedPixelDataPacketCount";
    private static final String INDEX = "screenCompressedPixelDataPacketIndex";
    private static final String SIZE = "screenCompressedPixelDataSize";
    private static final String WIDTH = "screenWidth", HEIGHT = "screenHeight";
    private static final Set<String> IMAGE_KEYS = Set.of(PIXELS, COUNT, INDEX, SIZE, WIDTH, HEIGHT);
    private static final Map<Object, WatutFrameStore<CompoundTag>> STORES = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private static Api api;
    private static boolean checkedApi;
    private WatutServerFrameCompat() {}

    public static boolean beforeReceive(Object manager, Player player, CompoundTag data) {
        if (REPLAYING.get() || !(player instanceof ServerPlayer source) || !available(manager)) return false;
        if (data.contains(PIXELS, Tag.TAG_BYTE_ARRAY)) WatutTrafficStats.receivedImage(data.getByteArray(PIXELS).length);
        else if (data.contains("playerGuiStatus") || data.contains("playerChatStatus") || data.contains("playerIdleTicks")) WatutTrafficStats.receivedState();
        else if (data.contains("playerMouseX")) WatutTrafficStats.receivedMouse();
        else WatutTrafficStats.receivedOther();
        WatutFrameStore<CompoundTag> store = STORES.computeIfAbsent(manager, ignored -> new WatutFrameStore<>());
        WatutFrameStore.Delivery<CompoundTag> delivery = delivery(manager);
        if (!pureImage(data)) {
            if (data.contains(PIXELS, Tag.TAG_BYTE_ARRAY)) WatutTrafficStats.nativeImage();
            // A state transition is an ordering barrier: emit buffered original fragments first.
            if (data.contains("playerGuiStatus") || data.contains(PIXELS)) store.remove(source, delivery);
            else store.flush(source, delivery);
            return false;
        }
        CompoundTag packet = data.copy();
        packet.putString("playerUuid", source.getUUID().toString());
        boolean handled = store.receive(source, source.serverLevel(), new WatutFrameStore.Header(
                packet.getInt(WIDTH), packet.getInt(HEIGHT), packet.getInt(SIZE), packet.getInt(COUNT)),
                packet.getInt(INDEX), packet.getByteArray(PIXELS), packet,
                recipients(source), source.getServer().getTickCount(), delivery);
        if (!handled) WatutTrafficStats.nativeImage();
        return handled;
    }

    public static void afterReceive(Object manager, Player player) {
        if (!available(manager)) return;
        CompoundTag cache = (CompoundTag) invoke(api.cache, invoke(api.status, manager, player));
        // State snapshots cannot represent a multi-fragment screenshot.
        for (String key : IMAGE_KEYS) cache.remove(key);
    }

    public static void afterLogin(Object manager, Player player) {
        if (!(player instanceof ServerPlayer recipient) || !available(manager)) return;
        WatutFrameStore<CompoundTag> store = STORES.get(manager);
        if (store == null) return;
        for (ServerPlayer source : recipient.getServer().getPlayerList().getPlayers()) {
            if (recipients(source).contains(recipient)) store.replay(source, source.serverLevel(), recipient,
                    recipient.getServer().getTickCount(), delivery(manager, true));
        }
    }

    public static void tick(MinecraftServer server) {
        if (api == null || STORES.isEmpty()) return;
        Set<ServerPlayer> live = new HashSet<>(server.getPlayerList().getPlayers());
        for (Map.Entry<Object, WatutFrameStore<CompoundTag>> entry : STORES.entrySet()) {
            // Integrated servers may be recreated in the same process.
            entry.getValue().expire(server.getTickCount(), live, delivery(entry.getKey()));
        }
    }

    public static void clear() { STORES.clear(); ServerWatutClientStats.clear(); }

    private static boolean pureImage(CompoundTag data) {
        if (!data.getAllKeys().equals(IMAGE_KEYS) || !data.contains(PIXELS, Tag.TAG_BYTE_ARRAY)) return false;
        for (String key : IMAGE_KEYS) if (!key.equals(PIXELS) && !data.contains(key, Tag.TAG_INT)) return false;
        int count = data.getInt(COUNT), index = data.getInt(INDEX);
        return count == 1 ? index == 0 || index == 1 : index >= 0 && index < count;
    }

    private static List<ServerPlayer> recipients(ServerPlayer source) {
        int radius;
        try { radius = api.radius.getInt(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("WATUT GUI radius unavailable", e); }
        List<ServerPlayer> recipients = new ArrayList<>();
        double squared = (double) radius * radius;
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers())
            if (player.serverLevel() == source.serverLevel() && player.distanceToSqr(source) < squared) recipients.add(player);
        return recipients;
    }

    private static WatutFrameStore.Delivery<CompoundTag> delivery(Object manager) {
        return delivery(manager, false);
    }

    private static WatutFrameStore.Delivery<CompoundTag> delivery(Object manager, boolean loginReplay) {
        return new WatutFrameStore.Delivery<>() {
            public void nativePacket(Object source, CompoundTag packet) {
                WatutTrafficStats.nativeImage();
                REPLAYING.set(true);
                try { invoke(api.receive, manager, source, packet); }
                finally { REPLAYING.remove(); }
            }
            public void frame(Object source, Object recipient, List<CompoundTag> packets) {
                Object networking = invoke(api.instance, null);
                for (CompoundTag packet : packets) invoke(api.send, networking, packet, recipient);
                WatutTrafficStats.serverSent(packets.stream().mapToLong(p -> p.getByteArray(PIXELS).length).sum());
                if (loginReplay) WatutTrafficStats.loginReplay(packets.stream().mapToLong(p -> p.getByteArray(PIXELS).length).sum());
            }
            public void suppressed(long bytes, int recipients) {
                WatutTrafficStats.serverSuppressed(bytes, recipients);
            }
        };
    }

    private static boolean available(Object manager) {
        if (!checkedApi) {
            checkedApi = true;
            try {
                Class<?> networking = Class.forName("com.corosus.watut.WatutNetworking");
                Class<?> statusClass = Class.forName("com.corosus.watut.PlayerStatus");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                // getMethod enumerates unrelated client-only return types on PlayerStatus.
                // Exact JVM signatures resolve only the server-safe methods we actually call.
                api = new Api(lookup.findVirtual(manager.getClass(), "receiveAny", MethodType.methodType(void.class, Player.class, CompoundTag.class)),
                        lookup.findVirtual(manager.getClass(), "getStatus", MethodType.methodType(statusClass, Player.class)),
                        lookup.findVirtual(statusClass, "getNbtCache", MethodType.methodType(CompoundTag.class)),
                        lookup.findStatic(networking, "instance", MethodType.methodType(networking)),
                        lookup.findVirtual(networking, "serverSendToClientPlayer", MethodType.methodType(void.class, CompoundTag.class, Player.class)),
                        Class.forName("com.corosus.watut.config.ConfigServerControlledSyncedToClient")
                                .getField("distanceRequiredToShowGUIInfo"));
            } catch (ReflectiveOperationException | LinkageError ignored) { api = null; }
        }
        return api != null;
    }

    private static Object invoke(MethodHandle method, Object receiver, Object... arguments) {
        try { return (receiver == null ? method : method.bindTo(receiver)).invokeWithArguments(arguments); }
        catch (Throwable e) { throw new IllegalStateException("WATUT frame delivery failed", e); }
    }

    private record Api(MethodHandle receive, MethodHandle status, MethodHandle cache, MethodHandle instance, MethodHandle send,
                       java.lang.reflect.Field radius) {}
}
