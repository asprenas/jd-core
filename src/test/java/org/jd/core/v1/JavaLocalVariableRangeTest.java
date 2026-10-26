package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local variables with several 'LocalVariableTable' ranges, and 'for' init variables (commons-imaging) */
public class JavaLocalVariableRangeTest {
    /** PnmImageParser: the next range of 'height' (after the 'try') is not the range of 'seenWidth' */
    @Test
    public void variableRangeSplitByTryCatch() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    int m(int a, String s, java.io.BufferedReader r) throws Exception {\n" +
            "        if (a == 1) {\n" +
            "            final int width = Integer.parseInt(s);\n" +
            "            final int height;\n" +
            "            try {\n" +
            "                height = Integer.parseInt(s);\n" +
            "            } catch (final NumberFormatException e) {\n" +
            "                throw new IllegalStateException(e);\n" +
            "            }\n" +
            "            return width + height;\n" +
            "        } else if (a == 2) {\n" +
            "            int width = -1;\n" +
            "            boolean seenWidth = false;\n" +
            "            int height = -1;\n" +
            "            String line;\n" +
            "            while ((line = r.readLine()) != null) {\n" +
            "                if (\"W\".equals(line)) {\n" +
            "                    seenWidth = true;\n" +
            "                    width = 1;\n" +
            "                } else if (\"H\".equals(line)) {\n" +
            "                    height = 2;\n" +
            "                } else {\n" +
            "                    break;\n" +
            "                }\n" +
            "            }\n" +
            "            if (!seenWidth) {\n" +
            "                throw new IllegalStateException(\"w\");\n" +
            "            }\n" +
            "            return width + height;\n" +
            "        }\n" +
            "        return 0;\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("if (!seenWidth)"), source);
    }

    /** BmpImageParser: one 'paletteLength' variable for the assignments of all the cases */
    @Test
    public void variableAssignedInSwitchCases() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    int m(int compression, int bitsPerPixel, int colorTableSize) {\n" +
            "        final int paletteLength;\n" +
            "        int rle = 0;\n" +
            "        switch (compression) {\n" +
            "        case 0:\n" +
            "            if (bitsPerPixel <= 8) {\n" +
            "                paletteLength = 4 * colorTableSize;\n" +
            "            } else {\n" +
            "                paletteLength = 0;\n" +
            "            }\n" +
            "            break;\n" +
            "        case 1:\n" +
            "            paletteLength = 4 * colorTableSize;\n" +
            "            rle = 1;\n" +
            "            break;\n" +
            "        default:\n" +
            "            throw new IllegalStateException(\"x\");\n" +
            "        }\n" +
            "        if (paletteLength > 0) {\n" +
            "            rle++;\n" +
            "        }\n" +
            "        return paletteLength + rle;\n" +
            "    }\n" +
            "}\n");

        // Not a new variable in the 'if' statement
        assertTrue(source.contains("paletteLength = 4 * colorTableSize;"), source);
        assertTrue(!source.contains("int i = 4"), source);
    }

    /** T4AndT6Compression: 'rowLength', initialized by a 'for' statement in a 'try', is used after the 'try' */
    @Test
    public void forInitVariableUsedAfterTryCatch() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    int read(int c) { return c + 1; }\n" +
            "    void m(int width, int height) {\n" +
            "        for (int y = 0; y < height; y++) {\n" +
            "            int rowLength;\n" +
            "            try {\n" +
            "                int color = 0;\n" +
            "                for (rowLength = 0; rowLength < width;) {\n" +
            "                    final int runLength = read(color);\n" +
            "                    color = 1 - color;\n" +
            "                    rowLength += runLength;\n" +
            "                }\n" +
            "            } catch (final IllegalStateException e) {\n" +
            "                throw new RuntimeException(\"x\", e);\n" +
            "            }\n" +
            "            if (rowLength == width) {\n" +
            "                System.out.println(\"ok\");\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("for (rowLength = 0; rowLength < width; )"), source);
    }

    /** Unchanged: the loops initializing 'i' get their own variables */
    @Test
    public void forInitVariableReusedByLoops() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    int m(String s, String t, int[] p) {\n" +
            "        int n = s.length(), m = t.length();\n" +
            "        int i;\n" +
            "        for (i = 0; i <= n; i++) { p[i] = i; }\n" +
            "        for (int j = 1; j <= m; j++) {\n" +
            "            for (i = 1; i <= n; i++) { p[i] = p[i - 1] + j; }\n" +
            "        }\n" +
            "        for (i = 2; i <= n; i++) { p[i] = i; }\n" +
            "        return p[n];\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("for (int i = 0; i <= n; )"), source);
    }

    protected static String decompile(String javaSource) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/S", javaSource);

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/S", Collections.emptyMap());
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
        return source;
    }
}
