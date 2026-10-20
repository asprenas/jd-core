package org.jd.core.test.java25;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Java 22 (JEP 456): unnamed variables and patterns. The class file keeps a normal
 * (or no) local variable slot, the decompiler has to decide when to emit '_'.
 */
public class UnnamedVariables {
    record Point(int x, int y) {
    }

    sealed interface Shape permits Circle, Square {
    }

    record Circle(Point center, int radius) implements Shape {
    }

    record Square(Point corner, int side) implements Shape {
    }

    public int unusedCatch(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    public int unusedForEach(List<String> list) {
        int n = 0;
        for (var _ : list) {
            n++;
        }
        return n;
    }

    public int unusedLocals() {
        var _ = System.nanoTime();
        int _ = 5;
        return 1;
    }

    public void unusedResource(String path) throws IOException {
        try (var _ = new FileInputStream(path)) {
            System.out.println("opened");
        }
    }

    public BiFunction<Integer, Integer, Integer> unusedLambdaParameter() {
        return (a, _) -> a;
    }

    public int recordPattern(Object o) {
        return o instanceof Point(int x, _) ? x : -1;
    }

    public String switchPattern(Shape shape) {
        return switch (shape) {
            case Circle(Point(int x, _), _) -> "circle at " + x;
            case Square(_, int side) -> "square " + side;
        };
    }

    public String typeOnly(Object o) {
        return switch (o) {
            case Integer _ -> "int";
            case String _ -> "string";
            default -> "other";
        };
    }

    public int multiplePatterns(Object o) {
        return switch (o) {
            case Integer _, Long _ -> 1;
            default -> 0;
        };
    }
}
