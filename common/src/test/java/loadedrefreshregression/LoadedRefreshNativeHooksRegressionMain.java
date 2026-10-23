package loadedrefreshregression;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Inspect the native target without initializing Minecraft or starting a game. */
public final class LoadedRefreshNativeHooksRegressionMain {
    public static void main(String[] args) throws Exception {
        Set<String> expected = Set.of("handleSetChunkCacheCenter", "handleSetChunkCacheRadius",
                "handleLevelChunkWithLight", "handleBlockUpdate", "handleChunkBlocksUpdate",
                "handleLightUpdatePacket", "handleBlockEntityData", "handleForgetLevelChunk");
        Set<String> found = new HashSet<>();
        String resource = "net/minecraft/client/multiplayer/ClientPacketListener.class";
        try (InputStream input = LoadedRefreshNativeHooksRegressionMain.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing native target " + resource);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (expected.contains(name) && descriptor.startsWith("(Lnet/minecraft/network/protocol/game/")
                            && descriptor.endsWith(")V")) found.add(name);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        if (!found.equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(found);
            throw new AssertionError("Loaded refresh native injection targets missing: " + missing);
        }
        System.out.println("Loaded refresh native hook targets PASS count=" + found.size());
    }
}
