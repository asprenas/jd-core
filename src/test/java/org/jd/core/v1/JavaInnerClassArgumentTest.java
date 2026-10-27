package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.jd.core.v1.loader.ClassPathLoader;
import org.jd.core.v1.printer.PlainTextPrinter;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outer instance arguments of inner classes and members of raw types (guava ImmutableMap, RegularImmutableMap) */
public class JavaInnerClassArgumentTest {
    @Test
    public void superOfStaticNestedTypeAndInheritedInnerType() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Base",
            "package p;\n" +
            "public abstract class Base {\n" +
            "    static abstract class Transformed {\n" +
            "        final Base source;\n" +
            "        Transformed(Base source) { this.source = source; }\n" +
            "        Transformed(Base source, int hashCode) { this.source = source; }\n" +
            "    }\n" +
            "    abstract Base entries();\n" +
            "    class KeySet extends Transformed {\n" +
            "        KeySet() { super(entries()); }\n" +
            "        KeySet(int hashCode) { super(entries(), hashCode); }\n" +
            "    }\n" +
            "    Object createKeySet() { return new KeySet(); }\n" +
            "}\n");
        sources.put("p/Regular",
            "package p;\n" +
            "public class Regular extends Base {\n" +
            "    int keySetHashCode;\n" +
            "    Base entries() { return this; }\n" +
            "    Object createKeySet() { return new KeySet(keySetHashCode); }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String base = sample.decompile("p/Base", Collections.emptyMap());
        assertTrue(base.contains("super(Base.this.entries());"), "super argument of the static nested super type");
        assertTrue(base.contains("super(Base.this.entries(), hashCode);"), "super arguments of the static nested super type");

        String regular = sample.decompile("p/Regular", Collections.emptyMap());
        assertTrue(regular.contains("KeySet(this.keySetHashCode)"), "implicit outer instance");
        assertTrue(!regular.contains("KeySet(this,"), "no explicit outer instance");
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/Base", base),
            new JavaSourceFileObject("p/Regular", regular)));
    }

    @Test
    public void memberOfRawTypeWithContextualTypeVariableName() throws Exception {
        // guava 12.0 without local variable table: 'Map.Entry[] arrayOfEntry' in '<K, V> copyOf(Map<? extends K, ? extends V> map)'
        String source = new Java9InterfaceTest().decompile(new ClassPathLoader(), new PlainTextPrinter(), "com/google/common/collect/ImmutableMap");

        assertTrue(source.contains("K k = (K)arrayOfEntry[i].getKey();"), "cast of the erased member of the raw type");
        assertTrue(source.contains("V v = (V)arrayOfEntry[i].getValue();"), "cast of the erased member of the raw type");
    }
}
