package com.kooo.evcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The USB stick keeps room for new clips; the car and the stick have their own limits (2026-10-13). */
public class AfmcStorageLimitsTest {
    private static final long GB = AfmcStorageLimits.GB;

    @Test public void a_stick_keeps_2_gb_or_5_percent_free() {
        assertEquals(2 * GB, AfmcStorageLimits.lowWaterBytes(16 * GB));
        assertEquals(64 * GB / 20, AfmcStorageLimits.lowWaterBytes(64 * GB));
        assertEquals(64 * GB / 20 + 2 * GB, AfmcStorageLimits.targetFreeBytes(64 * GB));
    }

    @Test public void nothing_is_deleted_while_there_is_room() {
        assertEquals(0, AfmcStorageLimits.bytesToFree(50 * GB, 64 * GB));
        assertEquals(0, AfmcStorageLimits.bytesToFree(64 * GB / 20, 64 * GB));
    }

    @Test public void a_short_stick_deletes_up_to_the_target() {
        long free = 1 * GB;
        assertEquals(AfmcStorageLimits.targetFreeBytes(64 * GB) - free, AfmcStorageLimits.bytesToFree(free, 64 * GB));
        assertEquals(3 * GB, AfmcStorageLimits.bytesToFree(1 * GB, 16 * GB));
    }

    @Test public void unknown_sizes_delete_nothing() {
        assertEquals(0, AfmcStorageLimits.bytesToFree(-1, 64 * GB));
        assertEquals(0, AfmcStorageLimits.bytesToFree(1 * GB, -1));
        assertEquals(0, AfmcStorageLimits.bytesToFree(1 * GB, 0));
    }

    @Test public void typed_limits() {
        assertEquals(0, AfmcStorageLimits.parseLimitGb(null));
        assertEquals(0, AfmcStorageLimits.parseLimitGb(""));
        assertEquals(0, AfmcStorageLimits.parseLimitGb(" 0 "));
        assertEquals(0, AfmcStorageLimits.parseLimitGb("abc"));
        assertEquals(0, AfmcStorageLimits.parseLimitGb("99999999999"));
        assertEquals(40, AfmcStorageLimits.parseLimitGb(" 40"));
    }

    @Test
    public void aClipStillBeingWrittenIsNeverDeleted() {
        long now = 1_000_000_000L;
        assertFalse(AfmcStorageLimits.oldEnoughToDelete(now - 30_000L, now));
        assertFalse(AfmcStorageLimits.oldEnoughToDelete(now - AfmcStorageLimits.ACTIVE_CLIP_MS, now));
        assertTrue(AfmcStorageLimits.oldEnoughToDelete(now - AfmcStorageLimits.ACTIVE_CLIP_MS - 1, now));
    }
}
