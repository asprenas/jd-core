package org.jd.core.test.java11;

/**
 * Java 9 (JEP 280): string concatenation compiles to invokedynamic
 * (StringConcatFactory.makeConcatWithConstants) instead of StringBuilder chains.
 */
public class StringConcat {
    private String name = "name";
    private int count = 3;

    public String simple(String a, int b) {
        return a + b;
    }

    public String constants(String a) {
        return "<" + a + ">";
    }

    public String mixed(String s, char c, long l, double d, boolean z, Object o) {
        return s + c + l + d + z + o + 1 + 'x' + 2L + 1.5f + null;
    }

    public String specialCharacters(String s) {
        return s + "\u0001" + "\u0002" + s;
    }

    public String compound(String s) {
        s += 1;
        s += "x";
        s += s;
        return s;
    }

    public String loop(int n) {
        String result = "";
        for (int i = 0; i < n; i++) {
            result += i + ",";
        }
        return result;
    }

    public String fields() {
        return name + ":" + count + ":" + this.name;
    }

    public String nested(String a, String b) {
        return "(" + (a + b) + ")" + (1 + 2) + 1 + 2;
    }

    public String withMethodCalls(Object o) {
        return "value=" + o.hashCode() + "/" + String.valueOf(o);
    }

    public String ternaryOperand(boolean b, String x) {
        return "x=" + (b ? x : "none");
    }

    public String arrays(int[] values) {
        return "values=" + java.util.Arrays.toString(values) + values.length;
    }
}
