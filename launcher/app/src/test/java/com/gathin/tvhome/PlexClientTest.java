package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlexClientTest {
    @Test public void seasonPageOnlyWhenItExists() {
        String show = "https://watch.plex.tv/show/the-ramparts-of-ice";
        assertEquals(show + "/season/1", PlexClient.pageFor(show, 1, u -> true));
        assertEquals(show, PlexClient.pageFor(show, 2, u -> false));
        assertEquals(show, PlexClient.pageFor(show, 0, u -> { throw new AssertionError("no lookup without a season"); }));
    }

    @Test public void latinTitles() {
        assertTrue(PlexClient.latin("Reincarnated as a Sword"));
        assertTrue(PlexClient.latin("86: Eighty-Six · S2"));
        assertTrue(PlexClient.latin("Pokémon"));
        assertTrue(PlexClient.latin(""));
    }

    @Test public void nonLatinTitles() {
        assertFalse(PlexClient.latin("転生したら剣でした"));
        assertFalse(PlexClient.latin("时光代理人"));
        assertFalse(PlexClient.latin("Re:ゼロから始める異世界生活"));
        assertFalse(PlexClient.latin("ПЕРЕЗАЛИВ"));
    }
}
