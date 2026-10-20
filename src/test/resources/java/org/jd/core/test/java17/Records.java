package org.jd.core.test.java17;

import java.io.Serializable;
import java.util.List;

/**
 * Java 16 (JEP 395): records. Class file has a Record attribute, a final class extending
 * java.lang.Record and invokedynamic ObjectMethods for equals/hashCode/toString.
 */
public class Records {
    public record Point(int x, int y) {
    }

    public record Named(String name, int... values) {
    }

    public record Range(int lo, int hi) {
        public static final Range EMPTY = new Range(0, 0);

        public Range {
            if (lo > hi) {
                throw new IllegalArgumentException("lo > hi");
            }
        }

        public static Range of(int a, int b) {
            return new Range(Math.min(a, b), Math.max(a, b));
        }

        public int length() {
            return hi - lo;
        }
    }

    public record Pair<A, B>(A first, B second) {
    }

    public record Person(String name, int age) implements Serializable {
        public Person(String name) {
            this(name, 0);
        }

        @Override
        public String name() {
            return name.toUpperCase();
        }

        @Override
        public String toString() {
            return "Person " + name;
        }
    }

    public record Wrapper<T extends Comparable<T>>(T value) {
        public boolean isGreater(Wrapper<T> other) {
            return value.compareTo(other.value) > 0;
        }
    }

    public record Annotated(@Deprecated int old, @SuppressWarnings("unused") String text) {
    }

    public record Explicit(int a, int b) {
        public Explicit(int a, int b) {
            this.a = a * 2;
            this.b = b;
        }
    }

    public interface Shape {
        double area();
    }

    public record Circle(double radius) implements Shape {
        @Override
        public double area() {
            return Math.PI * radius * radius;
        }
    }

    public record Nested(Point origin, List<Point> points) {
    }

    public Point create() {
        return new Point(1, 2);
    }

    public int use(Point p) {
        return p.x() + p.y();
    }

    public boolean same(Point a, Point b) {
        return a.equals(b) && a.hashCode() == b.hashCode();
    }

    public String text(Pair<String, Integer> pair) {
        return pair.first() + pair.second() + pair;
    }
}
