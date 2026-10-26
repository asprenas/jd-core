package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lambdas and method references as arguments (A4, junit-jupiter-api 'MethodOrderer', 'AssertionUtils') */
public class JavaFunctionalArgumentTest {
    @Test
    public void explicitTypeArgumentsOfAReceiverWithAnImplicitLambda() throws Exception {
        String source = decompile(
            "package p;\n" +
            "import java.util.*;\n" +
            "import java.lang.reflect.Method;\n" +
            "public class S {\n" +
            "    interface D { Method getMethod(); }\n" +
            "    static String parameterList(Method m) { return \"\"; }\n" +
            "    static final Comparator<D> comparator = Comparator.<D, String>comparing(d -> d.getMethod().getName())\n" +
            "            .thenComparing(d -> parameterList(d.getMethod()));\n" +
            "}\n");

        assertTrue(source.contains("Comparator.<D, String>comparing(d -> d.getMethod().getName())"), source);
    }

    @Test
    public void noCastOfAMethodReference() throws Exception {
        String source = decompile(
            "package p;\n" +
            "import java.util.*;\n" +
            "import java.util.stream.Collectors;\n" +
            "public class S {\n" +
            "    static String formatIndexes(Deque<Integer> indexes) {\n" +
            "        String s = indexes.stream().map(Object::toString).collect(Collectors.joining(\"][\", \"[\", \"]\"));\n" +
            "        return s;\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains(".map(Object::toString)"), source);
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
