package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MinecraftReflectionCompat {

    private MinecraftReflectionCompat() {}

    public static Object readObjectField(Object target, String fieldName) {
        Field field = findField(target == null ? null : target.getClass(), fieldName);
        if (field == null) {
            return null;
        }

        try {
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static <T> T readTypedField(Object target, Class<T> expectedType, String fieldName) {
        Field field = findField(target == null ? null : target.getClass(), fieldName);
        if (field == null || expectedType == null || !expectedType.isAssignableFrom(field.getType())) {
            return null;
        }

        try {
            Object value = field.get(target);
            return expectedType.isInstance(value) ? expectedType.cast(value) : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static <T> T readStaticTypedField(Class<?> ownerType, Class<T> expectedType, String fieldName) {
        Field field = findField(ownerType, fieldName);
        if (field == null || expectedType == null || !expectedType.isAssignableFrom(field.getType())) {
            return null;
        }

        try {
            Object value = field.get(null);
            return expectedType.isInstance(value) ? expectedType.cast(value) : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static void invokeVoidMethod(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) {
        Method method = findMethod(target == null ? null : target.getClass(), methodName, parameterTypes);
        if (method == null) {
            return;
        }

        try {
            method.invoke(target, arguments);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public static Boolean invokeBooleanMethod(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) {
        Method method = findMethod(target == null ? null : target.getClass(), methodName, parameterTypes);
        if (method == null) {
            return null;
        }

        try {
            Object result = method.invoke(target, arguments);
            return result instanceof Boolean booleanValue ? booleanValue : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static Method findMethod(Class<?> type, String methodName, Class<?>[] parameterTypes) {
        if (type == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        Class<?> currentType = type;
        while (currentType != null) {
            try {
                Method method = currentType.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                currentType = currentType.getSuperclass();
            }
        }
        return null;
    }

    public static Field findField(Class<?> type, String fieldName) {
        if (type == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        Class<?> currentType = type;
        while (currentType != null) {
            try {
                Field field = currentType.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                currentType = currentType.getSuperclass();
            }
        }
        return null;
    }
}
