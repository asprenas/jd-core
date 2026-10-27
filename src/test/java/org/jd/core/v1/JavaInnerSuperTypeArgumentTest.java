package org.jd.core.v1;

import org.jd.core.v1.loader.ClassPathLoader;
import org.jd.core.v1.printer.PlainTextPrinter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-static inner super type of a class without 'Signature' attribute (guava 12.0, old javac): the outer type takes the
 * type arguments of the super type of the enclosing class, a raw super type would erase the inherited methods (name clash)
 */
public class JavaInnerSuperTypeArgumentTest extends Java9InterfaceTest {
    @Test
    public void innerSuperTypeOfGenericSuperClass() throws Exception {
        String source = decompile(new ClassPathLoader(), new PlainTextPrinter(), "com/google/common/collect/ConcurrentHashMultiset");

        assertTrue(source.contains("private class EntrySet extends AbstractMultiset<E>.EntrySet"), "outer type arguments of the super type");
    }

    @Test
    public void innerSuperTypeOfGenericSuperClassOfOuterClass() throws Exception {
        String source = decompile(new ClassPathLoader(), new PlainTextPrinter(), "com/google/common/collect/Multimaps");

        assertTrue(source.contains("class EntrySet extends Multimaps.Keys<K, V>.KeysEntrySet"), "outer type arguments of the super type of the outer class");
    }
}
