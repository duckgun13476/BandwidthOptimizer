package compactrefregression;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** Explicit regression-only agent; never part of the production artifact. */
public final class CompactReferenceMemoryAgent {
    private static Instrumentation instrumentation;

    public static void premain(String arguments, Instrumentation value) { instrumentation = value; }

    static long retainedMetadataBytes(Object root) throws ReflectiveOperationException {
        if (instrumentation == null) throw new AssertionError("Memory regression agent not installed");
        var seen = new IdentityHashMap<Object, Boolean>();
        var pending = new ArrayDeque<Object>();
        pending.add(root);
        long bytes = 0;
        while (!pending.isEmpty()) {
            Object current = pending.removeFirst();
            if (seen.put(current, Boolean.TRUE) != null) continue;
            bytes += instrumentation.getObjectSize(current);
            Class<?> type = current.getClass();
            if (type.isArray()) {
                if (type == byte[].class && Array.getLength(current) > 64) throw new AssertionError("Dictionary retains packet-sized body");
                if (!type.getComponentType().isPrimitive()) {
                    for (Object value : (Object[]) current) if (value != null) pending.add(value);
                }
            } else {
                for (Class<?> owner=type; owner!=null; owner=owner.getSuperclass()) {
                    for (var field : owner.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                        field.setAccessible(true);
                        Object value = field.get(current);
                        if (value != null) pending.add(value);
                    }
                }
            }
        }
        return bytes;
    }
}
