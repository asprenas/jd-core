package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** String concatenation built on an empty StringBuilder that starts with two non-String operands (A15) */
public class JavaCharStringConcatTest {
    @Test
    public void charOperandsStayString() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Concat",
            "package p;\n" +
            "public class Concat {\n" +
            "    Object row, column, value;\n" +
            "    public String a() { return new StringBuilder().append('{').append(row).append(\"={\").append(column).append('=').append(value).append(\"}}\").toString(); }\n" +
            "    public static String b(char c1, int i) { return new StringBuilder().append(c1).append(i).toString(); }\n" +
            "    public static String c(int i) { return new StringBuilder().append(i).toString(); }\n" +
            "    public static String d(char c1, String s) { return new StringBuilder().append(c1).append(s).toString(); }\n" +
            "    public static String e(char key, String value) { return '{' + key + \"=\" + value; }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Concat", Collections.emptyMap());
        assertTrue(source.contains("return \"\" + '{' + this.row + \"={\" + this.column + '=' + this.value + \"}}\";"), source);
        assertTrue(source.contains("return \"\" + c1 + i;"), source);
        assertTrue(source.contains("return \"\" + i;"), source);
        assertTrue(source.contains("return c1 + s;"), source);
        assertTrue(source.contains("return (123 + key) + \"=\" + value;"), source);
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Concat", source)), source);
    }
}
