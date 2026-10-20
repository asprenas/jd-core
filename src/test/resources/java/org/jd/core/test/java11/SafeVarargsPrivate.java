package org.jd.core.test.java11;

import java.util.Arrays;
import java.util.List;

/**
 * Java 9 (JEP 213): @SafeVarargs on private instance methods.
 */
public class SafeVarargsPrivate {
    @SafeVarargs
    private <T> List<T> listOf(T... items) {
        return Arrays.asList(items);
    }

    @SafeVarargs
    private static <T> int count(T... items) {
        return items.length;
    }

    public int use() {
        return listOf("a", "b").size() + count(1, 2, 3);
    }
}
