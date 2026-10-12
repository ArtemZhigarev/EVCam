package com.kooo.evcam;

/**
 * AppsForMyCar fork: the decisions behind "Record while driving" switched from LocalStore
 * (AfmcRecordingReceiver). No Android in here, so it can be unit-tested.
 *
 * "Record while driving" is upstream's "Record automatically" setting (AppConfig auto_start_recording).
 * Upstream only acts on it the next time EVCam starts; switched from LocalStore it also takes effect
 * at once:
 *  - switched on while the car is in use (screen on) and EVCam isn't recording: start recording now,
 *    even if the driver had stopped a recording by hand in EVCam (switching on is asking for it);
 *  - switched off while it was on and EVCam is recording: stop that recording;
 *  - never touches a recording sentry mode is running (the car is left; sentry stops it itself);
 *  - a repeated "off" when it was already off leaves a recording the driver started by hand alone.
 */
final class AfmcRecordingPolicy {
    enum Action { START, STOP, NONE }

    static final String STORAGE_USB = "usb";
    static final String STORAGE_INTERNAL = "internal";

    private AfmcRecordingPolicy() {}

    static Action decide(boolean wasOn, boolean on, boolean carInUse, boolean sentryHoldsCameras, boolean recording) {
        if (sentryHoldsCameras) return Action.NONE;
        if (on) return carInUse && !recording ? Action.START : Action.NONE;
        return wasOn && recording ? Action.STOP : Action.NONE;
    }

    /**
     * Where the clips go: the USB stick when the storage setting says USB and a stick is in, else
     * the head unit's own storage (upstream falls back to it when no stick is found).
     */
    static String storage(boolean usbChosen, boolean usbPresent) {
        return usbChosen && usbPresent ? STORAGE_USB : STORAGE_INTERNAL;
    }

    /**
     * The reply to LocalStore goes out at most this long after the request arrived, whatever is still
     * pending. LocalStore waits 12 s (EvcamRecording.SWITCH_TIMEOUT_MS); before 2026-10-12 a switch
     * could take up to 3 s to apply plus 8 s waiting for the recording, so a slow but successful switch
     * reached LocalStore after it had given up and was shown as "needs a newer EVCam". A recording
     * still starting at the deadline is reported as starting (the reply's "starting" extra).
     */
    static final long REPLY_BY_MS = 7_000L;
    /** The longest the switch itself may take on EVCam's main thread before waiting for the recording. */
    static final long APPLY_WAIT_MS = 3_000L;

    /** How long to wait for something ([wantMs] at most) so the reply still goes out by [REPLY_BY_MS]. Never negative. */
    static long remainingMs(long receivedAtMs, long nowMs, long wantMs) {
        long left = REPLY_BY_MS - Math.max(0L, nowMs - receivedAtMs);
        return Math.max(0L, Math.min(wantMs, left));
    }

    /** A stop made by hand lasts at most this long, whatever else is known (a safety net). */
    static final long MANUAL_STOP_MAX_MS = 12 * 60 * 60_000L;
    /** Two boot times this close are the same boot (the wall clock drifts against the uptime). */
    static final long SAME_BOOT_SLACK_MS = 2 * 60_000L;

    /**
     * Recording starts by itself once per drive (owner, 2026-10-11): a recording the driver stopped by
     * hand is not started again by EVCam (its start, a relaunch, the screen coming back, returning from
     * another app, the once-a-minute check) until the next drive. A new drive is the car having been
     * left or switched off since the stop ([leftSince], usage mode 1 or 0), or the head unit having
     * restarted ([bootAtMs] differs from [savedBootAtMs]). [MANUAL_STOP_MAX_MS] ends it in any case.
     * The driver starting a recording, or switching "Record while driving" on from LocalStore, ends
     * it at once (the caller clears it).
     */
    static boolean manualStopHolds(boolean held, long stoppedAtWallMs, long nowWallMs,
                                   long savedBootAtMs, long bootAtMs, boolean leftSince) {
        if (!held || leftSince) return false;
        if (Math.abs(bootAtMs - savedBootAtMs) > SAME_BOOT_SLACK_MS) return false;
        long age = nowWallMs - stoppedAtWallMs;
        return age >= 0 && age < MANUAL_STOP_MAX_MS;
    }

    /** Usage mode 0x21408030: 1 the car was left, 0 it is going to sleep; 2 in use and 13 driving are not. */
    static boolean outOfUse(Integer usage) {
        return usage != null && (usage == 0 || usage == 1);
    }
}
