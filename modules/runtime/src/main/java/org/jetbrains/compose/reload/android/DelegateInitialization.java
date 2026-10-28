package org.jetbrains.compose.reload.android;

/** Called only by host-generated guards for proven constant Compose delegate initializers. */
public final class DelegateInitialization {
    private DelegateInitialization() {}

    public static Object read(Object owner, Object existing, Class<?> declaringClass, String fieldName, String recipe)
            throws ReflectiveOperationException {
        if (existing != null) return existing;
        synchronized (owner) {
            java.lang.reflect.Field field = declaringClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(owner);
            if (value != null) return value;
            value = create(declaringClass.getClassLoader(), recipe);
            if (value == null) throw new IllegalStateException("Compose delegate factory returned null");
            // Keep the final field and existing non-null delegate. Never invoke the owner's constructor.
            field.set(owner, value);
            return value;
        }
    }

    private static Object create(ClassLoader loader, String recipe) throws ReflectiveOperationException {
        String[] parts = recipe.split("\\|", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid delegate recipe");
        String literal = new String(java.util.Base64.getDecoder().decode(parts[3]), java.nio.charset.StandardCharsets.UTF_8);
        Object value;
        switch (parts[2]) {
            case "null": value = null; break;
            case "String": value = literal; break;
            case "Integer": value = Integer.valueOf(literal); break;
            case "Long": value = Long.valueOf(literal); break;
            case "Float": value = Float.intBitsToFloat(Integer.parseUnsignedInt(literal)); break;
            case "Double": value = Double.longBitsToDouble(Long.parseUnsignedLong(literal)); break;
            case "Boolean": value = Boolean.valueOf(literal); break;
            case "Byte": value = Byte.valueOf(literal); break;
            case "Short": value = Short.valueOf(literal); break;
            case "Character": value = (char) Integer.parseInt(literal); break;
            default: throw new IllegalArgumentException("Unsupported delegate literal");
        }
        String owner;
        Class<?> parameter;
        switch (parts[0]) {
            case "mutableStateOf":
                if (!parts[1].equals("structuralEqualityPolicy") && !parts[1].equals("referentialEqualityPolicy") &&
                        !parts[1].equals("neverEqualPolicy")) throw new IllegalArgumentException("Unsupported state policy");
                Class<?> factory = loader.loadClass("androidx.compose.runtime.SnapshotStateKt");
                Object policy = factory.getMethod(parts[1]).invoke(null);
                return factory.getMethod("mutableStateOf", Object.class,
                        loader.loadClass("androidx.compose.runtime.SnapshotMutationPolicy")).invoke(null, value, policy);
            case "mutableIntStateOf": owner = "SnapshotIntStateKt"; parameter = Integer.TYPE; break;
            case "mutableLongStateOf": owner = "SnapshotLongStateKt"; parameter = Long.TYPE; break;
            case "mutableFloatStateOf": owner = "PrimitiveSnapshotStateKt"; parameter = Float.TYPE; break;
            case "mutableDoubleStateOf": owner = "SnapshotDoubleStateKt"; parameter = Double.TYPE; break;
            default: throw new IllegalArgumentException("Unsupported delegate factory");
        }
        return loader.loadClass("androidx.compose.runtime." + owner).getMethod(parts[0], parameter).invoke(null, value);
    }
}
