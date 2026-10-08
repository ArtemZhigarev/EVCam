package com.kooo.evcam;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AfmcGeelyEx2DefaultsTest {
    @Test
    public void clearsTheFrontMirrorOurOldPresetSet() {
        assertTrue(AfmcGeelyEx2Defaults.shouldClearFrontMirror(true, "custom", "4"));
    }

    @Test
    public void leavesEverythingElseAlone() {
        assertFalse(AfmcGeelyEx2Defaults.shouldClearFrontMirror(false, "custom", "4"));
        assertFalse(AfmcGeelyEx2Defaults.shouldClearFrontMirror(true, "galaxy_e5", "4"));
        assertFalse(AfmcGeelyEx2Defaults.shouldClearFrontMirror(true, "custom", "2"));
        assertFalse(AfmcGeelyEx2Defaults.shouldClearFrontMirror(true, null, null));
    }
}
