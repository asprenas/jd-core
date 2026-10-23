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

/** Two types with the same simple name, one in the same package (A10) */
public class JavaSameSimpleNameTest {
    @Test
    public void samePackageAndOtherPackage() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/MediaType",
            "package p;\n" +
            "public class MediaType { public static MediaType parse(String s) { return new MediaType(); } }\n");
        sources.put("p/ext/MediaType",
            "package p.ext;\n" +
            "public class MediaType { }\n");
        sources.put("p/Reporter",
            "package p;\n" +
            "public interface Reporter {\n" +
            "    default void publish(String file, p.ext.MediaType mediaType) { publish(file, MediaType.parse(mediaType.toString())); }\n" +
            "    default void publish(String file, MediaType mediaType) { }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Reporter", Collections.emptyMap());
        assertTrue(source.contains("p.ext.MediaType mediaType"));
        assertFalse(source.contains("import p.ext.MediaType;"));
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/MediaType", sources.get("p/MediaType")),
            new JavaSourceFileObject("p/ext/MediaType", sources.get("p/ext/MediaType")),
            new JavaSourceFileObject("p/Reporter", source)));
    }
}
