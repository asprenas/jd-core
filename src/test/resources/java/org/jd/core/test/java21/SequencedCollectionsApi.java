package org.jd.core.test.java21;

import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.SequencedSet;

/**
 * Java 21 (JEP 431): sequenced collections. API only, but the interfaces are new
 * supertypes (List, Deque, LinkedHashSet, LinkedHashMap now extend them).
 */
public class SequencedCollectionsApi {
    public String firstLast(List<String> list) {
        return list.getFirst() + list.getLast();
    }

    public List<String> reversed(List<String> list) {
        return list.reversed();
    }

    public Deque<Integer> deque(Deque<Integer> deque) {
        deque.addFirst(1);
        deque.addLast(2);
        deque.removeFirst();
        return deque.reversed();
    }

    public Map.Entry<String, Integer> firstEntry(LinkedHashMap<String, Integer> map) {
        return map.firstEntry();
    }

    public SequencedSet<String> sequencedSet(LinkedHashSet<String> set) {
        set.addFirst("x");
        return set.reversed();
    }

    public SequencedMap<String, Integer> sequencedMap(LinkedHashMap<String, Integer> map) {
        return map.reversed();
    }
}
