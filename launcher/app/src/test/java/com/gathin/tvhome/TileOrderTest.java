package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class TileOrderTest {
    private static final List<String> ABC = Arrays.asList("a", "b", "c", "d");

    @Test public void movesOnePlace() {
        assertEquals(Arrays.asList("b", "a", "c", "d"), TileOrder.move(ABC, "b", -1));
        assertEquals(Arrays.asList("a", "c", "b", "d"), TileOrder.move(ABC, "b", 1));
    }

    @Test public void clampsAtTheEnds() {
        assertEquals(ABC, TileOrder.move(ABC, "a", -1));
        assertEquals(ABC, TileOrder.move(ABC, "d", 1));
    }

    @Test public void unknownPackageLeavesOrder() {
        assertEquals(ABC, TileOrder.move(ABC, "x", 1));
    }

    @Test public void splitIgnoresBlanks() {
        assertEquals(Arrays.asList("a", "b"), TileOrder.split(" a, ,b ,"));
        assertEquals(Collections.emptyList(), TileOrder.split(""));
        assertEquals(Collections.emptyList(), TileOrder.split(null));
    }
}
