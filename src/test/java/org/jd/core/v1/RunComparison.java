package org.jd.core.v1;

import org.jd.core.v1.loader.ZipLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls the same methods on the original class (from the sample zip) and on the recompiled decompiled
 * class (build/test-recompiled, written by CompilerUtil) and reports different results. This catches
 * output that recompiles but does not behave like the original.
 *
 * Calls come from an optional resource java&lt;N&gt;/&lt;Sample&gt;.run, one call per line, '#' starts a comment:
 *
 *   methodName arg1 arg2 ...
 *
 * Arguments are separated by blanks and converted to the parameter types of the only method with that
 * name and argument count: primitives and their wrappers, String ("null" is null, "\"\"" is the empty
 * string), enum constant names. For an Object parameter, "int:5" is an Integer. Instance methods are called on
 * a new instance (no-argument constructor).
 * Results are compared with String.valueOf, thrown exceptions by class name.
 */
public final class RunComparison {
    private static final File RECOMPILED_DIRECTORY = new File("build/test-recompiled");

    private RunComparison() {}

    public static List<String> compare(String runResource, ZipLoader originalLoader, String internalName) throws Exception {
        List<String> differences = new ArrayList<>();
        List<String> calls = readCalls(runResource);

        if (calls.isEmpty()) {
            return differences;
        }

        String className = internalName.replace('/', '.');

        try (URLClassLoader recompiledLoader = new URLClassLoader(new URL[] { RECOMPILED_DIRECTORY.toURI().toURL() }, null)) {
            Class<?> original = new ZipClassLoader(originalLoader).loadClass(className);
            Class<?> recompiled = recompiledLoader.loadClass(className);

            for (String call : calls) {
                String expected = invoke(original, call);
                String actual = invoke(recompiled, call);

                if (!expected.equals(actual)) {
                    differences.add(call + "  original: " + expected + "  decompiled: " + actual);
                }
            }
        }

        return differences;
    }

    private static List<String> readCalls(String runResource) throws Exception {
        List<String> calls = new ArrayList<>();

        try (InputStream is = RunComparison.class.getResourceAsStream(runResource)) {
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        calls.add(line);
                    }
                }
            }
        }

        return calls;
    }

    private static String invoke(Class<?> type, String call) {
        String[] parts = call.split("\\s+");
        String name = parts[0];
        int argumentCount = parts.length - 1;
        Method method = null;

        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && (m.getParameterTypes().length == argumentCount)) {
                if (method != null) {
                    return "error: ambiguous method " + name + "/" + argumentCount;
                }
                method = m;
            }
        }

        if (method == null) {
            return "error: no method " + name + "/" + argumentCount;
        }

        try {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[argumentCount];

            for (int i = 0; i < argumentCount; i++) {
                arguments[i] = convert(parameterTypes[i], parts[i + 1]);
            }

            Object target = null;

            if (!Modifier.isStatic(method.getModifiers())) {
                Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                target = constructor.newInstance();
            }

            method.setAccessible(true);
            return "returned " + String.valueOf(method.invoke(target, arguments));
        } catch (InvocationTargetException e) {
            return "threw " + e.getCause().getClass().getName();
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(Class<?> type, String value) {
        if (value.equals("null") && !type.isPrimitive()) {
            return null;
        }
        if ((type == int.class) || (type == Integer.class)) {
            return Integer.valueOf(value);
        }
        if ((type == long.class) || (type == Long.class)) {
            return Long.valueOf(value);
        }
        if ((type == double.class) || (type == Double.class)) {
            return Double.valueOf(value);
        }
        if ((type == float.class) || (type == Float.class)) {
            return Float.valueOf(value);
        }
        if ((type == boolean.class) || (type == Boolean.class)) {
            return Boolean.valueOf(value);
        }
        if ((type == char.class) || (type == Character.class)) {
            return Character.valueOf(value.charAt(0));
        }
        if (type.isEnum()) {
            return Enum.valueOf((Class<Enum>)type, value);
        }
        if (value.startsWith("int:") && !type.isPrimitive() && type.isAssignableFrom(Integer.class)) {
            return Integer.valueOf(value.substring(4));
        }
        if (value.equals("\"\"")) {
            return "";
        }
        return value;
    }

    /** Defines the sample classes from the zip, so the original and the recompiled classes never mix. */
    private static final class ZipClassLoader extends ClassLoader {
        private final ZipLoader zipLoader;

        ZipClassLoader(ZipLoader zipLoader) {
            super(null);
            this.zipLoader = zipLoader;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internalName = name.replace('.', '/');

            try {
                if (zipLoader.canLoad(internalName)) {
                    byte[] bytes = zipLoader.load(internalName);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            } catch (Exception e) {
                throw new ClassNotFoundException(name, e);
            }

            throw new ClassNotFoundException(name);
        }
    }
}
