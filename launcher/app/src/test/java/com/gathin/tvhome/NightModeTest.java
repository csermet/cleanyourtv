package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NightModeTest {
    private static int m(int h, int min) { return h * 60 + min; }

    @Test public void windowAcrossMidnight() {
        int s = m(23, 0), e = m(8, 0);
        assertTrue(NightMode.isNight(s, e, m(23, 0)));
        assertTrue(NightMode.isNight(s, e, m(2, 30)));
        assertTrue(NightMode.isNight(s, e, m(7, 59)));
        assertFalse(NightMode.isNight(s, e, m(8, 0)));
        assertFalse(NightMode.isNight(s, e, m(22, 59)));
        assertFalse(NightMode.isNight(s, e, m(12, 0)));
    }

    @Test public void windowWithinADay() {
        assertTrue(NightMode.isNight(m(13, 0), m(15, 0), m(14, 0)));
        assertFalse(NightMode.isNight(m(13, 0), m(15, 0), m(15, 0)));
    }

    @Test public void offWhenUnset() {
        assertFalse(NightMode.isNight(-1, m(8, 0), m(2, 0)));
        assertFalse(NightMode.isNight(m(8, 0), m(8, 0), m(8, 0)));
    }

    @Test public void clockParsing() {
        assertEquals(m(23, 0), Config.clock("23:00"));
        assertEquals(m(8, 5), Config.clock("8:05"));
        assertEquals(-1, Config.clock("24:00"));
        assertEquals(-1, Config.clock("nope"));
        assertEquals(-1, Config.clock(""));
    }
}
