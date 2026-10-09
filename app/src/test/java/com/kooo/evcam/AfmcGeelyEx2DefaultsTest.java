package com.kooo.evcam;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void rightCameraIsId2WhenId2IsAnOutsideCamera() {
        assertEquals("2", AfmcGeelyEx2Defaults.chooseRightCamera(new int[]{1280, 800}, new int[]{1280, 800}));
        assertEquals("2", AfmcGeelyEx2Defaults.chooseRightCamera(new int[]{1280, 800}, null));
    }

    @Test
    public void rightCameraIsId6OnTheTenCameraHeadUnit() {
        assertEquals("6", AfmcGeelyEx2Defaults.chooseRightCamera(new int[]{3280, 2464}, new int[]{1280, 800}));
        assertEquals("6", AfmcGeelyEx2Defaults.chooseRightCamera(null, new int[]{1280, 800}));
    }

    @Test
    public void rightCameraFallsBackToId2() {
        assertEquals("2", AfmcGeelyEx2Defaults.chooseRightCamera(null, null));
        assertEquals("2", AfmcGeelyEx2Defaults.chooseRightCamera(new int[]{3280, 2464}, new int[]{1920, 1080}));
        assertEquals("2", AfmcGeelyEx2Defaults.chooseRightCamera(new int[]{1280}, new int[0]));
    }
}
