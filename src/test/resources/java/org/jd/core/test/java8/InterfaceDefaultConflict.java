package org.jd.core.test.java8;

/**
 * Java 8 (JEP 126): default method conflict resolution (X.super.m()),
 * static interface methods and defaults that call lambdas.
 */
public class InterfaceDefaultConflict {
    interface A {
        default String name() {
            return "A";
        }
    }

    interface B {
        default String name() {
            return "B";
        }
    }

    static class C implements A, B {
        @Override
        public String name() {
            return A.super.name() + B.super.name();
        }
    }

    @FunctionalInterface
    interface Calculator {
        int calc(int a, int b);

        static Calculator add() {
            return (a, b) -> a + b;
        }

        default Calculator twice() {
            return (a, b) -> calc(calc(a, b), calc(a, b));
        }
    }

    interface Child extends Calculator {
        @Override
        default Calculator twice() {
            return Calculator.super.twice();
        }
    }

    public int run() {
        return Calculator.add().twice().calc(1, 2) + new C().name().length();
    }
}
