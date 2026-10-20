package org.jd.core.test.java21;

/**
 * Java 21 (JEP 441): pattern matching for switch without guards and record patterns.
 * javac compiles these switches with invokedynamic SwitchBootstraps.typeSwitch / enumSwitch
 * (labels: Class, String, Integer, enum constants through EnumDesc dynamic constants).
 */
public class TypeSwitchBasic {
    enum Coin { HEADS, TAILS }

    public String describe(Object o) {
        return switch (o) {
            case null -> "null";
            case Integer i -> "int " + i;
            case String s -> "string " + s.length();
            case int[] a -> "array " + a.length;
            default -> "other";
        };
    }

    public int strings(String s) {
        return switch (s) {
            case "a" -> 1;
            case "bb" -> 2;
            case String t -> t.length() + 10;
        };
    }

    public int boxed(Integer n) {
        return switch (n) {
            case 1 -> 10;
            case 2 -> 20;
            case Integer m -> m * 2;
        };
    }

    public String coins(Object o) {
        return switch (o) {
            case Coin.HEADS -> "heads";
            case Coin.TAILS -> "tails";
            default -> "not a coin";
        };
    }

    public String enumPattern(Coin c) {
        return switch (c) {
            case HEADS -> "h";
            case Coin x -> "other " + x;
        };
    }

    public String statement(Object o) {
        StringBuilder sb = new StringBuilder();
        switch (o) {
            case String s -> sb.append("s:").append(s);
            case Integer i -> sb.append("i:").append(i + 1);
            default -> sb.append("?");
        }
        return sb.toString();
    }
}
