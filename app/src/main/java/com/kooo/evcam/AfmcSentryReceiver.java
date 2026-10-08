package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * AppsForMyCar fork: lets LocalStore switch sentry mode on or off for the owner (the "Sentry mode"
 * switch on the account's Control page), the same as the switch in EVCam's own settings.
 *
 * Only apps signed with the AppsForMyCar key can call this (permission org.ex2.permission.CAR_SNAPSHOT,
 * protectionLevel signature, shared with the snapshot request).
 *
 *   in:  org.ex2.evcam.SET_SENTRY       extras: requestId (String), enabled (boolean), intervalMin (int, optional)
 *   out: org.ex2.localstore.EVCAM_SENTRY extras: requestId, enabled, intervalMin, error (String or null),
 *                                         state (String), stateText (String)  [both added after 1.6.6-afmc.12]
 *
 * state is what sentry is doing at the moment of the reply:
 *   armed       the car is left (locked, or usage mode 1/0): recording and taking pictures
 *   waiting     on, but the car is in use or unlocked; it arms by itself when the car is left
 *   no_signals  on, but EVCam can't read the car's signals (vehicle HAL on :40004), so it can't
 *               tell whether the car is left and won't arm; it keeps retrying by itself
 *   off         switched off
 * stateText is one plain-English line for the owner. When switching on, the reply waits up to
 * about 6 s for the first reading of the car's signals, so a healthy car doesn't report no_signals.
 * error stays for failures of the switch itself; no_signals is not one (the switch did happen).
 */
public class AfmcSentryReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.ex2.evcam.SET_SENTRY";
    public static final String RESULT_ACTION = "org.ex2.localstore.EVCAM_SENTRY";
    private static final String TAG = "AfmcSentry";
    private static final long FIRST_READING_WAIT_MS = 6_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                handle(app, intent);
            } catch (Throwable t) {
                AppLog.e(TAG, "remote sentry switch failed: " + t);
            } finally {
                try {
                    pending.finish();
                } catch (Throwable ignored) {
                }
            }
        }, "AfmcSentrySwitch").start();
    }

    private static void handle(Context app, Intent intent) {
        String requestId = intent.getStringExtra("requestId");
        String error = null;
        try {
            if (!intent.hasExtra("enabled")) {
                error = "Say on or off";
            } else {
                if (intent.hasExtra("intervalMin")) {
                    AfmcSentryMode.setIntervalMinutes(app, intent.getIntExtra("intervalMin", AfmcSentryPolicy.DEFAULT_INTERVAL_MIN));
                }
                // Same call as the switch in EVCam's settings: saves it and starts or stops sentry.
                AfmcSentryMode.setEnabled(app, intent.getBooleanExtra("enabled", false));
            }
        } catch (Throwable t) {
            error = "Error: " + t.getMessage();
            AppLog.e(TAG, "remote sentry switch failed: " + t);
        }
        AfmcSentryPolicy.State state = waitForReading(app);
        Intent out = new Intent(RESULT_ACTION)
                .setPackage("org.ex2.localstore")
                .putExtra("requestId", requestId)
                .putExtra("enabled", AfmcSentryMode.isEnabled(app))
                .putExtra("intervalMin", AfmcSentryMode.intervalMinutes(app))
                .putExtra("error", error)
                .putExtra("state", state.wire)
                .putExtra("stateText", AfmcSentryPolicy.stateText(state));
        app.sendBroadcast(out, AfmcSnapshotReceiver.PERMISSION);
        AppLog.d(TAG, "remote switch " + requestId + ": sentry " + (AfmcSentryMode.isEnabled(app) ? "on" : "off")
                + ", every " + AfmcSentryMode.intervalMinutes(app) + " min, state " + state.wire
                + (error != null ? ", " + error : ""));
        if (state == AfmcSentryPolicy.State.NO_SIGNALS) {
            AppLog.w(TAG, AfmcSentryPolicy.stateText(state));
        }
    }

    private static AfmcSentryPolicy.State waitForReading(Context app) {
        long until = System.currentTimeMillis() + FIRST_READING_WAIT_MS;
        AfmcSentryPolicy.State s = AfmcSentryMode.state(app);
        while (s == AfmcSentryPolicy.State.NO_SIGNALS && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
            s = AfmcSentryMode.state(app);
        }
        return s;
    }
}
