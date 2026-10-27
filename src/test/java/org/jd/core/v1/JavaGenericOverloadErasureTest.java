package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generic method overloads: explicit 'Object' bound and cast to the erasure of a type parameter (guava Joiner) */
public class JavaGenericOverloadErasureTest {
    @Test
    public void objectBoundAndErasedParameterCast() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Joiner",
            "package p;\n" +
            "import java.io.IOException;\n" +
            "import java.util.Iterator;\n" +
            "public class Joiner {\n" +
            "    public <A extends Appendable> A appendTo(A appendable, Iterator<?> parts) throws IOException {\n" +
            "        while (parts.hasNext()) { appendable.append(String.valueOf(parts.next())); }\n" +
            "        return appendable;\n" +
            "    }\n" +
            "    public final StringBuilder appendTo(StringBuilder builder, Iterator<?> parts) {\n" +
            "        try {\n" +
            "            appendTo((Appendable) builder, parts);\n" +
            "        } catch (IOException impossible) {\n" +
            "            throw new AssertionError(impossible);\n" +
            "        }\n" +
            "        return builder;\n" +
            "    }\n" +
            "    public final StringBuilder appendTo2(StringBuilder builder, Iterator<?> parts) throws IOException {\n" +
            "        return (StringBuilder) appendTo((Appendable) builder, parts);\n" +
            "    }\n" +
            "    public final <I extends Object & Iterable<?> & Iterator<?>> String join(I parts) {\n" +
            "        return join((Iterator<?>) parts);\n" +
            "    }\n" +
            "    public final String join(Iterable<?> parts) { return join(parts.iterator()); }\n" +
            "    public final String join(Iterator<?> parts) { return appendTo(new StringBuilder(), parts).toString(); }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Joiner", Collections.emptyMap());
        assertTrue(source.contains("I extends Object & Iterable<?> & Iterator<?>"), "explicit 'Object' bound");
        assertTrue(source.contains("appendTo((Appendable)builder, parts);"), "cast to the erasure");
        assertTrue(source.contains("return (StringBuilder)appendTo((Appendable)builder, parts);"), "cast of the erased returned type");
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Joiner", source)));
    }
}
