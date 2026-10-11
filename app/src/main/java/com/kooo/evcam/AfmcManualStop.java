package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * AppsForMyCar fork: remembers a recording the driver stopped by hand, for the rest of this drive
 * (AfmcRecordingPolicy.manualStopHolds). Kept in preferences, so it outlasts EVCam's screen being
 * rebuilt or its process being restarted by the keep-alive service; upstream kept it only in
 * MainActivity's memory, and a relaunch started recording again.
 *
 * While a stop is held, a read-only AfmcUsageModeReader follows the car's usage mode; the car being
 * left or switched off ends the stop, so the next drive records by itself again.
 */
final class AfmcManualStop {
    private static final String TAG = "AfmcManualStop";
    private static final String PREFS = "afmc_manual_stop";
    private static final String KEY_HELD = "held";
    private static final String KEY_AT = "stopped_at";
    private static final String KEY_BOOT = "boot_at";
    private static final String KEY_LEFT = "left_since";
    private static final long WATCH_EVERY_MS = 5_000L;

    private static AfmcUsageModeReader reader;
    private static Thread watcher;

    private AfmcManualStop() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static long bootAtMs() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /** The driver stopped the recording by hand. */
    static synchronized void mark(Context c) {
        prefs(c).edit().putBoolean(KEY_HELD, true).putLong(KEY_AT, System.currentTimeMillis())
                .putLong(KEY_BOOT, bootAtMs()).putBoolean(KEY_LEFT, false).apply();
        AppLog.d(TAG, "AFMC: recording stopped by hand: not started again by itself until the next drive");
        watch(c);
    }

    /** The driver (or LocalStore's switch) asked for recording: the stop is over. */
    static synchronized void clear(Context c, String why) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(KEY_HELD, false)) AppLog.d(TAG, "AFMC: stop by hand over: " + why);
        p.edit().putBoolean(KEY_HELD, false).apply();
        unwatch();
    }

    /** Whether a stop by hand still holds now. Ends it (and says why) once a new drive has begun. */
    static synchronized boolean holds(Context c) {
        SharedPreferences p = prefs(c);
        boolean held = p.getBoolean(KEY_HELD, false);
        if (!held) { unwatch(); return false; }
        boolean h = AfmcRecordingPolicy.manualStopHolds(true, p.getLong(KEY_AT, 0), System.currentTimeMillis(),
                p.getLong(KEY_BOOT, 0), bootAtMs(), p.getBoolean(KEY_LEFT, false));
        if (!h) {
            clear(c, p.getBoolean(KEY_LEFT, false) ? "the car was left or switched off since"
                    : "a new start of the head unit, or 12 h passed");
            return false;
        }
        watch(c);
        return true;
    }

    private static void watch(Context c) {
        if (watcher != null) return;
        final Context app = c.getApplicationContext();
        reader = new AfmcUsageModeReader();
        reader.start();
        final AfmcUsageModeReader r = reader;
        watcher = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (AfmcRecordingPolicy.outOfUse(r.usage())) {
                        prefs(app).edit().putBoolean(KEY_LEFT, true).apply();
                        AppLog.d(TAG, "AFMC: the car was left (usage mode " + r.usage() + "): the next drive records by itself");
                        synchronized (AfmcManualStop.class) { if (reader == r) unwatch(); }
                        return;
                    }
                    Thread.sleep(WATCH_EVERY_MS);
                }
            } catch (InterruptedException ignored) {
            }
        }, "afmc-manual-stop");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void unwatch() {
        if (reader != null) { try { reader.stop(); } catch (Exception ignored) {} }
        if (watcher != null && watcher != Thread.currentThread()) watcher.interrupt();
        reader = null;
        watcher = null;
    }
}
