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

/** Type annotations of the types written with their package name ('java.sql.@A Date') */
public class JavaTypeAnnotationTest {
    @Test
    public void qualifiedTypeName() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/A",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface A {}\n");
        sources.put("p/Dates",
            "package p;\n" +
            "public class Dates {\n" +
            "    java.util.@A Date utilDate;\n" +
            "    java.sql.@A Date sqlDate;\n" +
            "    public java.sql.@A Date convert(java.util.@A Date d) { return new java.sql.Date(d.getTime()); }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");
        String source = sample.decompile("p/Dates", Collections.emptyMap());

        assertFalse(source.contains("@A java."), "an annotation before a package name");
        assertTrue(source.matches("(?s).*java\\.(util|sql)\\.@A Date.*"), "the annotation of the qualified type is before the simple name");
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/A", sources.get("p/A")),
            new JavaSourceFileObject("p/Dates", source)), "the decompiled source does not recompile");
    }
}
