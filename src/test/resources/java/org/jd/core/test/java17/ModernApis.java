package org.jd.core.test.java17;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java 9 to 17 library additions. Plain bytecode, but useful to check generic type
 * inference and method resolution for newer API signatures.
 */
public class ModernApis {
    public List<String> streamToList(List<String> list) {
        return list.stream().map(String::strip).toList();
    }

    public String repeat(String s) {
        return s.repeat(3) + (s.isBlank() ? "blank" : "text");
    }

    public List<String> lines(String text) {
        return text.lines().collect(Collectors.toList());
    }

    public Map<String, Integer> mapOf() {
        return Map.of("a", 1, "b", 2);
    }

    public List<Integer> listOf() {
        return List.of(1, 2, 3);
    }

    public boolean optional(Optional<String> o) {
        return o.isEmpty() || o.orElseThrow().isEmpty();
    }

    public String readFile(Path path) throws IOException {
        return Files.readString(path);
    }

    public double teeing(Stream<Integer> stream) {
        return stream.collect(Collectors.teeing(
            Collectors.summingInt(i -> i),
            Collectors.counting(),
            (sum, count) -> (double) sum / count));
    }

    public String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
