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

/** Loops of commons-imaging 'DhtSegment.HuffmanTable' */
public class JavaLoopConditionStatementTest {
    @Test
    public void statementBeforeTheTestOfTheLoop() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    int[] maxCode = new int[17], valPtr = new int[17];\n" +
            "    void m(int[] bits) {\n" +
            "        int i = 0;\n" +
            "        int j = 0;\n" +
            "        while (true) {\n" +
            "            i++;\n" +
            "            if (i > 16) {\n" +
            "                break;\n" +
            "            }\n" +
            "            if (bits[i] == 0) {\n" +
            "                maxCode[i] = -1;\n" +
            "            } else {\n" +
            "                valPtr[i] = j;\n" +
            "                j++;\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        // 'i++' runs at each iteration, not once before 'while (i <= 16)'
        assertFalse(source.contains("while (i <= 16)"), source);
        int loop = source.indexOf("while (true)");
        assertTrue((loop != -1) && (source.indexOf("i++;", loop) != -1), source);
    }

    @Test
    public void exitInTheNextBranchOfTheLoopHeader() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    int[] huffSize = new int[100];\n" +
            "    int m(int[] bits) {\n" +
            "        int k = 0;\n" +
            "        int i = 1;\n" +
            "        int j = 1;\n" +
            "        int lastK = -1;\n" +
            "        while (true) {\n" +
            "            if (j > bits[i]) {\n" +
            "                i++;\n" +
            "                j = 1;\n" +
            "                if (i > 16) {\n" +
            "                    huffSize[k] = 0;\n" +
            "                    lastK = k;\n" +
            "                    break;\n" +
            "                }\n" +
            "            } else {\n" +
            "                huffSize[k] = i;\n" +
            "                k++;\n" +
            "                j++;\n" +
            "            }\n" +
            "        }\n" +
            "        System.out.println(\"after\");\n" +
            "        return lastK;\n" +
            "    }\n" +
            "}\n");

        // The 'else' part is the inner loop, not code after 'return'
        assertTrue(source.contains("while (j <= bits[i])"), source);
    }

    protected static String decompile(String javaSource) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/S", javaSource);

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/S", Collections.singletonMap("realignLineNumbers", Boolean.TRUE));
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
        return source;
    }
}
