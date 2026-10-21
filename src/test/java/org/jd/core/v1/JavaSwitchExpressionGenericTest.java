package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generic values and generic method invocations in switch expressions (remaining item B2) */
public class JavaSwitchExpressionGenericTest {
    @Test
    public void genericValues() throws Exception {
        String source =
            "package p;\n" +
            "import java.util.*;\n" +
            "public class Gen {\n" +
            "    public static <T> List<T> pick(int k, List<T> a, List<T> b) {\n" +
            "        return switch (k) { case 0 -> a; case 1 -> Collections.emptyList(); default -> b; };\n" +
            "    }\n" +
            "    public String first(int k, List<String> l, Map<String, Integer> m) {\n" +
            "        String s = switch (k) {\n" +
            "            case 0 -> l.get(0);\n" +
            "            case 1 -> m.keySet().iterator().next();\n" +
            "            default -> Optional.ofNullable(l.isEmpty() ? null : l.get(l.size() - 1)).orElse(\"none\");\n" +
            "        };\n" +
            "        return s.trim();\n" +
            "    }\n" +
            "    public List<String> list(int k) {\n" +
            "        List<String> r = switch (k) { case 0 -> new ArrayList<>(); case 1 -> List.of(\"a\"); default -> Collections.emptyList(); };\n" +
            "        return r;\n" +
            "    }\n" +
            "    public int sum(int k, Map<String, List<Integer>> m) {\n" +
            "        return switch (k) { case 0 -> m.values().stream().mapToInt(List::size).sum(); default -> m.getOrDefault(\"x\", List.of()).size(); };\n" +
            "    }\n" +
            "}\n";

        InMemorySample sample = new InMemorySample(Collections.singletonMap("p/Gen", source), "-g", "--release", "17");
        String decompiled = sample.decompile("p/Gen", Collections.emptyMap());

        assertTrue(!decompiled.contains("// Byte code:"), "byte code fallback");
        // Before: '(String)l.get(0)', 'Optional.ofNullable((T)...).orElse((T)"none")', 'List.of((E)"a")'
        assertTrue(decompiled.contains("case 0 -> l.get(0);"), "bound 'yield' value");
        assertTrue(decompiled.contains("case 1 -> List.of(\"a\");"), "bound generic method");
        assertTrue(decompiled.contains(".orElse(\"none\")"), "bound 'yield' value");
        assertTrue(decompiled.contains("case 0 -> new ArrayList<>();"), "diamond");
        assertTrue(CompilerUtil.compile("17", new JavaSourceFileObject("p/Gen", decompiled)), "the decompiled source does not recompile");
    }
}
