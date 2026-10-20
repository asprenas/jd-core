package org.jd.core.test.java11;

/**
 * Java 11 (JEP 181): nest-based access control. Inner and outer classes access each
 * other's private members directly, no synthetic access$NNN methods and no
 * synthetic NestMates$1 constructor-accessor classes are generated.
 */
public class NestMates {
    private int privateField = 1;
    private static int privateStatic = 2;

    private NestMates() {
    }

    private NestMates(int value) {
        privateField = value;
    }

    private int privateMethod() {
        return privateField;
    }

    public class Inner {
        private int innerField = 10;

        private Inner() {
        }

        public int readOuter() {
            return privateField + privateStatic + privateMethod();
        }

        public void writeOuter() {
            privateField++;
            privateField += 2;
            privateStatic = 3;
        }

        public NestMates create() {
            return new NestMates(5);
        }
    }

    public static class StaticNested {
        private String secret = "s";

        private static String staticSecret() {
            return "x";
        }
    }

    public int readInner() {
        Inner inner = new Inner();
        StaticNested nested = new StaticNested();
        inner.innerField++;
        return inner.innerField + nested.secret.length() + StaticNested.staticSecret().length();
    }

    public Runnable anonymous() {
        return new Runnable() {
            @Override
            public void run() {
                privateField++;
                privateMethod();
            }
        };
    }

    public static NestMates create() {
        return new NestMates();
    }
}
