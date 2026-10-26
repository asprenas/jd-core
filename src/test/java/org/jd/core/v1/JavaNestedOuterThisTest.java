package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outer instance of a nested anonymous or inner class (A12) */
public class JavaNestedOuterThisTest {
    @Test
    public void anonymousInAnonymous() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Service",
            "package p;\n" +
            "public abstract class Service {\n" +
            "    protected abstract void doStart();\n" +
            "    protected final void notifyStarted() { }\n" +
            "    protected int count;\n" +
            "}\n");
        sources.put("p/Idle",
            "package p;\n" +
            "public abstract class Idle {\n" +
            "    abstract void startUp();\n" +
            "    final Service delegate = new Service() {\n" +
            "        protected void doStart() {\n" +
            "            new Thread(new Runnable() {\n" +
            "                public void run() {\n" +
            "                    startUp();\n" +
            "                    notifyStarted();\n" +
            "                    count++;\n" +
            "                }\n" +
            "            }).start();\n" +
            "        }\n" +
            "    };\n" +
            "}\n");

        for (String release : new String[] { "8", "11" }) {
            InMemorySample sample = new InMemorySample(sources, "-g", "--release", release, "-Xlint:-options");
            String source = sample.decompile("p/Idle", Collections.emptyMap());
            assertTrue(source.contains("notifyStarted();") && !source.contains(".null.this") && !source.contains("this$"), source);
            assertTrue(CompilerUtil.compile("1.8",
                new JavaSourceFileObject("p/Service", sources.get("p/Service")),
                new JavaSourceFileObject("p/Idle", source)), source);
        }
    }

    @Test
    public void innerInInnerConstructor() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Multi",
            "package p;\n" +
            "import java.util.*;\n" +
            "public class Multi<V> {\n" +
            "    Iterator<V> iteratorOf(Collection<V> c) { return c.iterator(); }\n" +
            "    class Wrapped {\n" +
            "        Collection<V> delegate;\n" +
            "        List<V> list() { return (List<V>) delegate; }\n" +
            "        class WrappedIterator {\n" +
            "            final Iterator<V> it;\n" +
            "            WrappedIterator() { it = iteratorOf(delegate); }\n" +
            "            WrappedIterator(Iterator<V> it) { this.it = it; }\n" +
            "        }\n" +
            "        class WrappedListIterator extends WrappedIterator {\n" +
            "            WrappedListIterator(int index) { super(list().listIterator(index)); }\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        for (String release : new String[] { "8", "11" }) {
            InMemorySample sample = new InMemorySample(sources, "-g", "--release", release, "-Xlint:-options");
            String source = sample.decompile("p/Multi", Collections.emptyMap());
            assertTrue(!source.contains(".null.this") && !source.contains("this$"), source);
            assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Multi", source)), source);
        }
    }

    @Test
    public void anonymousInSuperArgumentsOfInner() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Transformed",
            "package p;\n" +
            "import java.util.*;\n" +
            "import java.util.function.Function;\n" +
            "public class Transformed {\n" +
            "    List<String> from = new ArrayList<>();\n" +
            "    static class Base {\n" +
            "        Base(List<String> list, Function<String, Integer> function) { }\n" +
            "    }\n" +
            "    class Entries extends Base {\n" +
            "        Entries(final int offset) {\n" +
            "            super(from, new Function<String, Integer>() {\n" +
            "                public Integer apply(String s) { return s.length() + offset + from.size(); }\n" +
            "            });\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        for (String release : new String[] { "8", "11" }) {
            InMemorySample sample = new InMemorySample(sources, "-g", "--release", release, "-Xlint:-options");
            String source = sample.decompile("p/Transformed", Collections.emptyMap());
            assertTrue(source.contains("new Function<String, Integer>() {") && !source.contains(".null.this") && !source.contains("this$"), source);
            assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/Transformed", source)), source);
        }
    }
}
