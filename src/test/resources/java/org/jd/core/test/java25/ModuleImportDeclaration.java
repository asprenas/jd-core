package org.jd.core.test.java25;

import module java.base;

/**
 * Java 25 (JEP 511): module import declarations. Source-only feature, the class file
 * references the resolved types, so the decompiler cannot (and need not) bring it back.
 * Useful to check that decompiled output with explicit imports recompiles.
 */
public class ModuleImportDeclaration {
    public List<String> sortedKeys(Map<String, Integer> map) {
        return map.keySet().stream().sorted().collect(Collectors.toList());
    }

    public Path path(String s) {
        return Path.of(s);
    }

    public BigDecimal sum(List<BigDecimal> values) {
        BigDecimal result = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            result = result.add(value);
        }
        return result;
    }

    public Optional<LocalDate> parse(String s) {
        try {
            return Optional.of(LocalDate.parse(s));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
