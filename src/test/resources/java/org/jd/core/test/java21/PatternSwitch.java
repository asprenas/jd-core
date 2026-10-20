package org.jd.core.test.java21;

/**
 * Java 21 (JEP 441): pattern matching for switch. Class file uses invokedynamic
 * SwitchBootstraps.typeSwitch / enumSwitch with a restart index loop, guards are
 * compiled into the case bodies, exhaustive switches get a MatchException default.
 */
public class PatternSwitch {
    sealed interface Shape permits Circle, Rect, Tri {
    }

    record Circle(double r) implements Shape {
    }

    record Rect(double w, double h) implements Shape {
    }

    record Tri(double b, double h) implements Shape {
    }

    enum Color {
        RED, GREEN, BLUE
    }

    sealed interface Currency permits Coin, Note {
    }

    enum Coin implements Currency {
        HEADS, TAILS
    }

    enum Note implements Currency {
        FIVE, TEN
    }

    public String typePatterns(Object o) {
        return switch (o) {
            case Integer i -> "int " + i;
            case Long l -> "long " + l;
            case String s -> "string " + s;
            case int[] a -> "int array " + a.length;
            default -> "other";
        };
    }

    public String guarded(Object o) {
        return switch (o) {
            case String s when s.isEmpty() -> "empty";
            case String s when s.length() > 5 -> "long " + s;
            case String s -> "short " + s;
            case Integer i when i > 0 -> "positive";
            case Integer i -> "non-positive";
            default -> "other";
        };
    }

    public String withNull(Object o) {
        return switch (o) {
            case null -> "null";
            case String s -> s;
            default -> "x";
        };
    }

    public String nullAndDefault(String s) {
        return switch (s) {
            case "a" -> "A";
            case null, default -> "?";
        };
    }

    public double sealedExhaustive(Shape shape) {
        return switch (shape) {
            case Circle c -> Math.PI * c.r() * c.r();
            case Rect r -> r.w() * r.h();
            case Tri t -> t.b() * t.h() / 2;
        };
    }

    public void statement(Object o) {
        switch (o) {
            case String s -> System.out.println(s);
            case Integer i -> {
                System.out.println(i + 1);
            }
            default -> {
            }
        }
    }

    public String oldStyle(Object o) {
        switch (o) {
            case String s:
                return s;
            case Integer i:
                return "i" + i;
            default:
                return "d";
        }
    }

    public int enumWithGuard(Object o) {
        return switch (o) {
            case Color c when c == Color.RED -> 1;
            case Color c -> 2;
            default -> 0;
        };
    }

    public String qualifiedEnumConstants(Currency currency) {
        return switch (currency) {
            case Coin.HEADS -> "h";
            case Coin.TAILS -> "t";
            case Note n -> "n" + n;
        };
    }

    public int yieldInBlock(Object o) {
        return switch (o) {
            case String s -> {
                int n = s.length();
                yield n * 2;
            }
            case Integer i -> i;
            default -> 0;
        };
    }
}
