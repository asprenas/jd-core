package org.jd.core.test.java11;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Java 10 (JEP 286) and Java 11 (JEP 323): var for locals and for lambda parameters.
 * The class file contains the inferred types, the decompiler may emit them or var.
 */
public class LocalVariableTypeInference {
    public int simple() {
        var i = 1;
        var s = "text";
        var l = 2L;
        return i + s.length() + (int) l;
    }

    public void generics() {
        var list = new ArrayList<String>();
        list.add("a");
        var map = new HashMap<String, List<Integer>>();
        map.computeIfAbsent("k", k -> new ArrayList<>()).add(1);
        for (var entry : map.entrySet()) {
            System.out.println(entry.getKey() + entry.getValue());
        }
    }

    public void loops(int[] array) {
        for (var i = 0; i < array.length; i++) {
            var v = array[i];
            System.out.println(v);
        }
        for (var v : array) {
            System.out.println(v);
        }
    }

    public void tryResource(String path) throws IOException {
        try (var in = new FileInputStream(path)) {
            System.out.println(in.read());
        }
    }

    public int anonymousType() {
        var o = new Object() {
            int hidden = 5;

            int twice() {
                return hidden * 2;
            }
        };
        return o.hidden + o.twice();
    }

    public BiFunction<Integer, Integer, Integer> lambdaVar() {
        return (var a, var b) -> a + b;
    }

    public void intersectionType(boolean b) {
        var x = b ? 1 : "s";
        System.out.println(x);
    }

    public void arrayAndCast() {
        var array = new int[] {1, 2};
        var runnable = (Runnable) () -> System.out.println(array.length);
        runnable.run();
    }
}
