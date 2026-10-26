package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** No raw cast of the arguments of generic methods (A4, guava 'Files.readBytes', 'Iterables.get') */
public class JavaGenericArgumentCastTest {
    @Test
    public void noRawCastOfArguments() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/S",
            "package p;\n" +
            "import java.io.*;\n" +
            "public class S {\n" +
            "    interface Supplier<T> { T get(); }\n" +
            "    interface Processor<T> { T result(); }\n" +
            "    static Supplier<FileInputStream> newSupplier(File f) { return null; }\n" +
            "    static <T> T readBytes(Supplier<? extends InputStream> supplier, Processor<T> processor) { return processor.result(); }\n" +
            "    public static <T> T read(File file, Processor<T> processor) {\n" +
            "        return readBytes(newSupplier(file), processor);\n" +
            "    }\n" +
            "    static <T> T get(Iterable<T> iterable, int position) { return null; }\n" +
            "    public static <T> T get(Iterable<? extends T> iterable, int position, T defaultValue) {\n" +
            "        try {\n" +
            "            return get(iterable, position);\n" +
            "        } catch (IndexOutOfBoundsException e) {\n" +
            "            return defaultValue;\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/S", Collections.emptyMap());
        // 'Supplier<FileInputStream>' fits 'Supplier<? extends InputStream>'
        assertTrue(source.contains("return readBytes(newSupplier(file), processor);"), source);
        // 'T' of the invoked method is inferred from 'Iterable<? extends T>'
        assertTrue(source.contains("return get(iterable, position);"), source);
        assertTrue(CompilerUtil.compile("1.8", new JavaSourceFileObject("p/S", source)), source);
    }
}
