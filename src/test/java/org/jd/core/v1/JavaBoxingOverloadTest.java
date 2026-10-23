package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boxing that selects the overload of a call (A3) */
public class JavaBoxingOverloadTest {
    @Test
    public void boxedArgumentSelectsObjectOverload() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Eq",
            "package p;\n" +
            "public class Eq {\n" +
            "    static void eq(short expected, short actual) { }\n" +
            "    static void eq(long expected, long actual) { }\n" +
            "    static void eq(Object expected, Object actual) { }\n" +
            "}\n");
        sources.put("p/Asserts",
            "package p;\n" +
            "public class Asserts {\n" +
            "    public static void a(short expected, Short actual) { Eq.eq((Short) expected, actual); }\n" +
            "    public static void b(Short expected, short actual) { Eq.eq(expected, (Short) actual); }\n" +
            "    public static void c(short expected, short actual) { Eq.eq(expected, actual); }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Asserts", Collections.emptyMap());
        assertTrue(source.contains("Eq.eq(expected, actual);"), "no cast for the primitive overload");
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/Eq", sources.get("p/Eq")),
            new JavaSourceFileObject("p/Asserts", source)));
    }
}
