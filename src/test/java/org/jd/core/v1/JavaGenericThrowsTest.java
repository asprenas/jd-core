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

/** Call of a generic method that throws a type variable (A6b) */
public class JavaGenericThrowsTest {
    @Test
    public void typeVariableOfTheCallerAsArgument() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Timeout",
            "package p;\n" +
            "import java.util.concurrent.Future;\n" +
            "import java.util.concurrent.atomic.AtomicReference;\n" +
            "import java.util.function.Supplier;\n" +
            "public class Timeout {\n" +
            "    public interface Factory<E extends Throwable> { E create(); }\n" +
            "    static <T, E extends Throwable> T run(Supplier<T> supplier, Factory<E> factory) throws E {\n" +
            "        AtomicReference<Thread> threadReference = new AtomicReference<>();\n" +
            "        try {\n" +
            "            Future<T> future = submit(supplier);\n" +
            "            return resolve(future, threadReference::get, factory);\n" +
            "        } finally {\n" +
            "            threadReference.set(null);\n" +
            "        }\n" +
            "    }\n" +
            "    static <T, E extends Throwable> void probe(Future<T> future, Factory<E> factory) throws E {\n" +
            "        Object o = resolve(future, null, factory);\n" +
            "        System.out.println(o);\n" +
            "    }\n" +
            "    private static <T> Future<T> submit(Supplier<T> supplier) { return null; }\n" +
            "    private static <T, E extends Throwable> T resolve(Future<T> future, Supplier<Thread> threadSupplier, Factory<E> factory) throws E {\n" +
            "        if (future.isDone()) { throw factory.create(); }\n" +
            "        return null;\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Timeout", Collections.emptyMap());
        assertFalse(source.contains("(Future)"), "no raw cast");
        assertFalse(source.contains("requireNonNull"), "no null check of the method reference receiver");
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Timeout", source)));
    }
}
