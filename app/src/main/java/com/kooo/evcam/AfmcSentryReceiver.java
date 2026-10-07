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
 *   out: org.ex2.localstore.EVCAM_SENTRY extras: requestId, enabled, intervalMin, error (String or null)
 */
public class AfmcSentryReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.ex2.evcam.SET_SENTRY";
    public static final String RESULT_ACTION = "org.ex2.localstore.EVCAM_SENTRY";
    private static final String TAG = "AfmcSentry";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
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
        Intent out = new Intent(RESULT_ACTION)
                .setPackage("org.ex2.localstore")
                .putExtra("requestId", requestId)
                .putExtra("enabled", AfmcSentryMode.isEnabled(app))
                .putExtra("intervalMin", AfmcSentryMode.intervalMinutes(app))
                .putExtra("error", error);
        app.sendBroadcast(out, AfmcSnapshotReceiver.PERMISSION);
        AppLog.d(TAG, "remote switch " + requestId + ": sentry " + (AfmcSentryMode.isEnabled(app) ? "on" : "off")
                + ", every " + AfmcSentryMode.intervalMinutes(app) + " min" + (error != null ? ", " + error : ""));
    }
}
