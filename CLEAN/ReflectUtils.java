package com.blankj.util;

import java.lang.reflect.*;
import java.util.*;

/**
 * ReflectUtils: Clean, fluent API for common reflection tasks.
 */
public final class ReflectUtils {
    private ReflectUtils() { /* Prevent instantiation */ }

    public static Class<?> forName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new ReflectException(e);
        }
    }

    public static <T> T newInstance(Class<T> clazz, Object... args) {
        try {
            Constructor<T> ctor = findMatchingConstructor(clazz, args);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new ReflectException(e);
        }
    }

    public static Object getFieldValue(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new ReflectException(e);
        }
    }

    public static void setFieldValue(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new ReflectException(e);
        }
    }

    public static Object invoke(Object target, String methodName, Object... args) {
        try {
            Method method = findMethod(target.getClass(), methodName, args);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Exception e) {
            throw new ReflectException(e);
        }
    }

    // -- Internal helpers --

    private static Constructor<?> findMatchingConstructor(Class<?> cls, Object[] args) throws NoSuchMethodException {
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            if (matches(ctor.getParameterTypes(), args)) {
                return ctor;
            }
        }
        throw new NoSuchMethodException("No matching constructor for " + cls.getName());
    }

    private static Method findMethod(Class<?> cls, String name, Object[] args) throws NoSuchMethodException {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && matches(m.getParameterTypes(), args)) {
                return m;
            }
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && matches(m.getParameterTypes(), args)) {
                return m;
            }
        }
        throw new NoSuchMethodException("No matching method " + name + " in " + cls.getName());
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> search = cls;
        while (search != null) {
            try {
                return search.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                search = search.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean matches(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != args.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            if (args[i] != null && !wrapper(paramTypes[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrapper(Class<?> cls) {
        if (!cls.isPrimitive()) return cls;
        if (cls == int.class) return Integer.class;
        if (cls == boolean.class) return Boolean.class;
        if (cls == long.class) return Long.class;
        if (cls == double.class) return Double.class;
        if (cls == float.class) return Float.class;
        if (cls == short.class) return Short.class;
        if (cls == byte.class) return Byte.class;
        if (cls == char.class) return Character.class;
        return cls;
    }

    public static class ReflectException extends RuntimeException {
        public ReflectException(Throwable cause) {
            super(cause);
        }
    }
}
