package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class CreateContraptionReferenceResolver {

    private static final MemberKey ATTACHED_CONTRAPTION = MemberKey.method("getAttachedContraption");
    private static final MemberKey MOVED_CONTRAPTION_METHOD = MemberKey.method("getMovedContraption");
    private static final MemberKey MOVED_CONTRAPTION_FIELD = MemberKey.field("movedContraption");
    private static final MemberKey HOUR_HAND = MemberKey.field("hourHand");
    private static final MemberKey MINUTE_HAND = MemberKey.field("minuteHand");
    private static final MemberKey SHARED_MIRROR = MemberKey.field("sharedMirrorContraption");
    private static final MemberKey ANCHOR_VECTOR = MemberKey.method("getAnchorVec");

    private static final ClassValue<ConcurrentHashMap<MemberKey, Accessor>> ACCESSORS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<MemberKey, Accessor> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private CreateContraptionReferenceResolver() {}

    static Resolution resolve(Object controller, String typeKey) {
        if (controller == null) {
            return Resolution.nearbyScan();
        }
        List<Object> references = new ArrayList<>(2);
        switch (CreateGateTypePolicy.contraptionReferenceKind(typeKey)) {
            case PISTON -> add(references, read(controller, MOVED_CONTRAPTION_FIELD));
            case BEARING -> add(references, read(controller, MOVED_CONTRAPTION_METHOD));
            case CLOCKWORK -> {
                add(references, read(controller, HOUR_HAND));
                add(references, read(controller, MINUTE_HAND));
            }
            case PULLEY -> {
                add(references, read(controller, ATTACHED_CONTRAPTION));
                add(references, read(controller, SHARED_MIRROR));
            }
            case NEARBY_SCAN -> {
                return Resolution.nearbyScan();
            }
        }
        return new Resolution(references, false);
    }

    static Object readAnchorVector(Object entity) {
        return read(entity, ANCHOR_VECTOR);
    }

    private static void add(List<Object> references, Object value) {
        if (value instanceof Reference<?> reference) {
            value = reference.get();
        }
        if (value != null) {
            references.add(value);
        }
    }

    private static Object read(Object target, MemberKey key) {
        if (target == null) {
            return null;
        }
        Accessor accessor = ACCESSORS.get(target.getClass())
                .computeIfAbsent(key, memberKey -> Accessor.resolve(target.getClass(), memberKey));
        return accessor.read(target);
    }

    record Resolution(List<Object> references, boolean requiresNearbyScan) {
        private static Resolution nearbyScan() {
            return new Resolution(List.of(), true);
        }
    }

    private record MemberKey(String name, boolean method) {
        private static MemberKey method(String name) {
            return new MemberKey(name, true);
        }

        private static MemberKey field(String name) {
            return new MemberKey(name, false);
        }
    }

    private interface Accessor {
        Accessor MISSING = ignored -> null;

        Object read(Object target);

        static Accessor resolve(Class<?> runtimeType, MemberKey key) {
            Class<?> type = runtimeType;
            while (type != null) {
                try {
                    if (key.method()) {
                        Method method = type.getDeclaredMethod(key.name());
                        method.setAccessible(true);
                        return target -> invoke(method, target);
                    }
                    Field field = type.getDeclaredField(key.name());
                    field.setAccessible(true);
                    return target -> read(field, target);
                } catch (NoSuchMethodException | NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                } catch (LinkageError | SecurityException ignored) {
                    return MISSING;
                }
            }
            return MISSING;
        }

        private static Object invoke(Method method, Object target) {
            try {
                return method.invoke(target);
            } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
                return null;
            }
        }

        private static Object read(Field field, Object target) {
            try {
                return field.get(target);
            } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
                return null;
            }
        }
    }
}
