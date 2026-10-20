package org.jd.core.test.java8;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Java 8 (JEP 126): lambda forms that go beyond the basic cases of Lambda.java.
 */
public class LambdaAdvanced {
    private static final Supplier<String> STATIC_SUPPLIER = () -> "static";
    private final Function<Integer, Integer> fieldLambda = x -> x + 1;
    private int counter;

    public Runnable capturingThis() {
        return () -> counter++;
    }

    public Supplier<Supplier<String>> nestedLambda(String prefix) {
        return () -> () -> prefix + counter;
    }

    public IntFunction<int[]> arrayConstructorReference() {
        return int[]::new;
    }

    public Supplier<List<String>> genericConstructorReference() {
        return ArrayList::new;
    }

    public Supplier<String> thisMethodReference() {
        return this::toString;
    }

    public Supplier<String> superMethodReference() {
        return super::toString;
    }

    public Runnable intersectionCast() {
        return (Runnable & Serializable) () -> System.out.println("serializable");
    }

    public Comparator<String> comparatorChain() {
        return Comparator.comparing(String::length).thenComparing(Comparator.naturalOrder());
    }

    public Map<Boolean, List<String>> streamPipeline(List<String> list) {
        return list.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .collect(Collectors.partitioningBy(s -> s.length() > 3));
    }

    public int intStream(int[] values) {
        return Arrays.stream(values).filter(v -> v > 0).map(v -> v * 2).sum();
    }

    public BiFunction<Integer, Integer, Integer> blockLambda() {
        return (a, b) -> {
            int sum = a + b;
            if (sum > 10) {
                return sum - 10;
            }
            return sum;
        };
    }

    public static <T, R> List<R> mapAll(List<T> list, Function<? super T, ? extends R> function) {
        List<R> result = new ArrayList<>();
        list.forEach(item -> result.add(function.apply(item)));
        return result;
    }

    public Supplier<Integer> localClassCapture(int base) {
        class Local {
            int value() {
                return base * 2;
            }
        }
        return () -> new Local().value();
    }

    public String useFields() {
        return STATIC_SUPPLIER.get() + fieldLambda.apply(1);
    }
}
