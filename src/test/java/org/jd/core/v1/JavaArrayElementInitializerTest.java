package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** An array initializer stored into an array element (commons-imaging 'MyLzwDecompressor.initializeTable') */
public class JavaArrayElementInitializerTest {
    @Test
    public void arrayInitializerInArrayElement() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/S",
            "package p;\n" +
            "public class S {\n" +
            "    byte[][] table = new byte[10][];\n" +
            "    Object[] objects = new Object[3];\n" +
            "    void m(int n) {\n" +
            "        for (int i = 0; i < n; i++) {\n" +
            "            table[i] = new byte[] { (byte) i, };\n" +
            "        }\n" +
            "        objects[1] = new String[] { \"a\", \"b\" };\n" +
            "        int[][] a = new int[][] { { 1, 2 }, { 3 } };\n" +
            "        System.out.println(a.length);\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");
        String source = sample.decompile("p/S", Collections.emptyMap());

        assertTrue(source.contains("this.table[i] = new byte[] { (byte)i };"), source);
        assertTrue(source.contains("this.objects[1] = new String[] { \"a\", \"b\" };"), source);
        // Unchanged: the elements of an array initializer
        assertTrue(source.contains("int[][] a = { { 1, 2 }, { 3 } };"), source);
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
    }
}
