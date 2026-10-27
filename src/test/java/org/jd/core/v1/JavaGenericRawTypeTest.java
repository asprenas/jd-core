package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Raw types required by the recompilation (guava Sets) */
public class JavaGenericRawTypeTest {
    @Test
    public void rawCastOfChainedGenericInvocation() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Ordering",
            "package p;\n" +
            "import java.util.Comparator;\n" +
            "public abstract class Ordering<T> implements Comparator<T> {\n" +
            "    @SuppressWarnings(\"rawtypes\")\n" +
            "    public static <C extends Comparable> Ordering<C> natural() { return null; }\n" +
            "    public <S extends T> Ordering<S> reverse() { return null; }\n" +
            "}\n");
        sources.put("p/Descending",
            "package p;\n" +
            "import java.util.Comparator;\n" +
            "public class Descending<E> {\n" +
            "    Comparator<? super E> forward;\n" +
            "    @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
            "    public Comparator<? super E> comparator() {\n" +
            "        if (forward == null) {\n" +
            "            return (Comparator) Ordering.natural().reverse();\n" +
            "        }\n" +
            "        return forward;\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Descending", Collections.emptyMap());
        assertTrue(source.contains("return (Comparator)Ordering.natural().reverse();"), "raw cast");
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/Ordering", sources.get("p/Ordering")),
            new JavaSourceFileObject("p/Descending", source)));
    }

    @Test
    public void instanceOfNonStaticInnerTypeOfGenericOuterType() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Cartesian",
            "package p;\n" +
            "import java.util.List;\n" +
            "public class Cartesian<B> {\n" +
            "    class Axis {\n" +
            "        List<B> choices;\n" +
            "        public boolean equals(Object obj) {\n" +
            "            if (obj instanceof Cartesian.Axis) {\n" +
            "                Axis that = (Axis) obj;\n" +
            "                return choices.equals(that.choices);\n" +
            "            }\n" +
            "            return false;\n" +
            "        }\n" +
            "        public int hashCode() { return 0; }\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Cartesian", Collections.emptyMap());
        assertTrue(source.contains("obj instanceof Cartesian.Axis"), "raw qualified inner type");
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Cartesian", source)));
    }
}
