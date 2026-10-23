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

/** Call of a default method of a super interface: 'X.super.m()' (A5) */
public class JavaInterfaceSuperCallTest {
    @Test
    public void superInterfaceDefaultMethod() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Base",
            "package p;\n" +
            "public interface Base {\n" +
            "    default boolean isAnnotated(String name) { return name.isEmpty(); }\n" +
            "}\n");
        sources.put("p/Child",
            "package p;\n" +
            "public interface Child extends Base {\n" +
            "    @Override default boolean isAnnotated(String name) { return Base.super.isAnnotated(name); }\n" +
            "}\n");
        sources.put("p/Impl",
            "package p;\n" +
            "public class Impl implements Base {\n" +
            "    @Override public boolean isAnnotated(String name) { return Base.super.isAnnotated(name) || name.length() > 1; }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String child = sample.decompile("p/Child", Collections.emptyMap());
        assertTrue(child.contains("return Base.super.isAnnotated(name);"));
        String impl = sample.decompile("p/Impl", Collections.emptyMap());
        assertTrue(impl.contains("Base.super.isAnnotated(name)"));
        assertFalse(impl.contains(" super.isAnnotated"));

        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/Base", sources.get("p/Base")),
            new JavaSourceFileObject("p/Child", child),
            new JavaSourceFileObject("p/Impl", impl)));
    }
}
