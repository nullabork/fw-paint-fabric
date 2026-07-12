package co.fax.wang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure helpers for the manual "Pick" order mode: blocks carry a user-assigned number (1+) and the
 * gradient is exactly those blocks, lowest number → highest. Blocks sharing a number form a tie
 * group (one is chosen at random per placement). Number 0 / absent = not used.
 */
public final class Picks {

    private Picks() {}

    /** Ids assigned a number > 0, grouped by number and sorted ascending (each group = a tie). */
    public static List<List<String>> groups(List<String> ids, Map<String, Integer> numbers) {
        TreeMap<Integer, List<String>> byNumber = new TreeMap<>();
        for (String id : ids) {
            int n = numbers.getOrDefault(id, 0);
            if (n > 0) byNumber.computeIfAbsent(n, k -> new ArrayList<>()).add(id);
        }
        return new ArrayList<>(byNumber.values());
    }

    /** New number after a left-click (increment). */
    public static int increment(int current) {
        return current + 1;
    }

    /** New number after a right-click (decrement, floored at 0 = unassigned). */
    public static int decrement(int current) {
        return Math.max(0, current - 1);
    }
}
