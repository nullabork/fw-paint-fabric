package co.fax.wang;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PicksTest {

    @Test
    void groupsSortByNumberAscending() {
        Map<String, Integer> nums = new HashMap<>();
        nums.put("a", 3);
        nums.put("b", 1);
        nums.put("c", 2);
        List<List<String>> groups = Picks.groups(List.of("a", "b", "c"), nums);
        assertEquals(List.of(List.of("b"), List.of("c"), List.of("a")), groups);
    }

    @Test
    void unnumberedBlocksAreExcluded() {
        Map<String, Integer> nums = new HashMap<>();
        nums.put("a", 1);
        // b has no entry, c is explicitly 0
        nums.put("c", 0);
        List<List<String>> groups = Picks.groups(List.of("a", "b", "c"), nums);
        assertEquals(List.of(List.of("a")), groups);
    }

    @Test
    void tiesShareAGroup() {
        Map<String, Integer> nums = new HashMap<>();
        nums.put("a", 1);
        nums.put("b", 1);
        nums.put("c", 2);
        List<List<String>> groups = Picks.groups(List.of("a", "b", "c"), nums);
        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).size()); // a + b tie at number 1
        assertEquals(List.of("c"), groups.get(1));
    }

    @Test
    void incrementAndDecrement() {
        assertEquals(1, Picks.increment(0));
        assertEquals(4, Picks.increment(3));
        assertEquals(2, Picks.decrement(3));
        assertEquals(0, Picks.decrement(0)); // floored
    }
}
