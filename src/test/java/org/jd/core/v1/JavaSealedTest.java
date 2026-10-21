package org.jd.core.v1;

import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.InMemorySample;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sealed hierarchies whose types are in several class files */
public class JavaSealedTest {
    @Test
    public void nonSealedSubtypeOfAnotherFile() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Shape", "package p;\npublic sealed interface Shape permits Circle, Square {}\n");
        sources.put("p/Circle", "package p;\npublic final class Circle implements Shape {}\n");
        sources.put("p/Square", "package p;\npublic non-sealed class Square implements Shape {}\n");
        sources.put("p/BigSquare", "package p;\npublic class BigSquare extends Square {}\n");
        sources.put("p/Vehicle", "package p;\npublic abstract sealed class Vehicle permits Truck {}\n");
        sources.put("p/Truck", "package p;\npublic non-sealed class Truck extends Vehicle {}\n");

        InMemorySample sample = new InMemorySample(sources, "--release", "17");
        List<JavaFileObjectPair> decompiled = new ArrayList<>();

        for (String internalName : sources.keySet()) {
            decompiled.add(new JavaFileObjectPair(internalName, sample.decompile(internalName, Collections.emptyMap())));
        }

        assertTrue(source(decompiled, "p/Square").contains("non-sealed class Square implements Shape"), "interface of another file");
        assertTrue(source(decompiled, "p/Truck").contains("non-sealed class Truck extends Vehicle"), "class of another file");
        assertFalse(source(decompiled, "p/BigSquare").contains("non-sealed"), "the super type is not sealed");
        assertTrue(source(decompiled, "p/Shape").contains("sealed interface Shape permits Circle, Square"));

        JavaSourceFileObject[] files = new JavaSourceFileObject[decompiled.size()];
        for (int i = 0; i < files.length; i++) {
            files[i] = new JavaSourceFileObject(decompiled.get(i).internalName, decompiled.get(i).source);
        }
        assertTrue(CompilerUtil.compile("17", files), "the decompiled sources do not recompile");
    }

    private static String source(List<JavaFileObjectPair> list, String internalName) {
        for (JavaFileObjectPair pair : list) {
            if (pair.internalName.equals(internalName)) {
                return pair.source;
            }
        }
        throw new IllegalArgumentException(internalName);
    }

    private static final class JavaFileObjectPair {
        final String internalName;
        final String source;

        JavaFileObjectPair(String internalName, String source) {
            this.internalName = internalName;
            this.source = source;
        }
    }
}
