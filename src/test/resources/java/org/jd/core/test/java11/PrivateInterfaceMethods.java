package org.jd.core.test.java11;

/**
 * Java 9 (JEP 213): private and private static interface methods.
 */
public interface PrivateInterfaceMethods {
    String name();

    default String greet() {
        return prefix() + name() + suffix();
    }

    default String shout() {
        return helper(name()).toUpperCase();
    }

    static String create(String name) {
        return helper(name);
    }

    private String prefix() {
        return "Hello, ";
    }

    private String suffix() {
        return "!";
    }

    private static String helper(String s) {
        return s.trim();
    }
}
