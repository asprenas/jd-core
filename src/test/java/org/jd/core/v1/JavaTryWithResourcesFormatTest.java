package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Try-with-resources output format (remaining item D6) */
public class JavaTryWithResourcesFormatTest {
    @Test
    public void format() throws Exception {
        String source =
            "package p;\n" +
            "import java.io.*;\n" +
            "public class Resources {\n" +
            "    public void two(InputStream in, OutputStream out) throws IOException {\n" +
            "        try (in; out) {\n" +
            "            out.write(in.read());\n" +
            "        }\n" +
            "    }\n" +
            "    public int loop(String[] values) throws IOException {\n" +
            "        int sum = 0;\n" +
            "        for (int i = 0; i < values.length; i++) {\n" +
            "            try (Reader r = new StringReader(values[i])) {\n" +
            "                if (i == 0) {\n" +
            "                    continue;\n" +
            "                }\n" +
            "                sum += r.read();\n" +
            "            }\n" +
            "        }\n" +
            "        return sum;\n" +
            "    }\n" +
            "}\n";

        InMemorySample sample = new InMemorySample(Collections.singletonMap("p/Resources", source), "-g", "--release", "11");
        String decompiled = sample.decompile("p/Resources", Collections.emptyMap());

        assertTrue(decompiled.contains("try (in; out) {"), "space after 'try'");
        assertFalse(decompiled.matches("(?s).*\\{\\s*(/\\*[ 0-9:]+\\*/\\s*)*} else.*"), "empty 'if' block");
        assertTrue(decompiled.contains("if (i != 0)"), "negated condition");
        assertTrue(CompilerUtil.compile("11", new JavaSourceFileObject("p/Resources", decompiled)), "the decompiled source does not recompile");
    }
}
