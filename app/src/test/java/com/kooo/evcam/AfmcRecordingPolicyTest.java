package com.kooo.evcam;

import org.junit.Test;

import static com.kooo.evcam.AfmcRecordingPolicy.Action.NONE;
import static com.kooo.evcam.AfmcRecordingPolicy.Action.START;
import static com.kooo.evcam.AfmcRecordingPolicy.Action.STOP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AfmcRecordingPolicyTest {

    @Test
    public void switchingOnWhileTheCarIsInUseStartsRecording() {
        assertEquals(START, AfmcRecordingPolicy.decide(false, true, true, false, false));
        // already on (e.g. after the driver stopped it by hand): switching on again asks for it
        assertEquals(START, AfmcRecordingPolicy.decide(true, true, true, false, false));
    }

    @Test
    public void switchingOnWhileParkedOrAlreadyRecordingDoesNothingNow() {
        assertEquals(NONE, AfmcRecordingPolicy.decide(false, true, false, false, false));
        assertEquals(NONE, AfmcRecordingPolicy.decide(false, true, true, false, true));
    }

    @Test
    public void switchingOffStopsARecording() {
        assertEquals(STOP, AfmcRecordingPolicy.decide(true, false, true, false, true));
        assertEquals(STOP, AfmcRecordingPolicy.decide(true, false, false, false, true));
        assertEquals(NONE, AfmcRecordingPolicy.decide(true, false, true, false, false));
    }

    @Test
    public void aRepeatedOffLeavesAHandStartedRecordingAlone() {
        assertEquals(NONE, AfmcRecordingPolicy.decide(false, false, true, false, true));
    }

    @Test
    public void neverTouchesASentryRecording() {
        assertEquals(NONE, AfmcRecordingPolicy.decide(true, false, false, true, true));
        assertEquals(NONE, AfmcRecordingPolicy.decide(false, true, true, true, false));
    }

    @Test
    public void storageIsTheStickOnlyWhenChosenAndPresent() {
        assertEquals("usb", AfmcRecordingPolicy.storage(true, true));
        assertEquals("internal", AfmcRecordingPolicy.storage(true, false));
        assertEquals("internal", AfmcRecordingPolicy.storage(false, true));
        assertEquals("internal", AfmcRecordingPolicy.storage(false, false));
    }

    // ---------- the reply goes out inside LocalStore's 12 s wait (2026-10-12 audit) ----------

    @Test
    public void theWorstCaseSwitchStillAnswersWellInsideLocalStoresWait() {
        // apply takes its full 3 s, then the recording never starts: the reply goes out at 7 s.
        long apply = AfmcRecordingPolicy.remainingMs(0, 0, AfmcRecordingPolicy.APPLY_WAIT_MS);
        assertEquals(3_000L, apply);
        long startWait = AfmcRecordingPolicy.remainingMs(0, apply, AfmcRecordingPolicy.REPLY_BY_MS);
        assertEquals(4_000L, startWait);
        assertTrue(apply + startWait <= 7_000L);
        assertTrue("LocalStore waits 12 s", AfmcRecordingPolicy.REPLY_BY_MS + 2_000L < 12_000L);
    }

    @Test
    public void nothingIsLeftToWaitPastTheDeadline() {
        assertEquals(0L, AfmcRecordingPolicy.remainingMs(0, 9_000L, AfmcRecordingPolicy.REPLY_BY_MS));
        assertEquals(0L, AfmcRecordingPolicy.remainingMs(0, 7_000L, AfmcRecordingPolicy.APPLY_WAIT_MS));
        assertEquals("a clock that went back counts as no time spent",
                AfmcRecordingPolicy.APPLY_WAIT_MS, AfmcRecordingPolicy.remainingMs(5_000L, 0, AfmcRecordingPolicy.APPLY_WAIT_MS));
    }

    // ---------- once per drive (owner, 2026-10-11) ----------

    private static final long H = 60 * 60_000L;
    private static final long BOOT = 1_000_000_000L;

    @Test
    public void aStopByHandHoldsForTheRestOfTheDriveAcrossRelaunches() {
        // EVCam relaunched 40 minutes later, same head-unit boot, car still in use: no restart.
        assertTrue(AfmcRecordingPolicy.manualStopHolds(true, 0, 40 * 60_000L, BOOT, BOOT + 30_000L, false));
        assertFalse(AfmcRecordingPolicy.manualStopHolds(false, 0, 1, BOOT, BOOT, false));
    }

    @Test
    public void theNextDriveRecordsByItselfAgain() {
        // the car was left or switched off since the stop
        assertFalse(AfmcRecordingPolicy.manualStopHolds(true, 0, 60_000L, BOOT, BOOT, true));
        // the head unit started again
        assertFalse(AfmcRecordingPolicy.manualStopHolds(true, 0, 60_000L, BOOT, BOOT + 10 * 60_000L, false));
        // safety net
        assertFalse(AfmcRecordingPolicy.manualStopHolds(true, 0, 12 * H, BOOT, BOOT, false));
        assertFalse("a clock that went back", AfmcRecordingPolicy.manualStopHolds(true, 5 * H, 0, BOOT, BOOT, false));
    }

    @Test
    public void onlyLeftOrSleepingIsOutOfUse() {
        assertTrue(AfmcRecordingPolicy.outOfUse(1));
        assertTrue(AfmcRecordingPolicy.outOfUse(0));
        assertFalse(AfmcRecordingPolicy.outOfUse(2));
        assertFalse(AfmcRecordingPolicy.outOfUse(13));
        assertFalse(AfmcRecordingPolicy.outOfUse(null));
    }
}
