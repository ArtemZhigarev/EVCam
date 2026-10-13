package com.kooo.evcam;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AfmcThemePolicyTest {

    @Test
    public void onlyGlassFromLocalStoreRestylesEvcam() {
        assertTrue(AfmcThemePolicy.isGlass("glass"));
        assertTrue(AfmcThemePolicy.isGlass(" glass\n"));
        assertFalse(AfmcThemePolicy.isGlass("classic"));
        // no LocalStore, an older one, or a look EVCam doesn't know: upstream's look
        assertFalse(AfmcThemePolicy.isGlass(null));
        assertFalse(AfmcThemePolicy.isGlass(""));
        assertFalse(AfmcThemePolicy.isGlass("neon"));
        assertEquals(AfmcThemePolicy.CLASSIC, AfmcThemePolicy.parse(null));
    }

    @Test
    public void cornersAreRounderButSquareOnesStaySquare() {
        assertEquals(18f, AfmcThemePolicy.scaleRadius(12f), 0.001f);
        assertEquals(0f, AfmcThemePolicy.scaleRadius(0f), 0.001f);
    }

    @Test
    public void theFirstPairForAColourWins() {
        Map<Integer, Integer> m = AfmcThemePolicy.colourMap(new int[][] { { 1, 10 }, { 1, 20 }, { 2, 30 } });
        assertEquals(Integer.valueOf(10), m.get(1));
        assertEquals(Integer.valueOf(30), m.get(2));
    }

    @Test
    public void aGlassColourThatIsAlsoAnUpstreamOneIsLeftOutSoRestylingTwiceChangesNothing() {
        // 1 -> 2 and 2 -> 3: styling twice would turn 1 into 3, so 1 is not mapped
        Map<Integer, Integer> m = AfmcThemePolicy.colourMap(new int[][] { { 1, 2 }, { 2, 3 } });
        assertNull(m.get(1));
        assertEquals(Integer.valueOf(3), m.get(2));
        // a colour kept as it is stays mapped
        assertEquals(Integer.valueOf(5), AfmcThemePolicy.colourMap(new int[][] { { 5, 5 } }).get(5));
    }
}
