package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arguments typed with wildcards (A4, guava 'Lists.newCopyOnWriteArrayList', 'ImmutableMap.Builder.put') */
public class JavaWildcardArgumentTest {
    @Test
    public void noExplicitTypeArgumentInTernary() throws Exception {
        String source = decompile(
            "package p;\n" +
            "import java.util.*;\n" +
            "public class S {\n" +
            "    static class C2 {\n" +
            "        static <T> Collection<T> cast(Iterable<T> iterable) { return (Collection<T>) iterable; }\n" +
            "    }\n" +
            "    static <E> ArrayList<E> newArrayList(Iterable<? extends E> elements) { return new ArrayList<E>(); }\n" +
            "    public static <E> List<E> copy(Iterable<? extends E> elements) {\n" +
            "        Collection<? extends E> c = (elements instanceof Collection) ? C2.cast(elements) : newArrayList(elements);\n" +
            "        return new ArrayList<E>(c);\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("? C2.cast(elements) : newArrayList(elements);"), source);
    }

    @Test
    public void receiverTypeArgumentIsNotWidenedByAWildcardArgument() throws Exception {
        String source = decompile(
            "package p;\n" +
            "import java.util.*;\n" +
            "public class S<K, V> {\n" +
            "    List<Map.Entry<K, V>> entries = new ArrayList<>();\n" +
            "    @SuppressWarnings(\"unchecked\")\n" +
            "    public S<K, V> put(Map.Entry<? extends K, ? extends V> entry) {\n" +
            "        if (entry instanceof AbstractMap.SimpleEntry) {\n" +
            "            Map.Entry<K, V> e = (Map.Entry<K, V>) entry;\n" +
            "            entries.add(e);\n" +
            "        }\n" +
            "        return this;\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("this.entries.add((Map.Entry)"), source);
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
