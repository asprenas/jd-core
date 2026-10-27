package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Raw cast of an argument for a parameter typed by a captured type argument of the receiver (guava TypeToken) */
public class JavaCaptureRawCastTest {
    @Test
    public void rawCastForCapturedReceiver() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Token",
            "package p;\n" +
            "public class Token<T> {\n" +
            "    Class<? super T> rawType;\n" +
            "    public final Token<? super T> getSupertype(Class<? super T> superclass) { return this; }\n" +
            "    @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
            "    Token<? super T> bounded(Token<? super T> bound, Class<? super T> supertype) {\n" +
            "        Token<? super T> result = bound.getSupertype((Class) supertype);\n" +
            "        return result;\n" +
            "    }\n" +
            "    @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
            "    Object wildcard(Token<?> subtype) {\n" +
            "        return subtype.getSupertype((Class) rawType);\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Token", Collections.emptyMap());
        assertTrue(source.contains("bound.getSupertype((Class)supertype)"), "raw cast for 'Token<? super T>'");
        assertTrue(source.contains("subtype.getSupertype((Class)this.rawType)"), "raw cast for 'Token<?>'");
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Token", source)));
    }
}
