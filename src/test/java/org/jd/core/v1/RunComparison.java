package org.jd.core.v1;

import org.jd.core.v1.loader.ZipLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Calls the same methods on the original class (from the sample zip) and on the recompiled decompiled
 * class (build/test-recompiled, written by CompilerUtil) and reports different results. This catches
 * output that recompiles but does not behave like the original.
 *
 * Calls come from an optional resource java&lt;N&gt;/&lt;Sample&gt;.run, one call per line, '#' starts a comment:
 *
 *   methodName arg1 arg2 ...        method of the sample class
 *   Nested.methodName arg1 ...      method of a nested class of the sample ('A.B.m' for deeper classes)
 *   new Nested arg1 ...             constructor of a nested class, the result is the new instance
 *   with(arg1,arg2) methodName ...  instance method called on 'new Sample(arg1, arg2)' (no blank inside 'with(...)')
 *
 * Arguments are separated by blanks and converted to the parameter types of the only method (or constructor)
 * with that name and argument count:
 * <ul>
 *   <li>primitives and their wrappers, String ("null" is null, "\"\"" is the empty string, "\"a b\"" a string with
 *       blanks), enum constant names</li>
 *   <li>for an Object (or Number, ...) parameter: "int:5", "long:5", "double:1.5" are boxed values</li>
 *   <li>"list:a|b|int:3": an ArrayList, each element converted like an Object argument ("list:" is empty)</li>
 *   <li>"Point(1,2)": a new instance of the nested class Point of the sample, arguments converted recursively
 *       (no blank inside). "Line(Point(0,0),Point(1,1))" nests</li>
 *   <li>"reader:abc", "in:abc", "out:": a Reader, an InputStream reading "abc", an OutputStream. Closing them prints
 *       "[closed]" ("[closed out:written text]"), the printed text is compared: the resources must be closed once</li>
 *   <li>"fn:length": a function of the JDK for a Comparator (natural, reverse, length), Function (identity, string,
 *       length, upper), Predicate (isNull, isEmpty, true) or Supplier (returns the name) parameter</li>
 * </ul>
 * Instance methods are called on a new instance (no-argument constructor). A non-static inner class gets a new
 * instance of its enclosing class as outer instance.
 * Results are compared with String.valueOf, thrown exceptions by class name. A returned Supplier is called and
 * its result compared ('supplied ...', recursively), a returned Runnable is run. A returned Comparator, Function,
 * BiFunction, IntFunction or Predicate is applied to a few strings and integers. Arrays are compared by content.
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

                if (System.getenv("JDCORE_RUN_VERBOSE") != null) {
                    System.out.println("run " + internalName + ": " + call + " -> " + expected);
                }

                if (expected.startsWith("error:")) {
                    // A wrong .run line, not a decompiler difference
                    differences.add(call + "  invalid call: " + expected);
                } else if (!expected.equals(actual)) {
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

    /** The text printed on System.out by the call is a part of the result */
    private static String invoke(Class<?> sample, String call) {
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();

        System.setOut(new java.io.PrintStream(printed, true));

        try {
            String result = invokeCall(sample, call);
            return (printed.size() == 0) ? result : result + ", printed " + printed.toString().replace("\r", "").replace("\n", "\\n");
        } finally {
            System.setOut(out);
        }
    }

    private static String invokeCall(Class<?> sample, String call) {
        String[] parts = splitCall(call);
        String[] constructorValues = null;

        if (parts[0].startsWith("with(") && parts[0].endsWith(")")) {
            constructorValues = splitArguments(parts[0].substring(5, parts[0].length() - 1));
            parts = copyOfRange(parts, 1);
        }

        try {
            if (parts[0].equals("new")) {
                Class<?> type = findNestedClass(sample, parts[1]);
                String[] values = copyOfRange(parts, 2);
                return "returned " + describe(newInstance(sample, findConstructor(type, values.length), values), 0);
            }

            Class<?> type = sample;
            String name = parts[0];
            int index = name.lastIndexOf('.');

            if (index != -1) {
                type = findNestedClass(sample, name.substring(0, index));
                name = name.substring(index + 1);
            }

            String[] values = copyOfRange(parts, 1);
            Method method = (Method)find(type.getDeclaredMethods(), name, values.length);
            Object[] arguments = convertArguments(sample, method.getParameterTypes(), values);
            Object target = null;

            if (!Modifier.isStatic(method.getModifiers())) {
                if (constructorValues == null) {
                    constructorValues = new String[0];
                }
                target = newInstance(sample, findConstructor(type, constructorValues.length), constructorValues);
            }

            method.setAccessible(true);
            return "returned " + describe(method.invoke(target, arguments), 0);
        } catch (InvocationTargetException e) {
            return "threw " + e.getCause().getClass().getName();
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    /** @param name null for a constructor */
    private static Executable find(Executable[] executables, String name, int argumentCount) {
        Executable found = null;

        for (Executable e : executables) {
            if (((name == null) || e.getName().equals(name)) && (e.getParameterCount() == argumentCount) && !e.isSynthetic()) {
                if (found != null) {
                    throw new IllegalArgumentException("ambiguous " + name + "/" + argumentCount);
                }
                found = e;
            }
        }

        if (found == null) {
            throw new IllegalArgumentException("no " + name + "/" + argumentCount);
        }
        return found;
    }

    /** @param path 'Point' or 'Outer.Inner': nested classes of the sample */
    private static Class<?> findNestedClass(Class<?> sample, String path) throws ClassNotFoundException {
        return Class.forName(sample.getName() + "$" + path.replace('.', '$'), true, sample.getClassLoader());
    }

    /** The constructor with 'argumentCount' arguments, plus the outer instance of a non-static inner class */
    private static Constructor<?> findConstructor(Class<?> type, int argumentCount) {
        return (Constructor<?>)find(type.getDeclaredConstructors(), null, isInnerClass(type) ? argumentCount + 1 : argumentCount);
    }

    private static boolean isInnerClass(Class<?> type) {
        return type.isMemberClass() && !Modifier.isStatic(type.getModifiers());
    }

    /** Arguments of a non-static inner class: the outer instance is a new instance of the enclosing class */
    private static Object newInstance(Class<?> sample, Constructor<?> constructor, String[] values) throws Exception {
        Class<?> type = constructor.getDeclaringClass();
        Class<?>[] types = constructor.getParameterTypes();
        Object[] arguments;

        constructor.setAccessible(true);

        if (isInnerClass(type)) {
            Class<?> outer = type.getEnclosingClass();
            Object[] others = convertArguments(sample, java.util.Arrays.copyOfRange(types, 1, types.length), values);

            arguments = new Object[types.length];
            arguments[0] = newInstance(sample, findConstructor(outer, 0), new String[0]);
            System.arraycopy(others, 0, arguments, 1, others.length);
        } else {
            arguments = convertArguments(sample, types, values);
        }

        return constructor.newInstance(arguments);
    }

    private static Object[] convertArguments(Class<?> sample, Class<?>[] types, String[] values) throws Exception {
        Object[] arguments = new Object[values.length];

        for (int i = 0; i < values.length; i++) {
            arguments[i] = convert(sample, types[i], values[i]);
        }

        return arguments;
    }

    /** Results of lambdas are compared by behavior: their 'toString' contains generated class names */
    @SuppressWarnings("unchecked")
    private static String describe(Object result, int depth) {
        if ((depth < 3) && (result instanceof Supplier)) {
            return "supplied " + describe(((Supplier<?>)result).get(), depth + 1);
        }
        if ((depth < 3) && (result instanceof Runnable) && !(result instanceof Thread)) {
            ((Runnable)result).run();
            return "ran";
        }
        if ((depth < 3) && (result instanceof java.util.Comparator)) {
            return "comparator " + applyToSamples(v -> Integer.signum(((java.util.Comparator<Object>)result).compare(v[0], v[1])), true);
        }
        if ((depth < 3) && (result instanceof java.util.function.Function)) {
            return "function " + applyToSamples(v -> describe(((java.util.function.Function<Object, ?>)result).apply(v[0]), depth + 1), false);
        }
        if ((depth < 3) && (result instanceof java.util.function.BiFunction)) {
            return "bifunction " + applyToSamples(v -> describe(((java.util.function.BiFunction<Object, Object, ?>)result).apply(v[0], v[1]), depth + 1), true);
        }
        if ((depth < 3) && (result instanceof java.util.function.IntFunction)) {
            return "intfunction " + applyToSamples(v -> describe(((java.util.function.IntFunction<?>)result).apply((v[0] instanceof Integer) ? (Integer)v[0] : -1), depth + 1), false);
        }
        if ((result != null) && result.getClass().isArray()) {
            // 'int[]' and 'Object[]' strings contain a hash code
            return java.util.Arrays.deepToString(new Object[] { result });
        }
        if ((depth < 3) && (result instanceof java.util.function.Predicate)) {
            return "predicate " + applyToSamples(v -> ((java.util.function.Predicate<Object>)result).test(v[0]), false);
        }
        if ((result != null) && (depth < 3) && isSampleClassWithoutToString(result.getClass())) {
            return describeFields(result, depth);
        }
        return String.valueOf(result);
    }

    /**
     * A returned function is applied to strings, then to integers (its parameter type is unknown): '[a->A, b->B]'.
     * A value it can not take gives the exception class name.
     */
    private static String applyToSamples(java.util.function.Function<Object[], Object> function, boolean pairs) {
        Object[][] inputs = pairs ?
            new Object[][] { { "a", "b" }, { "b", "a" }, { "ab", "ab" }, { 1, 2 }, { 2, 1 }, { 3, 3 } } :
            new Object[][] { { "" }, { "ab" }, { 0 }, { 5 }, { null } };
        StringBuilder sb = new StringBuilder("[");

        for (Object[] input : inputs) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            try {
                sb.append(function.apply(input));
            } catch (RuntimeException e) {
                sb.append(e.getClass().getSimpleName());
            }
        }

        return sb.append(']').toString();
    }

    /** A class of the sample (not of the JDK) that inherits 'Object.toString': its string contains a hash code */
    private static boolean isSampleClassWithoutToString(Class<?> type) {
        if ((type.getClassLoader() == null) || type.isSynthetic() || type.getName().contains("$$Lambda")) {
            return false;
        }
        try {
            return type.getMethod("toString").getDeclaringClass() == Object.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** 'Name{field=value, ...}' with the instance fields of the class and of its super classes */
    private static String describeFields(Object object, int depth) {
        StringBuilder sb = new StringBuilder(object.getClass().getSimpleName()).append('{');
        String separator = "";

        try {
            for (Class<?> c = object.getClass(); (c != null) && (c != Object.class); c = c.getSuperclass()) {
                java.lang.reflect.Field[] fields = c.getDeclaredFields();
                // The decompiled class may declare the fields in another order
                java.util.Arrays.sort(fields, java.util.Comparator.comparing(java.lang.reflect.Field::getName));

                for (java.lang.reflect.Field field : fields) {
                    if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                        field.setAccessible(true);
                        sb.append(separator).append(field.getName()).append('=').append(describe(field.get(object), depth + 1));
                        separator = ", ";
                    }
                }
            }
        } catch (IllegalAccessException e) {
            sb.append(e);
        }

        return sb.append('}').toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(Class<?> sample, Class<?> type, String value) throws Exception {
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
        if ((type == byte.class) || (type == Byte.class)) {
            return Byte.valueOf(value);
        }
        if (type.isEnum()) {
            return Enum.valueOf((Class<Enum>)type, value);
        }
        if (value.startsWith("int:")) {
            return Integer.valueOf(value.substring(4));
        }
        if (value.startsWith("long:")) {
            return Long.valueOf(value.substring(5));
        }
        if (value.startsWith("double:")) {
            return Double.valueOf(value.substring(7));
        }
        if (type.isArray() && value.startsWith("array:")) {
            String[] elements = (value.length() > 6) ? value.substring(6).split("\\|", -1) : new String[0];
            Object array = java.lang.reflect.Array.newInstance(type.getComponentType(), elements.length);

            for (int i = 0; i < elements.length; i++) {
                java.lang.reflect.Array.set(array, i, convert(sample, type.getComponentType(), elements[i]));
            }
            return array;
        }
        if (value.startsWith("list:")) {
            List<Object> list = new ArrayList<>();
            if (value.length() > 5) {
                for (String element : value.substring(5).split("\\|")) {
                    list.add(convert(sample, Object.class, element));
                }
            }
            return list;
        }
        int open = value.indexOf('(');
        if ((open > 0) && value.endsWith(")") && Character.isUpperCase(value.charAt(0))) {
            // 'Point(1,2)': a nested class of the sample
            Class<?> nested = findNestedClass(sample, value.substring(0, open));
            String[] values = splitArguments(value.substring(open + 1, value.length() - 1));
            return newInstance(sample, findConstructor(nested, values.length), values);
        }
        if (value.matches("[A-Z]\\w*(\\.[A-Z]\\w*)*\\.[A-Z][A-Z0-9_]*")) {
            // 'Color.RED': a constant of a nested enum of the sample
            int dot = value.lastIndexOf('.');

            try {
                Class<?> nested = findNestedClass(sample, value.substring(0, dot));

                if (nested.isEnum()) {
                    return Enum.valueOf((Class<Enum>)nested, value.substring(dot + 1));
                }
            } catch (ClassNotFoundException e) {
                // A string
            }
        }
        if (value.startsWith("reader:") && type.isAssignableFrom(ClosingReader.class)) {
            return new ClosingReader(value.substring(7));
        }
        if (value.startsWith("in:") && type.isAssignableFrom(ClosingInputStream.class)) {
            return new ClosingInputStream(value.substring(3));
        }
        if (value.equals("out:") && type.isAssignableFrom(ClosingOutputStream.class)) {
            return new ClosingOutputStream();
        }
        if (value.startsWith("fn:")) {
            return function(type, value.substring(3));
        }
        if ((value.length() >= 2) && value.startsWith("\"") && value.endsWith("\"")) {
            // '"a b"': a string with blanks, '""' the empty string
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 'fn:name': a function of the JDK for a functional interface parameter */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object function(Class<?> type, String name) {
        if (type == java.util.Comparator.class) {
            switch (name) {
                case "natural": return java.util.Comparator.naturalOrder();
                case "reverse": return java.util.Comparator.reverseOrder();
                case "length": return java.util.Comparator.comparingInt((Object o) -> String.valueOf(o).length());
            }
        } else if (type == java.util.function.Function.class) {
            switch (name) {
                case "identity": return java.util.function.Function.identity();
                case "string": return (java.util.function.Function<Object, String>)String::valueOf;
                case "length": return (java.util.function.Function<Object, Integer>)o -> String.valueOf(o).length();
                case "upper": return (java.util.function.Function<Object, String>)o -> String.valueOf(o).toUpperCase();
            }
        } else if (type == java.util.function.Predicate.class) {
            switch (name) {
                case "isNull": return (java.util.function.Predicate<Object>)java.util.Objects::isNull;
                case "isEmpty": return (java.util.function.Predicate<Object>)o -> String.valueOf(o).isEmpty();
                case "true": return (java.util.function.Predicate<Object>)o -> true;
            }
        } else if (type == java.util.function.Supplier.class) {
            return (Supplier<Object>)() -> name;
        }
        throw new IllegalArgumentException("no function " + name + " for " + type.getName());
    }

    /** Blanks separate the arguments, except in '"a b"' */
    private static String[] splitCall(String call) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < call.length(); i++) {
            char c = call.charAt(i);

            if (c == '"') {
                quoted = !quoted;
                part.append(c);
            } else if (Character.isWhitespace(c) && !quoted) {
                if (part.length() > 0) {
                    parts.add(part.toString());
                    part.setLength(0);
                }
            } else {
                part.append(c);
            }
        }

        if (part.length() > 0) {
            parts.add(part.toString());
        }
        return parts.toArray(new String[0]);
    }

    private static final class ClosingReader extends java.io.StringReader {
        ClosingReader(String text) { super(text); }
        @Override public void close() { System.out.print("[closed]"); super.close(); }
    }

    private static final class ClosingInputStream extends java.io.ByteArrayInputStream {
        ClosingInputStream(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() throws java.io.IOException { System.out.print("[closed]"); super.close(); }
    }

    private static final class ClosingOutputStream extends java.io.ByteArrayOutputStream {
        @Override public void close() throws java.io.IOException { System.out.print("[closed out:" + toString() + "]"); super.close(); }
    }

    /** "1,Point(2,3),x" -> {"1", "Point(2,3)", "x"} */
    private static String[] splitArguments(String text) {
        List<String> values = new ArrayList<>();
        int depth = 0;
        int start = 0;

        if (text.isEmpty()) {
            return new String[0];
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if ((c == ',') && (depth == 0)) {
                values.add(text.substring(start, i));
                start = i + 1;
            }
        }

        values.add(text.substring(start));
        return values.toArray(new String[0]);
    }

    private static String[] copyOfRange(String[] array, int from) {
        String[] copy = new String[array.length - from];
        System.arraycopy(array, from, copy, 0, copy.length);
        return copy;
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
