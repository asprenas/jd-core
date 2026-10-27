package org.jd.core.v1;

import org.jd.core.v1.loader.ClassPathLoader;
import org.jd.core.v1.printer.PlainTextPrinter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** No explicit type argument for a generic method invoked with an argument typed by captures (guava ImmutableTable) */
public class JavaCaptureTypeArgumentTest extends Java9InterfaceTest {
    @Test
    public void noExplicitTypeArgumentForCapturedArgument() throws Exception {
        // 'Table<? extends R, ? extends C, ? extends V> table': 'table.cellSet()' is typed by captures
        String source = decompile(new ClassPathLoader(), new PlainTextPrinter(), "com/google/common/collect/ImmutableTable");

        assertTrue(source.contains("onlyCell = Iterables.getOnlyElement(table.cellSet());"), "no explicit type argument");
    }
}
