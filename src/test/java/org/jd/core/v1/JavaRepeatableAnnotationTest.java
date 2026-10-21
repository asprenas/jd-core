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

/** 'repeatableAnnotations' configuration on the annotations of the parameters (Phase 5-5) */
public class JavaRepeatableAnnotationTest {
    @Test
    public void parameterAnnotations() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Tag",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) @Repeatable(Tags.class) public @interface Tag { String value(); }\n");
        sources.put("p/Tags",
            "package p;\n" +
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME) public @interface Tags { Tag[] value(); }\n");
        sources.put("p/Params",
            "package p;\n" +
            "public class Params {\n" +
            "    public String join(@Tag(\"a\") @Tag(\"b\") String s, @Tag(\"c\") int i) { return s + i; }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String folded = sample.decompile("p/Params", Collections.emptyMap());
        assertTrue(folded.contains("@Tags({@Tag(\"a\"), @Tag(\"b\")}) String"), "the default keeps the container");

        String unfolded = sample.decompile("p/Params", Collections.singletonMap("repeatableAnnotations", "true"));
        assertTrue(unfolded.contains("@Tag(\"a\") @Tag(\"b\") String"), "unfolded parameter annotations");
        assertTrue(unfolded.contains("@Tag(\"c\") int"));
        assertFalse(unfolded.contains("@Tags"));
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/Tag", sources.get("p/Tag")),
            new JavaSourceFileObject("p/Tags", sources.get("p/Tags")),
            new JavaSourceFileObject("p/Params", unfolded)));
    }
}
