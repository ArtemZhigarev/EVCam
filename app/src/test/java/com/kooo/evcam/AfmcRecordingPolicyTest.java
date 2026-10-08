package com.kooo.evcam;

import org.junit.Test;

import static com.kooo.evcam.AfmcRecordingPolicy.Action.NONE;
import static com.kooo.evcam.AfmcRecordingPolicy.Action.START;
import static com.kooo.evcam.AfmcRecordingPolicy.Action.STOP;
import static org.junit.Assert.assertEquals;

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
}
