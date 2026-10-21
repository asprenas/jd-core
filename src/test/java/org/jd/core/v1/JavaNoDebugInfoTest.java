package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classes compiled without debug information ('-g:none'): no LocalVariableTable, the types of the local
 * variables are inferred while the byte code is parsed. The samples of the other tests have debug information.
 */
public class JavaNoDebugInfoTest {
    @Test
    public void iincOnUndecidedVariable() throws Exception {
        // 'int s = 0': '0' may still be 'false' when 'iinc s 10' is parsed, the increment was printed 'true'
        InMemorySample sample = new InMemorySample(Collections.singletonMap("p/Iinc",
            "package p;\n" +
            "public class Iinc {\n" +
            "    public int add(int v) { int s = 0; s += 10; s -= 3; return s + v; }\n" +
            "    public int loop(int[] a) { int s = 0; for (int v : a) { switch (v) { case 0: continue; case 1: s += 10; break; default: s += v; } s++; } return s; }\n" +
            "}\n"), "-g:none", "--release", "8", "-Xlint:-options");
        String source = sample.decompile("p/Iinc", Collections.emptyMap());

        assertFalse(source.contains("true"), "the increment of 'iinc' is printed as a boolean");
        assertTrue(source.matches("(?s).*\\+= 10;.*-= 3;.*"));
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Iinc", source)));
    }
}
