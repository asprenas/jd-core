package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Java 25 flexible constructor bodies: captured variables used before 'super(...)' (remaining item B3) */
public class JavaFlexibleConstructorCaptureTest {
    @Test
    public void localClassCapture() throws Exception {
        assumeTrue(InMemorySample.runtimeFeatureVersion() >= 25, "flexible constructor bodies need a JDK 25+");

        String source =
            "package p;\n" +
            "public class Capture {\n" +
            "    static class Base {\n" +
            "        final String name;\n" +
            "        Base(String name) { this.name = name; }\n" +
            "    }\n" +
            "    public String make(String s, int n) {\n" +
            "        class Local extends Base {\n" +
            "            final int size;\n" +
            "            Local(String suffix) {\n" +
            "                String t = s.trim() + suffix;\n" +
            "                if (t.isEmpty()) {\n" +
            "                    throw new IllegalArgumentException();\n" +
            "                }\n" +
            "                super(t);\n" +
            "                this.size = n + t.length();\n" +
            "            }\n" +
            "        }\n" +
            "        Local local = new Local(\"!\");\n" +
            "        return local.name + local.size;\n" +
            "    }\n" +
            "}\n";

        InMemorySample sample = new InMemorySample(Collections.singletonMap("p/Capture", source), "-g", "--release", "25");
        String decompiled = sample.decompile("p/Capture", Collections.emptyMap());

        assertTrue(!decompiled.contains("// Byte code:"), "byte code fallback");
        assertTrue(decompiled.contains("String t = s.trim() + suffix;"), "captured variable before 'super'");
        assertTrue(CompilerUtil.compile("25", new JavaSourceFileObject("p/Capture", decompiled)), "the decompiled source does not recompile");
    }
}
