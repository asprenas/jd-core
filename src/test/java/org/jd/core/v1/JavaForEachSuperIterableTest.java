package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** For-each on a not generic inner type that is an 'Iterable' parameterized by its super type (guava ImmutableMultiset) */
public class JavaForEachSuperIterableTest {
    @Test
    public void forEachOnInnerTypeWithParameterizedSuperType() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Multi",
            "package p;\n" +
            "import java.util.AbstractSet;\n" +
            "import java.util.List;\n" +
            "public abstract class Multi<E> {\n" +
            "    abstract class EntrySet extends AbstractSet<List<E>> {\n" +
            "        int count() {\n" +
            "            int index = 0;\n" +
            "            for (List<?> element : this) {\n" +
            "                index += element.size();\n" +
            "            }\n" +
            "            return index;\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Multi", Collections.emptyMap());
        assertTrue(source.contains("for (List<?> element : this)"), "no 'Iterable' cast");
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Multi", source)));
    }
}
