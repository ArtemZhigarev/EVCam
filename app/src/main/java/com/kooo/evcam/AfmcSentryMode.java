package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AppsForMyCar fork: sentry mode. Off unless the owner switches it on (Settings → Sentry mode).
 *
 * While it is on and the car is left (usage mode not 2, read by AfmcUsageModeReader), EVCam:
 *  - keeps recording (starts a recording if none is running; MainActivity then also keeps the
 *    cameras and the recording going with the screen off, see holdsCameras());
 *  - every N minutes (Settings, default 5) saves a picture from each outside camera into the
 *    "Sentry" folder next to EVCam's photos, on the same drive as the recordings (the USB stick
 *    when one is plugged in), named <yyyyMMdd_HHmmss>_<front|back|left|right>.jpg;
 *  - when that drive runs short, deletes the oldest sentry pictures first, then the oldest
 *    finished recordings (AfmcSentryPolicy.planCleanup).
 * It stops when the car is in use again; a recording it started itself is then stopped, unless
 * "Record automatically" is on (then recording simply carries on as usual).
 *
 * It never opens a camera of its own and never uses the interior cameras (Camera2 IDs 0 and 1):
 * pictures come from the outside cameras EVCam already has open. It runs only while the head unit
 * is awake; it does nothing to keep the car on (that is the car's own stay-on timer).
 * Logcat tag AfmcSentry.
 */
public final class AfmcSentryMode {
    private static final String TAG = "AfmcSentry";
    static final String PREFS = "afmc_sentry";
    static final String KEY_ENABLED = "sentry_enabled";
    static final String KEY_INTERVAL_MIN = "sentry_interval_min";
    private static final long TICK_MS = 30_000L;
    private static final long RECORD_RETRY_MS = 2 * 60_000L;
    private static final String[] POSITIONS = {"front", "back", "left", "right"};

    /** What sentry needs from the screen that owns the cameras (MainActivity). */
    public interface Recorder {
        boolean isRecording();
        void startRecording();
        void stopRecording();
    }

    private static AfmcSentryMode instance;
    private static volatile boolean active;
    private static String lastClaimedTimestamp = "";

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AfmcUsageModeReader usageReader = new AfmcUsageModeReader();
    private WeakReference<Recorder> recorder = new WeakReference<>(null);
    private boolean started;
    private long activeSinceMs;
    private long lastShotMs = -1;
    private long lastRecordAttemptMs;
    private boolean startedRecording;
    private final Runnable tickRunnable = this::tick;

    private AfmcSentryMode(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("AfmcSentry");
        t.start();
        handler = new Handler(t.getLooper());
    }

    public static synchronized AfmcSentryMode get(Context context) {
        if (instance == null) instance = new AfmcSentryMode(context);
        return instance;
    }

    /**
     * True while sentry is running (car left, setting on): MainActivity then keeps the cameras and
     * the recording going with the screen off, as upstream's "screen-off recording" would.
     */
    public static boolean holdsCameras() {
        return active;
    }

    /**
     * A picture timestamp (yyyyMMdd_HHmmss) no other caller has had: sentry and the LocalStore
     * snapshot receiver both name pictures by the second and pick their files up by that name, so
     * two shots in the same second would take each other's files. Waits for the next second if needed.
     */
    static synchronized String claimTimestamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String ts = f.format(new Date());
        for (int i = 0; i < 12 && ts.equals(lastClaimedTimestamp); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
            ts = f.format(new Date());
        }
        lastClaimedTimestamp = ts;
        return ts;
    }

    // ---------- settings ----------

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static int intervalMinutes(Context context) {
        return AfmcSentryPolicy.normalizeInterval(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_INTERVAL_MIN, AfmcSentryPolicy.DEFAULT_INTERVAL_MIN));
    }

    public static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply();
        AppLog.d(TAG, "sentry mode switched " + (on ? "on" : "off"));
        get(context).start();
        get(context).checkSoon();
    }

    public static void setIntervalMinutes(Context context, int minutes) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_INTERVAL_MIN, AfmcSentryPolicy.normalizeInterval(minutes)).apply();
        get(context).checkSoon();
    }

    // ---------- lifecycle ----------

    /** Called by MainActivity once its cameras exist. Cheap when sentry is off. */
    public void attach(Recorder r) {
        recorder = new WeakReference<>(r);
        start();
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        handler.post(tickRunnable);
    }

    private void checkSoon() {
        handler.removeCallbacks(tickRunnable);
        handler.post(tickRunnable);
    }

    private void tick() {
        handler.removeCallbacks(tickRunnable);
        try {
            step();
        } catch (Throwable t) {
            AppLog.e(TAG, "sentry step failed: " + t);
        }
        handler.postDelayed(tickRunnable, nextTickMs());
    }

    private long nextTickMs() {
        if (!active) return TICK_MS;
        long untilShot = AfmcSentryPolicy.nextSnapshotDelayMs(SystemClock.elapsedRealtime(),
                activeSinceMs, lastShotMs, intervalMinutes(app));
        return Math.max(1_000L, Math.min(TICK_MS, untilShot));
    }

    private void step() {
        boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        if (enabled) usageReader.start(); else usageReader.stop();

        long now = SystemClock.elapsedRealtime();
        Integer usage = AfmcSentryPolicy.freshUsage(usageReader.usage(), usageReader.readAtMs(), now);
        switch (AfmcSentryPolicy.decide(enabled, usage, active)) {
            case START:
                AppLog.d(TAG, "car left (usage mode " + usage + "): sentry on, pictures every "
                        + intervalMinutes(app) + " min");
                active = true;
                activeSinceMs = now;
                lastShotMs = -1;
                lastRecordAttemptMs = 0;
                startedRecording = false;
                break;
            case STOP:
                AppLog.d(TAG, "sentry off (" + (enabled ? "car in use" : "switched off") + ")");
                active = false;
                stopOwnRecording();
                return;
            case NONE:
                break;
        }
        if (!active) return;

        keepRecording(now);
        cleanUpIfShort();
        if (AfmcSentryPolicy.nextSnapshotDelayMs(now, activeSinceMs, lastShotMs, intervalMinutes(app)) == 0) {
            lastShotMs = now;
            takeSentryPictures();
        }
    }

    // ---------- recording ----------

    private void keepRecording(long now) {
        Recorder r = recorder.get();
        if (r != null && r.isRecording()) return;
        MultiCameraManager m = currentManager();
        if (r == null && m != null && m.isRecording()) return;
        if (lastRecordAttemptMs != 0 && now - lastRecordAttemptMs < RECORD_RETRY_MS) return;
        lastRecordAttemptMs = now;
        startedRecording = true;
        if (r != null) {
            AppLog.d(TAG, "not recording: starting a recording");
            main.post(r::startRecording);
        } else {
            // EVCam's screen isn't running: start it the way the floating record button does.
            AppLog.d(TAG, "not recording and EVCam's screen isn't running: starting it to record");
            try {
                Intent i = new Intent(app, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("auto_start_recording", true);
                app.startActivity(i);
            } catch (Exception e) {
                AppLog.e(TAG, "could not start EVCam to record: " + e.getMessage());
            }
        }
    }

    private void stopOwnRecording() {
        if (!startedRecording) return;
        startedRecording = false;
        if (new AppConfig(app).isAutoStartRecording()) return;  // recording carries on as usual
        Recorder r = recorder.get();
        if (r != null) {
            AppLog.d(TAG, "stopping the recording sentry started");
            main.post(() -> {
                if (r.isRecording()) r.stopRecording();
            });
        }
    }

    // ---------- pictures ----------

    /**
     * One still per outside camera, taken from that camera's newest recorded clip. EVCam's own
     * take-picture copies the on-screen preview, which is black while EVCam isn't on screen (seen on
     * the EX2 2026-10-07: 36 sentry pictures, all black) — the clips have the real picture, at most a
     * clip-length old. Runs off the main thread; never touches the cameras.
     */
    private void takeSentryPictures() {
        final String ts = claimTimestamp();
        new Thread(() -> {
            File videoDir = StorageHelper.getFinalVideoDir(app);
            File sentryDir = AfmcSentryPolicy.sentryDir(StorageHelper.getPhotoDir(app));
            if (!sentryDir.exists() && !sentryDir.mkdirs()) {
                AppLog.e(TAG, "cannot create " + sentryDir);
                return;
            }
            File[] files = videoDir.listFiles();
            MultiCameraManager m = currentManager();
            List<String> saved = new ArrayList<>();
            for (String position : POSITIONS) {
                SingleCamera cam = m != null ? m.getCamera(position) : null;
                if (cam != null && !AfmcSentryPolicy.isOutsideCamera(position, cam.getCameraId())) {
                    AppLog.w(TAG, "camera " + cam.getCameraId() + " at '" + position + "' is not an outside camera: skipped");
                    continue;
                }
                for (File clip : AfmcSentryPolicy.clipsFor(files, position)) {
                    if (frameFrom(clip, new File(sentryDir, ts + "_" + position + ".jpg"))) {
                        saved.add(position);
                        break;
                    }
                }
            }
            AppLog.d(TAG, "sentry picture " + ts + ": " + saved + " saved in " + sentryDir);
        }, "AfmcSentryPicture").start();
    }

    /** The last frame near the end of [clip] as a JPEG; false when the clip can't be read (still being written). */
    private static boolean frameFrom(File clip, File out) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(clip.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = d == null ? 0 : Long.parseLong(d);
            if (durationMs <= 0) return false;
            Bitmap frame = r.getFrameAtTime(Math.max(0, durationMs - 500) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return false;
            try (FileOutputStream fo = new FileOutputStream(out)) {
                frame.compress(Bitmap.CompressFormat.JPEG, 85, fo);
            } finally {
                frame.recycle();
            }
            return out.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private static MultiCameraManager currentManager() {
        return AfmcSnapshotReceiver.currentManager();
    }

    // ---------- space ----------

    private void cleanUpIfShort() {
        File photoDir = StorageHelper.getPhotoDir(app);
        File sentryDir = AfmcSentryPolicy.sentryDir(photoDir);
        long free = StorageHelper.getAvailableSpace(photoDir);
        long total = StorageHelper.getTotalSpace(photoDir);
        if (free < 0 || free >= AfmcSentryPolicy.lowWaterBytes(total)) return;

        List<AfmcSentryPolicy.Entry> sentry = new ArrayList<>();
        list(sentryDir, sentry, false);
        List<AfmcSentryPolicy.Entry> videos = new ArrayList<>();
        File videoDir = StorageHelper.getFinalVideoDir(app);
        // Recordings are deleted only when they are on the same drive as the sentry pictures.
        if (sameDrive(videoDir, photoDir)) list(videoDir, videos, true);

        List<AfmcSentryPolicy.Entry> plan = AfmcSentryPolicy.planCleanup(sentry, videos, free, total,
                System.currentTimeMillis());
        int deleted = 0;
        long bytes = 0;
        for (AfmcSentryPolicy.Entry e : plan) {
            if (new File(e.path).delete()) {
                deleted++;
                bytes += e.size;
            }
        }
        AppLog.d(TAG, "drive short of space (" + StorageHelper.formatSize(free) + " free): deleted "
                + deleted + " file(s), " + StorageHelper.formatSize(bytes));
    }

    private static void list(File dir, List<AfmcSentryPolicy.Entry> out, boolean oneLevelDown) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) out.add(new AfmcSentryPolicy.Entry(f.getAbsolutePath(), f.length(), f.lastModified()));
            else if (oneLevelDown && f.isDirectory()) list(f, out, false);
        }
    }

    private static boolean sameDrive(File a, File b) {
        String pa = a.getAbsolutePath();
        String pb = b.getAbsolutePath();
        String[] sa = pa.split("/");
        String[] sb = pb.split("/");
        // /storage/<volume>/... : the first three parts name the volume.
        if (sa.length < 3 || sb.length < 3) return false;
        return sa[1].equals(sb[1]) && sa[2].equals(sb[2]) && (!"emulated".equals(sa[2]) || sa.length > 3 && sb.length > 3 && sa[3].equals(sb[3]));
    }
}
