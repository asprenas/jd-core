package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 'localVariableTypeInference' configuration with method invocations (remaining item B4) */
public class JavaVarTest {
    @Test
    public void methodInvocations() throws Exception {
        String source =
            "package p;\n" +
            "import java.util.*;\n" +
            "public class Calls {\n" +
            "    public int calls(List<String> list, Map<String, Integer> map) {\n" +
            "        String first = list.get(0);\n" +
            "        int length = first.length();\n" +
            "        Integer value = map.get(first);\n" +
            "        List<String> empty = Collections.emptyList();\n" +
            "        Object o = list.get(1);\n" +
            "        return length + value + empty.size() + o.hashCode();\n" +
            "    }\n" +
            "}\n";

        InMemorySample sample = new InMemorySample(Collections.singletonMap("p/Calls", source), "-g", "--release", "11");
        String decompiled = sample.decompile("p/Calls", Collections.singletonMap("localVariableTypeInference", "true"));

        assertTrue(decompiled.contains("var first = list.get(0);"), "method of a generic class");
        assertTrue(decompiled.contains("var length = first.length();"), "non-generic method");
        assertTrue(decompiled.contains("var value = map.get(first);"), "method of a generic class");
        assertTrue(decompiled.contains("List<String> empty = Collections.emptyList();"), "generic method: inferred from the target type");
        assertTrue(decompiled.contains("Object o = list.get(1);"), "other type than the declared type");
        assertTrue(CompilerUtil.compile("11", new JavaSourceFileObject("p/Calls", decompiled)), "the decompiled source does not recompile");
    }
}
