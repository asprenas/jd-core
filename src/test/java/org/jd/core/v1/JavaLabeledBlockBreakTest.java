package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 'break label;' out of a labeled block, whose target is also reached from other blocks (A11, joda-time 'DateTimeFormatterBuilder$TimeZoneOffset.parseInto') */
public class JavaLabeledBlockBreakTest {
    @Test
    public void breakFromOrConditionToSharedStatements() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    public int a(StringBuilder b, CharSequence text, int position, String z) {\n" +
            "        label:\n" +
            "        if (z != null) {\n" +
            "            if (z.length() == 0) {\n" +
            "                if (position > 0) {\n" +
            "                    char c = text.charAt(position);\n" +
            "                    if (c == '-' || c == '+') {\n" +
            "                        break label;\n" +
            "                    }\n" +
            "                }\n" +
            "                return 1;\n" +
            "            }\n" +
            "        }\n" +
            "        char c = text.charAt(position);\n" +
            "        if (c == '-') {\n" +
            "            b.append(2);\n" +
            "        } else {\n" +
            "            return 3;\n" +
            "        }\n" +
            "        return 4;\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("if (c1 != '-' && c1 != '+')"), source);
    }

    @Test
    public void breakFromElseToSharedTernaryReturn() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    public int a(CharSequence text, int position, boolean negative) {\n" +
            "        int offset = 0;\n" +
            "        parse: {\n" +
            "            boolean expectSeparators;\n" +
            "            char c = text.charAt(position);\n" +
            "            if (c == ':') {\n" +
            "                expectSeparators = true;\n" +
            "            } else if (c == '0') {\n" +
            "                expectSeparators = false;\n" +
            "            } else {\n" +
            "                break parse;\n" +
            "            }\n" +
            "            if (!expectSeparators) {\n" +
            "                break parse;\n" +
            "            }\n" +
            "            offset = text.length();\n" +
            "        }\n" +
            "        return negative ? -offset : offset;\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("return negative ? -offset : offset;"), source);
    }

    @Test
    public void breakFromNestedIfToSharedTernaryReturn() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    public int a(CharSequence text, int position, boolean negative, boolean expect) {\n" +
            "        int offset = 0;\n" +
            "        parse: {\n" +
            "            if (expect) {\n" +
            "                if (text.charAt(position) != ':') {\n" +
            "                    break parse;\n" +
            "                }\n" +
            "                position++;\n" +
            "            }\n" +
            "            offset = text.length();\n" +
            "        }\n" +
            "        return negative ? -offset : offset;\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("if (text.charAt(position) != ':')"), source);
    }

    protected static String decompile(String javaSource) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/S", javaSource);

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/S", Collections.emptyMap());
        assertTrue(source.indexOf("// Byte code:") == -1, source);
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
        return source;
    }
}
