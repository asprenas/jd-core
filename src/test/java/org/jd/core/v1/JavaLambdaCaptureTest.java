package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captured variables and parameters of an instance lambda (A2) */
public class JavaLambdaCaptureTest {
    @Test
    public void capturedVariableAndParameter() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Condition",
            "package p;\n" +
            "import java.util.Optional;\n" +
            "import java.util.function.Function;\n" +
            "public class Condition {\n" +
            "    private final Function<String, String> methodName = s -> s;\n" +
            "    public Optional<Integer> evaluate(Object context, Optional<String> annotation) {\n" +
            "        return annotation\n" +
            "            .map(this.methodName)\n" +
            "            .map(methodName -> getConditionMethod(methodName, context))\n" +
            "            .map(method -> method.length() + annotation.get().length());\n" +
            "    }\n" +
            "    String getConditionMethod(String name, Object context) { return name + context; }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Condition", Collections.emptyMap());
        assertTrue(source.contains("getConditionMethod(methodName, context)"));
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Condition", source)));
    }
}
