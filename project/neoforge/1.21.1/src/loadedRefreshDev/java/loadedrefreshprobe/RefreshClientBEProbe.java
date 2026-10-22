package loadedrefreshprobe;

import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;

/** Test-only bounded latest native tags, never a production cache. */
public final class RefreshClientBEProbe {
    private static final Map<String, JsonElement> TAGS = new LinkedHashMap<>();
    private static final Map<String, Integer> SIZES = new LinkedHashMap<>();
    private static int bytes, received;
    public static void observe(ClientboundBlockEntityDataPacket packet) {
        var p = packet.getPos();
        if ((p.getX() >> 4) != RefreshProbe.TARGET_X || (p.getZ() >> 4) != RefreshProbe.TARGET_Z || packet.getTag() == null) return;
        received++;
        String key = (p.getX() & 15) + "," + p.getY() + "," + (p.getZ() & 15);
        var json = NbtOps.INSTANCE.convertTo(com.mojang.serialization.JsonOps.INSTANCE, packet.getTag());
        int size = json.toString().length() * 2;
        int previous = SIZES.getOrDefault(key, 0);
        if (size > 16384 || (!TAGS.containsKey(key) && TAGS.size() >= 256) || bytes - previous + size > 1024 * 1024)
            throw new AssertionError("Native BE test sample exceeded bound");
        TAGS.put(key, json); SIZES.put(key, size); bytes += size - previous;
    }
    public static void atCapture() {
        if (!java.nio.file.Files.exists(RefreshProbe.ROOT.resolve("native-be-updates-at-capture.json")))
            RefreshProbe.write("native-be-updates-at-capture.json", Map.of("received", received, "tags", Map.copyOf(TAGS)));
    }
}
