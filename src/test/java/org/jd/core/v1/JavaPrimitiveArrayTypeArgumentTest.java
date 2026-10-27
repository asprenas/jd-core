package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Type variable bound to an array of primitive type (guava ByteStreams) */
public class JavaPrimitiveArrayTypeArgumentTest {
    @Test
    public void typeVariableBoundToPrimitiveArray() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Processor",
            "package p;\n" +
            "public interface Processor<T> {\n" +
            "    T getResult();\n" +
            "}\n");
        sources.put("p/Streams",
            "package p;\n" +
            "public final class Streams {\n" +
            "    static <T> T read(Processor<T> processor) { return processor.getResult(); }\n" +
            "    public static byte[] digest() {\n" +
            "        return read(new Processor<byte[]>() {\n" +
            "            public byte[] getResult() { return new byte[0]; }\n" +
            "        });\n" +
            "    }\n" +
            "}\n");

        InMemorySample sample = new InMemorySample(sources, "-g", "--release", "8", "-Xlint:-options");

        String source = sample.decompile("p/Streams", Collections.emptyMap());
        assertTrue(source.contains("return read(new Processor<byte[]>() {"), "no raw cast of the anonymous class");
        assertTrue(CompilerUtil.compile("1.8",
            new JavaSourceFileObject("p/Processor", sources.get("p/Processor")),
            new JavaSourceFileObject("p/Streams", source)));
    }
}
