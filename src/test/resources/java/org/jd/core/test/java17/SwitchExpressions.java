package org.jd.core.test.java17;

/**
 * Java 14 (JEP 361): switch expressions, arrow labels, multiple labels, yield.
 */
public class SwitchExpressions {
    enum Day {
        MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY
    }

    public int enumSwitch(Day day) {
        return switch (day) {
            case MONDAY, FRIDAY, SUNDAY -> 6;
            case TUESDAY -> 7;
            case THURSDAY, SATURDAY -> 8;
            case WEDNESDAY -> 9;
        };
    }

    public String stringSwitch(String s) {
        return switch (s) {
            case "a", "b" -> "first";
            case "c" -> {
                String upper = s.toUpperCase();
                yield upper + upper;
            }
            default -> throw new IllegalArgumentException(s);
        };
    }

    public int oldStyleYield(int k) {
        return switch (k) {
            case 1:
                yield 10;
            case 2:
            case 3: {
                int r = k * 2;
                yield r;
            }
            default:
                yield 0;
        };
    }

    public void statementArrow(int k) {
        switch (k) {
            case 1 -> System.out.println("one");
            case 2, 3 -> {
                System.out.println("few");
            }
            default -> System.out.println("many");
        }
    }

    public int nested(int a, int b) {
        return switch (a) {
            case 0 -> switch (b) {
                case 0 -> 1;
                default -> 2;
            };
            default -> a;
        };
    }

    public int asArgument(int k) {
        return Math.max(switch (k) {
            case 1 -> 5;
            default -> 7;
        }, 3);
    }

    public String inExpression(Day day) {
        return "day:" + switch (day) {
            case SATURDAY, SUNDAY -> "weekend";
            default -> "weekday";
        };
    }

    public int withTryCatch(String s) {
        return switch (s.length()) {
            case 0 -> 0;
            default -> {
                try {
                    yield Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    yield -1;
                }
            }
        };
    }

    public void assignToVariable(int k) {
        String text = switch (k) {
            case 1 -> "one";
            case 2 -> "two";
            default -> "many";
        };
        System.out.println(text);
    }
}
