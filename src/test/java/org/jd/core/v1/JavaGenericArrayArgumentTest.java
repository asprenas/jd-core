package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Array of type variables passed to an 'Object[]' parameter (guava ReverseNaturalOrdering) */
public class JavaGenericArrayArgumentTest {
    @Test
    public void typeVariableArrayWithoutObjectArrayCast() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Ordering",
            "package p;\n" +
            "public abstract class Ordering<T> {\n" +
            "    public <E extends T> E max(E a, E b, E c, E... rest) { return a; }\n" +
            "}\n");
        sources.put("p/Natural",
            "package p;\n" +
            "@SuppressWarnings(\"rawtypes\")\n" +
            "public class Natural extends Ordering<Comparable> {\n" +
            "    static final Natural INSTANCE = new Natural();\n" +
            "}\n");
        sources.put("p/Reverse",
            "package p;\n" +
            "@SuppressWarnings(\"rawtypes\")\n" +
            "public class Reverse {\n" +
            "    public <E extends Comparable> E min(E a, E b, E c, E... rest) {\n" +
            "        return Natural.INSTANCE.max(a, b, c, rest);\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Reverse", Collections.emptyMap());
        assertTrue(source.contains("Natural.INSTANCE.max(a, b, c, rest)"), "no '(Object[])' cast");
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/Ordering", sources.get("p/Ordering")),
            new JavaSourceFileObject("p/Natural", sources.get("p/Natural")),
            new JavaSourceFileObject("p/Reverse", source)));
    }
}
