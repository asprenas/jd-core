package org.jd.core.test.java17;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 16 (JEP 395, JEP 397): local records, local enums, local interfaces and
 * static members in inner classes.
 */
public class LocalTypes {
    public int localRecord(List<String> names) {
        record Entry(String name, int length) {
        }

        List<Entry> entries = new ArrayList<>();
        for (String n : names) {
            entries.add(new Entry(n, n.length()));
        }
        int total = 0;
        for (Entry e : entries) {
            total += e.length();
        }
        return total;
    }

    public String localEnum(int k) {
        enum Level {
            LOW, HIGH
        }

        Level level = k > 5 ? Level.HIGH : Level.LOW;
        return level.name();
    }

    public int localInterface() {
        interface Op {
            int apply(int a);
        }

        Op twice = a -> a * 2;
        return twice.apply(21);
    }

    public class Inner {
        static int counter = 0;

        static int next() {
            return ++counter;
        }

        record InnerRecord(int a) {
        }
    }
}
