package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 'for' each over a collection returned by a receiver typed with wildcards (A4, guava 'ImmutableMultimap.Builder.putAll') */
public class JavaForEachWildcardTest {
    @Test
    public void castFromRawIterable() throws Exception {
        for (String option : new String[] { "-g", "-g:none" }) {
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put("p/S",
                "package p;\n" +
                "import java.util.*;\n" +
                "public class S<K, V> {\n" +
                "    interface MM<K, V> { Map<K, Collection<V>> asMap(); }\n" +
                "    void put(K k, Collection<? extends V> v) {}\n" +
                "    public void putAll(MM<? extends K, ? extends V> multimap) {\n" +
                "        for (Map.Entry<? extends K, ? extends Collection<? extends V>> entry : multimap.asMap().entrySet()) {\n" +
                "            put(entry.getKey(), entry.getValue());\n" +
                "        }\n" +
                "    }\n" +
                "}\n");

            InMemorySample sample = new InMemorySample(sources, option, "--release", "8", "-Xlint:-options");

            String source = sample.decompile("p/S", Collections.emptyMap());
            assertTrue(source.contains(">)(Iterable)"), source);
            assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
        }
    }
}
