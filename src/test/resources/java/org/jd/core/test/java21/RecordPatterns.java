package org.jd.core.test.java21;

/**
 * Java 21 (JEP 440): record patterns. Deconstruction is compiled to accessor calls
 * guarded by try/catch that wraps accessor exceptions in MatchException.
 */
public class RecordPatterns {
    record Point(int x, int y) {
    }

    record Line(Point from, Point to) {
    }

    record Box<T>(T content) {
    }

    sealed interface Node permits Leaf, Pair {
    }

    record Leaf(int value) implements Node {
    }

    record Pair(Node left, Node right) implements Node {
    }

    public int instanceofPattern(Object o) {
        if (o instanceof Point(int x, int y)) {
            return x + y;
        }
        return 0;
    }

    public int nestedPattern(Object o) {
        if (o instanceof Line(Point(var x1, var y1), Point(int x2, int y2))) {
            return (x2 - x1) + (y2 - y1);
        }
        return 0;
    }

    public String switchPattern(Object o) {
        return switch (o) {
            case Point(int x, int y) when x == y -> "diagonal";
            case Point(int x, int y) -> "point " + x + "," + y;
            case Line(Point from, Point to) -> "line";
            default -> "other";
        };
    }

    public int sum(Node node) {
        return switch (node) {
            case Leaf(int v) -> v;
            case Pair(Leaf(var a), Leaf(var b)) -> a + b;
            case Pair(Node l, Node r) -> sum(l) + sum(r);
        };
    }

    public String genericExhaustive(Box<String> box) {
        return switch (box) {
            case Box<String>(var s) -> s;
        };
    }

    public String genericInstanceof(Box<Object> box) {
        if (box instanceof Box<Object>(String s)) {
            return s;
        }
        return "none";
    }

    public boolean andCondition(Object o) {
        return o instanceof Point(int x, int y) && x > y;
    }
}
