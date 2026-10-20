package org.jd.core.test.java25;

import java.util.List;
import java.util.Optional;
import java.util.stream.Gatherers;

/**
 * Java 24 (JEP 485): stream gatherers. API only, generic method inference with
 * wildcard-heavy signatures.
 */
public class StreamGatherersApi {
    public List<List<Integer>> windowFixed(List<Integer> in) {
        return in.stream().gather(Gatherers.windowFixed(3)).toList();
    }

    public List<List<Integer>> windowSliding(List<Integer> in) {
        return in.stream().gather(Gatherers.windowSliding(2)).toList();
    }

    public Optional<Integer> fold(List<Integer> in) {
        return in.stream().gather(Gatherers.fold(() -> 0, (a, b) -> a + b)).findFirst();
    }

    public List<Integer> scan(List<Integer> in) {
        return in.stream().gather(Gatherers.scan(() -> 0, (a, b) -> a + b)).toList();
    }

    public List<String> mapConcurrent(List<Integer> in) {
        return in.stream().gather(Gatherers.mapConcurrent(4, i -> "v" + i)).toList();
    }

    public List<Integer> composed(List<Integer> in) {
        return in.stream()
            .gather(Gatherers.<Integer>windowFixed(2).andThen(Gatherers.mapConcurrent(2, (List<Integer> w) -> w.size())))
            .toList();
    }
}
