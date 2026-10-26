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

/** Loops around 'try' statements and infinite inner loops (A11b, guava 'Finalizer', 'AtomicLongMap', 'AbstractListeningExecutorService') */
public class JavaLoopControlFlowTest {
    @Test
    public void infiniteLoopAroundTryCatch() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    static class ShutDown extends Exception {}\n" +
            "    java.lang.ref.ReferenceQueue<Object> queue;\n" +
            "    void cleanUp(Object o) throws ShutDown {}\n" +
            "    public void run() {\n" +
            "        try {\n" +
            "            while (true) {\n" +
            "                try {\n" +
            "                    cleanUp(queue.remove());\n" +
            "                } catch (InterruptedException e) { }\n" +
            "            }\n" +
            "        } catch (ShutDown shutDown) { }\n" +
            "    }\n" +
            "}\n");

        assertFalse(source.contains("break;"), source);
        // One loop, around the inner 'try' statement
        assertTrue(source.indexOf("while (true)") == source.lastIndexOf("while (true)"), source);
    }

    @Test
    public void infiniteInnerLoopWithContinueOuter() throws Exception {
        String source = decompile(
            "package p;\n" +
            "import java.util.concurrent.ConcurrentHashMap;\n" +
            "import java.util.concurrent.atomic.AtomicLong;\n" +
            "public class S {\n" +
            "    ConcurrentHashMap<String, AtomicLong> map;\n" +
            "    public long addAndGet(String key, long delta) {\n" +
            "        outer: for (;;) {\n" +
            "            AtomicLong atomic = map.get(key);\n" +
            "            if (atomic == null) {\n" +
            "                atomic = map.putIfAbsent(key, new AtomicLong(delta));\n" +
            "                if (atomic == null) {\n" +
            "                    return delta;\n" +
            "                }\n" +
            "            }\n" +
            "            for (;;) {\n" +
            "                long oldValue = atomic.get();\n" +
            "                if (oldValue == 0L) {\n" +
            "                    if (map.replace(key, atomic, new AtomicLong(delta))) {\n" +
            "                        return delta;\n" +
            "                    }\n" +
            "                    continue outer;\n" +
            "                }\n" +
            "                long newValue = oldValue + delta;\n" +
            "                if (atomic.compareAndSet(oldValue, newValue)) {\n" +
            "                    return newValue;\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        assertFalse(source.contains("break;"), source);
    }

    @Test
    public void tryFinallyWithoutNormalCompletion() throws Exception {
        String source = decompile(
            "package p;\n" +
            "public class S {\n" +
            "    Object poll() { return null; }\n" +
            "    Object get(Object o) throws Exception { return o; }\n" +
            "    public Object a(int n) throws Exception {\n" +
            "        int active = 1;\n" +
            "        try {\n" +
            "            Exception ee = null;\n" +
            "            for (;;) {\n" +
            "                Object f = poll();\n" +
            "                if (f == null) {\n" +
            "                    if (n > 0) {\n" +
            "                        n--;\n" +
            "                    } else {\n" +
            "                        break;\n" +
            "                    }\n" +
            "                }\n" +
            "                if (f != null) {\n" +
            "                    --active;\n" +
            "                    try {\n" +
            "                        return get(f);\n" +
            "                    } catch (RuntimeException e) {\n" +
            "                        ee = e;\n" +
            "                    }\n" +
            "                }\n" +
            "            }\n" +
            "            throw ee;\n" +
            "        } finally {\n" +
            "            poll();\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        assertTrue(source.contains("throw ee;"), source);
        assertTrue(source.contains("return get(f);"), source);
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
