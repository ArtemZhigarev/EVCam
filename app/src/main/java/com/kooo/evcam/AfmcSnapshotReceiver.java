package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * AppsForMyCar fork: lets LocalStore get pictures from the cameras EVCam already has open, so a
 * snapshot asked for from the owner's account works while EVCam is recording.
 *
 * Only apps signed with the AppsForMyCar key can call this (permission org.ex2.permission.CAR_SNAPSHOT,
 * protectionLevel signature). It runs EVCam's own take-picture on every active camera, waits for the
 * files (<photo dir>/<yyyyMMdd_HHmmss>_<front|back|left|right>.jpg) and replies to LocalStore with
 * their paths.
 *
 *   in:  org.ex2.evcam.TAKE_SNAPSHOT        extras: requestId (String)
 *   out: org.ex2.localstore.EVCAM_SNAPSHOT  extras: requestId, paths (String[]), error (String or null)
 */
public class AfmcSnapshotReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.ex2.evcam.TAKE_SNAPSHOT";
    public static final String RESULT_ACTION = "org.ex2.localstore.EVCAM_SNAPSHOT";
    public static final String PERMISSION = "org.ex2.permission.CAR_SNAPSHOT";
    private static final String TAG = "AfmcSnapshot";

    private static WeakReference<MultiCameraManager> manager = new WeakReference<>(null);

    /** Called by MainActivity when it creates its camera manager. */
    public static void setCameraManager(MultiCameraManager m) {
        manager = new WeakReference<>(m);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String requestId = intent.getStringExtra("requestId");
        final Context app = context.getApplicationContext();
        final MultiCameraManager m = currentManager();
        if (m == null) {
            reply(app, requestId, new String[0], "EVCam isn't running its cameras");
            return;
        }

        final PendingResult pending = goAsync();
        // Never the same second as a sentry picture: both pick their files up by this name.
        final String timestamp = AfmcSentryMode.claimTimestamp();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                m.takePicture(timestamp);
            } catch (Throwable t) {
                AppLog.e(TAG, "takePicture failed: " + t);
            }
        });

        // Pictures are saved one camera per second; collect what's there after up to ~10 s.
        new Thread(() -> {
            ArrayList<String> paths = new ArrayList<>();
            try {
                File dir = StorageHelper.getPhotoDir(app);
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(500);
                    paths.clear();
                    File[] files = dir.listFiles((d, name) -> name.startsWith(timestamp + "_") && name.endsWith(".jpg"));
                    if (files != null) for (File f : files) if (f.length() > 0) paths.add(f.getAbsolutePath());
                    if (paths.size() >= 4 && i >= 6) break;
                }
                reply(app, requestId, paths.toArray(new String[0]), paths.isEmpty() ? "No pictures were saved" : null);
            } catch (Throwable t) {
                reply(app, requestId, paths.toArray(new String[0]), "Error: " + t.getMessage());
            } finally {
                pending.finish();
            }
        }).start();
    }

    /**
     * The camera manager MainActivity registered, or — when a background service created it first
     * (MainActivity then reuses that one) or the Activity was recreated — the one in upstream's
     * process-wide CameraManagerHolder.
     */
    static MultiCameraManager currentManager() {
        MultiCameraManager m = manager.get();
        if (m == null || m.isReleased()) {
            m = CameraManagerHolder.getInstance().getCameraManager();
        }
        return m == null || m.isReleased() ? null : m;
    }

    private static void reply(Context context, String requestId, String[] paths, String error) {
        Intent out = new Intent(RESULT_ACTION)
                .setPackage("org.ex2.localstore")
                .putExtra("requestId", requestId)
                .putExtra("paths", paths)
                .putExtra("error", error);
        context.sendBroadcast(out, PERMISSION);
        AppLog.d(TAG, "snapshot " + requestId + ": " + paths.length + " picture(s)" + (error != null ? ", " + error : ""));
    }
}
