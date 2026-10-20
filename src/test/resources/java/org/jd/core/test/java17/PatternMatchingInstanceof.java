package org.jd.core.test.java17;

import java.util.List;

/**
 * Java 16 (JEP 394): pattern matching for instanceof. The class file only contains
 * instanceof + checkcast + a synthetic local variable, the binding has to be rebuilt.
 */
public class PatternMatchingInstanceof {
    public int length(Object o) {
        if (o instanceof String s) {
            return s.length();
        }
        return -1;
    }

    public boolean andCondition(Object o) {
        return o instanceof String s && s.length() > 3;
    }

    public String notPattern(Object o) {
        if (!(o instanceof String s)) {
            return "not a string";
        }
        return s.trim();
    }

    public String chain(Object o) {
        if (o instanceof Integer i) {
            return "int " + (i + 1);
        } else if (o instanceof String s && !s.isEmpty()) {
            return "str " + s;
        } else if (o instanceof List<?> l) {
            return "list " + l.size();
        }
        return "other";
    }

    public int ternary(Object o) {
        return o instanceof Integer i ? i : -1;
    }

    public int loop(Object[] values) {
        int sum = 0;
        for (Object v : values) {
            if (!(v instanceof Integer i)) {
                continue;
            }
            sum += i;
        }
        return sum;
    }

    public boolean whileLoop(Object o) {
        while (!(o instanceof String s)) {
            o = String.valueOf(o);
        }
        return s.isEmpty();
    }

    public boolean equalsLike(Object o) {
        return o instanceof PatternMatchingInstanceof other && other.hashCode() == hashCode();
    }

    public int arrayPattern(Object o) {
        if (o instanceof int[] a && a.length > 0) {
            return a[0];
        }
        return 0;
    }

    public String reuseName(Object a, Object b) {
        if (a instanceof String s) {
            return s;
        }
        if (b instanceof String s) {
            return s + "!";
        }
        return "";
    }
}
