package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.kooo.evcam.camera.MultiCameraManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AppsForMyCar fork: lets LocalStore read and switch "Record while driving" (the dashcam), which is
 * upstream's "Record automatically" setting (AppConfig auto_start_recording) — the same switch as in
 * EVCam's settings, but it takes effect at once (AfmcRecordingPolicy): switched on while the car is in
 * use, EVCam starts recording now; switched off, it stops the recording.
 *
 * Only apps signed with the AppsForMyCar key can call this (permission org.ex2.permission.CAR_SNAPSHOT,
 * protectionLevel signature, shared with the snapshot and sentry requests).
 *
 *   in:  org.ex2.evcam.SET_RECORDING        extras: requestId (String), enabled (boolean, optional:
 *                                            without it the request only reports the state)
 *   out: org.ex2.localstore.EVCAM_RECORDING extras: requestId, enabled (the saved setting),
 *                                            recordingNow (boolean), storage ("usb" | "internal"),
 *                                            error (String or null)
 *
 * "In use" is the screen being on, which is how upstream decides too (it stops recording 10 s after
 * the screen goes off unless screen-off recording is on). When switching on, the reply waits up to
 * about 8 s for the recording to start, so recordingNow is true when it worked.
 */
public class AfmcRecordingReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.ex2.evcam.SET_RECORDING";
    public static final String RESULT_ACTION = "org.ex2.localstore.EVCAM_RECORDING";
    private static final String TAG = "AfmcRecording";
    private static final long START_WAIT_MS = 8_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                handle(app, intent);
            } catch (Throwable t) {
                AppLog.e(TAG, "record while driving request failed: " + t);
            } finally {
                try {
                    pending.finish();
                } catch (Throwable ignored) {
                }
            }
        }, "AfmcRecordingSwitch").start();
    }

    private static void handle(Context app, Intent intent) {
        String requestId = intent.getStringExtra("requestId");
        String error = null;
        boolean expectRecording = false;
        AppConfig config = new AppConfig(app);
        try {
            if (intent.hasExtra("enabled")) {
                boolean on = intent.getBooleanExtra("enabled", false);
                boolean wasOn = config.isAutoStartRecording();
                // Same call as the switch in EVCam's settings: saves it.
                config.setAutoStartRecording(on);
                boolean inUse = carInUse(app);
                apply(app, wasOn, on, inUse);
                expectRecording = on && inUse && !AfmcSentryMode.holdsCameras();
            }
        } catch (Throwable t) {
            error = "Error: " + t.getMessage();
            AppLog.e(TAG, "record while driving switch failed: " + t);
        }
        boolean recording = expectRecording ? waitForRecording() : recordingNow();
        String storage;
        try {
            storage = AfmcRecordingPolicy.storage(config.isUsingExternalSdCard(), StorageHelper.hasExternalSdCard(app));
        } catch (Throwable t) {
            storage = AfmcRecordingPolicy.STORAGE_INTERNAL;
        }
        boolean enabled = config.isAutoStartRecording();
        Intent out = new Intent(RESULT_ACTION)
                .setPackage("org.ex2.localstore")
                .putExtra("requestId", requestId)
                .putExtra("enabled", enabled)
                .putExtra("recordingNow", recording)
                .putExtra("storage", storage)
                .putExtra("error", error);
        app.sendBroadcast(out, AfmcSnapshotReceiver.PERMISSION);
        AppLog.d(TAG, (intent.hasExtra("enabled") ? "remote switch " : "remote query ") + requestId
                + ": record while driving " + (enabled ? "on" : "off") + ", recording now " + recording
                + ", to " + storage + (error != null ? ", " + error : ""));
    }

    /** Starts or stops a recording on EVCam's screen, or starts EVCam to record when it isn't running. */
    private static void apply(Context app, boolean wasOn, boolean on, boolean inUse) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                MainActivity activity = MainActivity.getInstance();
                if (activity != null) {
                    activity.afmcRecordWhileDrivingSwitched(wasOn, on, inUse);
                } else if (AfmcRecordingPolicy.decide(wasOn, on, inUse, AfmcSentryMode.holdsCameras(), false)
                        == AfmcRecordingPolicy.Action.START) {
                    // EVCam's screen isn't running: start it the way its foreground service does for
                    // automatic recording; it records and then goes to the background by itself.
                    AppLog.d(TAG, "EVCam's screen isn't running: starting it to record");
                    Intent i = new Intent(app, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            .putExtra("auto_start_from_boot", true)
                            .putExtra("silent_mode", true)
                            .putExtra("from_service_restart", true);
                    app.startActivity(i);
                }
            } catch (Throwable t) {
                AppLog.e(TAG, "applying record while driving failed: " + t);
            } finally {
                done.countDown();
            }
        });
        done.await(3, TimeUnit.SECONDS);
    }

    private static boolean carInUse(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean waitForRecording() {
        long until = System.currentTimeMillis() + START_WAIT_MS;
        boolean r = recordingNow();
        while (!r && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
            r = recordingNow();
        }
        return r;
    }

    static boolean recordingNow() {
        try {
            MainActivity activity = MainActivity.getInstance();
            if (activity != null && activity.afmcIsRecording()) return true;
            MultiCameraManager m = AfmcSnapshotReceiver.currentManager();
            return m != null && m.isRecording();
        } catch (Throwable t) {
            return false;
        }
    }
}
