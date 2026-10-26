package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Type variables of other declarations ('T' of 'checkNotNull', 'C' of 'Range') must not be printed (A4, guava 'primitives', 'GeneralRange') */
public class JavaForeignTypeVariableTest {
    @Test
    public void primitiveArrayStoreBindsTheValue() throws Exception {
        String source = decompile(
            "package p;\n" +
            "import java.util.Collection;\n" +
            "public class S {\n" +
            "    static <T> T checkNotNull(T reference) { return reference; }\n" +
            "    public static int[] toArray(Collection<? extends Number> collection) {\n" +
            "        Object[] boxedArray = collection.toArray();\n" +
            "        int len = boxedArray.length;\n" +
            "        int[] array = new int[len];\n" +
            "        for (int i = 0; i < len; i++) {\n" +
            "            array[i] = ((Number) checkNotNull(boxedArray[i])).intValue();\n" +
            "        }\n" +
            "        return array;\n" +
            "    }\n" +
            "}\n", "-g");

        assertTrue(source.contains("array[i] = ((Number)checkNotNull(boxedArray[i])).intValue();"), source);
    }

    @Test
    public void ternaryOfAnotherTypeVariable() throws Exception {
        for (String option : new String[] { "-g", "-g:none" }) {
            String source = decompile(
                "package p;\n" +
                "public class S<T> {\n" +
                "    static class R<C extends Comparable> {\n" +
                "        C v;\n" +
                "        boolean has() { return v != null; }\n" +
                "        C lower() { return v; }\n" +
                "    }\n" +
                "    S(T a, T b) {}\n" +
                "    static <T extends Comparable> S<T> from(R<T> range) {\n" +
                "        T lower = range.has() ? range.lower() : null;\n" +
                "        T upper = range.has() ? range.lower() : null;\n" +
                "        return new S<T>(lower, upper);\n" +
                "    }\n" +
                "}\n", option);

            assertFalse(source.contains("(C)"), source);
        }
    }

    protected static String decompile(String javaSource, String debugOption) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/S", javaSource);

        InMemorySample sample = new InMemorySample(sources, debugOption, "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/S", Collections.emptyMap());
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
        return source;
    }
}
