package org.jd.core.test.java11;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Java 9 (JEP 213): diamond operator with anonymous inner classes.
 */
public class DiamondAnonymous {
    public Comparator<String> comparator() {
        return new Comparator<>() {
            @Override
            public int compare(String a, String b) {
                return a.length() - b.length();
            }
        };
    }

    public List<String> doubleBrace() {
        return new ArrayList<>() {
            {
                add("a");
                add("b");
            }
        };
    }

    public Iterator<Integer> iterator(int n) {
        return new Iterator<>() {
            private int i;

            @Override
            public boolean hasNext() {
                return i < n;
            }

            @Override
            public Integer next() {
                return i++;
            }
        };
    }
}
