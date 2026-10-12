package com.kooo.evcam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Outside cameras only, by size not by number (Test car 1, 2026-10-12: numbers changed after a camera-service restart). */
public class AfmcCameraGuardTest {
    @Test public void only_outside_cameras_are_opened_on_the_ex2() {
        assertTrue(AfmcCameraGuard.mayOpen(true, new int[]{1280, 800}));
        assertFalse("driver monitoring", AfmcCameraGuard.mayOpen(true, new int[]{1280, 960}));
        assertFalse("cabin", AfmcCameraGuard.mayOpen(true, new int[]{1920, 1080}));
    }

    @Test public void unknown_size_or_another_head_unit_is_left_as_before() {
        assertTrue(AfmcCameraGuard.mayOpen(true, null));
        assertTrue(AfmcCameraGuard.mayOpen(false, new int[]{1920, 1080}));
    }
}
