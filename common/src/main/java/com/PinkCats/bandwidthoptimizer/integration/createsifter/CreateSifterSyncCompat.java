package com.PinkCats.bandwidthoptimizer.integration.createsifter;

import net.minecraft.world.level.block.entity.BlockEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Optional APIs are resolved lazily. Unknown classes/shapes keep their original behavior. */
public final class CreateSifterSyncCompat {
    private static final String BASE = "com.oierbravo.createsifter.content.contraptions.components.sifter.";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bandwidthoptimizer.compat.createSifterSyncBatchEnabled", "true"));
    private static final ClassValue<Method> APPLY = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try { Method method = current.getDeclaredMethod("apply"); method.setAccessible(true); return method; }
                catch (NoSuchMethodException ignored) { /* preserve virtual dispatch to the actual override */ }
            }
            throw new IllegalStateException("Matched Mechanical apply call has no implementation");
        }
    };
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            if (!type.getName().equals("com.oierbravo.mechanicals.foundation.blockEntity.behaviour.DynamicCycleBehavior")) return new Access(null);
            try {
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    try { Field field = current.getDeclaredField("blockEntity"); field.setAccessible(true); return new Access(field); }
                    catch (NoSuchFieldException ignored) { /* inspect the known behavior hierarchy */ }
                }
            } catch (RuntimeException ignored) { /* incompatible optional API: original call */ }
            return new Access(null);
        }
    };
    private static final ClassValue<Sender> SENDERS = new ClassValue<>() {
        @Override protected Sender computeValue(Class<?> type) {
            if (!type.getName().equals(BASE + "andesite.SifterBlockEntity") && !type.getName().equals(BASE + "brass.BrassSifterBlockEntity")) return new Sender(null);
            try { return new Sender(type.getMethod("sendData")); }
            catch (ReflectiveOperationException | RuntimeException ignored) { return new Sender(null); }
        }
    };
    private CreateSifterSyncCompat() {}

    public static void apply(Object behavior, Runnable original) {
        if (!ENABLED || behavior == null) { original.run(); return; }
        Object owner;
        Access access = ACCESS.get(behavior.getClass());
        if (access.owner == null) { original.run(); return; }
        try { owner = access.owner.get(behavior); }
        catch (IllegalAccessException | RuntimeException ignored) { original.run(); return; }
        if (!(owner instanceof BlockEntity be) || be.getLevel() == null || be.getLevel().isClientSide()
                || be.getLevel().getServer() == null || !be.getLevel().getServer().isSameThread()) { original.run(); return; }
        Sender sender = SENDERS.get(owner.getClass());
        if (sender.method == null) { original.run(); return; }
        SifterSyncTransaction.run(owner, original, () -> send(sender.method, owner));
    }

    public static boolean defer(Object owner) { return ENABLED && SifterSyncTransaction.defer(owner); }
    public static void invokeApply(Object behavior) { send(APPLY.get(behavior.getClass()), behavior); }
    private static void send(Method method, Object owner) {
        try { method.invoke(owner); }
        catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("CreateSifter sync flush failed", cause);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException("CreateSifter sync flush failed", exception); }
    }
    private record Access(Field owner) {}
    private record Sender(Method method) {}
}
