package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Java 21 class: type patterns whose binding is not used (remaining item D9). javac 21 stores the binding even
 * when it is not used, so the patterns are restored with a name (with or without debug information).
 */
public class JavaTypePatternJava21Test {
    @Test
    public void unusedBindings() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "pattern switch needs a JDK 21+");

        String source =
            "package p;\n" +
            "public class Patterns {\n" +
            "    public String unused(Object o) {\n" +
            "        return switch (o) {\n" +
            "            case Integer i -> \"int\";\n" +
            "            case String s -> \"str\";\n" +
            "            default -> \"other\";\n" +
            "        };\n" +
            "    }\n" +
            "    public String statement(Object o) {\n" +
            "        String r = \"?\";\n" +
            "        switch (o) {\n" +
            "            case Integer i: r = \"int\"; break;\n" +
            "            case String s: r = \"str\"; break;\n" +
            "            default: break;\n" +
            "        }\n" +
            "        return r;\n" +
            "    }\n" +
            "}\n";

        for (String debug : new String[] { "-g", "-g:none" }) {
            InMemorySample sample = new InMemorySample(Collections.singletonMap("p/Patterns", source), debug, "--release", "21");
            String decompiled = sample.decompile("p/Patterns", Collections.emptyMap());

            assertFalse(decompiled.contains("typeSwitch"), "index switch kept (" + debug + ")");
            assertTrue(CompilerUtil.compile("21", new JavaSourceFileObject("p/Patterns", decompiled)), "the decompiled source does not recompile (" + debug + ")");
        }
    }
}
