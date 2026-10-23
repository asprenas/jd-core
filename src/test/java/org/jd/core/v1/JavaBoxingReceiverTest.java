package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boxing of the receiver of a method invocation (A13) */
public class JavaBoxingReceiverTest {
    @Test
    public void boxedReceiverKeepsCast() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Boxes",
            "package p;\n" +
            "public class Boxes {\n" +
            "    public static int hashD(double value) { return ((Double) value).hashCode(); }\n" +
            "    public static String str(int value) { return ((Integer) value).toString(); }\n" +
            "    public static int cmp(long a, long b) { return ((Long) a).compareTo(b); }\n" +
            "    public static boolean eq(char c, Object o) { return ((Character) c).equals(o); }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Boxes", Collections.emptyMap());
        assertTrue(source.contains("((Double)value).hashCode()"), source);
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Boxes", source)), source);
    }
}
