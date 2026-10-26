package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Type parameter bounds and the target type (A4, guava 'Ordering.natural()', 'Ranges.atLeast(domain.minValue())') */
public class JavaGenericBoundTest {
    @Test
    public void rawCastWhenTargetTypeDoesNotFitTheBound() throws Exception {
        String source = decompile(
            "package p;\n" +
            "import java.util.Comparator;\n" +
            "public class S<E> {\n" +
            "    static class Ord<T> implements Comparator<T> {\n" +
            "        public int compare(T a, T b) { return 0; }\n" +
            "        @SuppressWarnings(\"rawtypes\")\n" +
            "        static <C extends Comparable> Ord<C> natural() { return new Ord<C>(); }\n" +
            "    }\n" +
            "    Comparator<? super E> c;\n" +
            "    S(Comparator<? super E> c) { this.c = c; }\n" +
            "    @SuppressWarnings(\"unchecked\")\n" +
            "    S() { this((Comparator) Ord.natural()); }\n" +
            "    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n" +
            "    static <E> Ord<E> a() {\n" +
            "        Ord<E> o = (Ord<E>) Ord.<Comparable>natural();\n" +
            "        return o;\n" +
            "    }\n" +
            "    static <E extends Comparable<E>> Ord<E> b() {\n" +
            "        Ord<E> o = Ord.natural();\n" +
            "        return o;\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("this((Comparator)Ord.natural());"), source);
        assertTrue(source.contains("Ord<E> o = (Ord)Ord.natural();"), source);
        // 'E extends Comparable<E>' fits the bound: no cast
        assertTrue(source.contains("Ord<E> o = Ord.natural();"), source);
    }

    @Test
    public void noCastWhenTheBoundFits() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S<C extends Comparable> {\n" +
            "    static abstract class Domain<C extends Comparable> {\n" +
            "        public C minValue() { return null; }\n" +
            "    }\n" +
            "    static <C extends Comparable<?>> S<C> atLeast(C endpoint) { return new S(); }\n" +
            "    S<C> intersection(S<C> other) { return this; }\n" +
            "    @SuppressWarnings(\"unchecked\")\n" +
            "    public S<C> canonical(Domain<C> domain) {\n" +
            "        S<C> r = this;\n" +
            "        r = r.intersection(atLeast(domain.minValue()));\n" +
            "        return r;\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("r = r.intersection(atLeast(domain.minValue()));"), source);
    }

    protected static String decompile(String javaSource) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/S", javaSource);

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/S", Collections.emptyMap());
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
        return source;
    }
}
