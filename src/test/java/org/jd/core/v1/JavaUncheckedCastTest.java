package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unchecked cast of a sub type of 'S<Object>' to 'S<T>', without 'checkcast' in the byte code (A14, guava 'Optional.absent()') */
public class JavaUncheckedCastTest {
    @Test
    public void castOfSubTypeWithObjectTypeArgument() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/S",
            "package p;\n" +
            "public abstract class S<T> {\n" +
            "    static final class Absent extends S<Object> {\n" +
            "        static final Absent INSTANCE = new Absent();\n" +
            "    }\n" +
            "    static class Gen<X> extends S<X> {}\n" +
            "    static Gen RAW = new Gen();\n" +
            "    @SuppressWarnings(\"unchecked\")\n" +
            "    public static <T> S<T> absent() { return (S<T>) Absent.INSTANCE; }\n" +
            "    @SuppressWarnings(\"unchecked\")\n" +
            "    public static <T> S<T> raw() { return (S<T>) RAW; }\n" +
            "    @SuppressWarnings(\"unchecked\")\n" +
            "    public static <T> S<T> local() {\n" +
            "        S<T> s = (S<T>) Absent.INSTANCE;\n" +
            "        return s;\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/S", Collections.emptyMap());
        assertTrue(source.contains("return (S<T>)Absent.INSTANCE;"), source);
        assertTrue(source.contains("S<T> s = (S<T>)Absent.INSTANCE;"), source);
        // A raw type is assignable to 'S<T>': no cast
        assertTrue(source.contains("return RAW;"), source);
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
    }
}
