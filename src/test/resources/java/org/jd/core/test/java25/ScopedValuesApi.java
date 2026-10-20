package org.jd.core.test.java25;

/**
 * Java 25 (JEP 506): scoped values. API only; lambdas passed to Carrier.run / call.
 */
public class ScopedValuesApi {
    private static final ScopedValue<String> USER = ScopedValue.newInstance();
    private static final ScopedValue<Integer> LEVEL = ScopedValue.newInstance();

    public void runWith(String user, Runnable runnable) {
        ScopedValue.where(USER, user).run(runnable);
    }

    public String callWith(String user) {
        return ScopedValue.where(USER, user).call(() -> USER.get().toUpperCase());
    }

    public String twoValues(String user, int level) {
        return ScopedValue.where(USER, user).where(LEVEL, level).call(() -> USER.get() + LEVEL.get());
    }

    public String current() {
        return USER.isBound() ? USER.get() : USER.orElse("anonymous");
    }
}
