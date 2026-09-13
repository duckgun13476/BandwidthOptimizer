package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CreateEncoderThreadIsolationRegressionMain {

    private static final String TARGET_METHOD = "resolveDynamicTargetForEncoder";
    private static final String WORLD_RESOLVER = "resolveDynamicTargetOnServerThread";

    private CreateEncoderThreadIsolationRegressionMain() {}

    public static void main(String[] args) throws Exception {
        String resourceName = "/" + CreateBlockEntityUpdateGate.class.getName().replace('.', '/') + ".class";
        AtomicBoolean foundTarget = new AtomicBoolean();
        AtomicBoolean callsWorldResolver = new AtomicBoolean();
        AtomicBoolean readsPreparedTarget = new AtomicBoolean();

        try (InputStream input = CreateBlockEntityUpdateGate.class.getResourceAsStream(resourceName)) {
            require(input != null, "Create gate class bytes were unavailable");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!TARGET_METHOD.equals(name)) {
                        return null;
                    }
                    foundTarget.set(true);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (WORLD_RESOLVER.equals(name)) {
                                callsWorldResolver.set(true);
                            }
                            if ("readPreparedDynamicTarget".equals(name)) {
                                readsPreparedTarget.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        require(foundTarget.get(), "Encoder target resolver was not found");
        require(readsPreparedTarget.get(), "Encoder target resolver no longer consumes prepared packet state");
        require(!callsWorldResolver.get(), "Encoder target resolver can still read server world state");
        System.out.println("Create encoder thread isolation regression passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
