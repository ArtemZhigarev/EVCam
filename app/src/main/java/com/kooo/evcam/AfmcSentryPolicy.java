package com.kooo.evcam;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * AppsForMyCar fork: the decisions behind sentry mode, kept free of Android so they can be unit
 * tested (see AfmcSentryPolicyTest). AfmcSentryMode does the work.
 *
 * Sentry mode (off unless the owner switches it on in Settings): while the car is left (its usage
 * mode is anything but 2, "in use") EVCam keeps recording, and every few minutes saves a picture
 * from each outside camera into a "Sentry" folder next to EVCam's photos (same drive as the
 * recordings: the USB stick when one is plugged in). When the drive runs short, the oldest sentry
 * pictures go first, then the oldest finished recordings.
 */
final class AfmcSentryPolicy {
    private AfmcSentryPolicy() {}

    /** The car's usage mode property (0x21408030): 2 in use, 1 left, 0 going to sleep. */
    static final int USAGE_MODE_PROP = 0x21408030;
    /** Central lock: 3 locked, 1 unlocked. */
    static final int LOCK_PROP = 0x21408033;
    static final int LOCKED = 3;
    /** The car's camping mode flag (its own camping scene sets it): 1 on. */
    static final int CAMPING_PROP = 0x21207626;
    static final int USAGE_IN_USE = 2;
    static final int USAGE_LEFT = 1;
    static final int USAGE_SLEEPING = 0;

    /** Snapshot interval choices in Settings, in minutes. */
    static final int[] INTERVAL_CHOICES_MIN = {1, 2, 5, 10, 15};
    static final int DEFAULT_INTERVAL_MIN = 5;

    /** Folder for sentry pictures, next to EVCam's photo folder (so on the same drive). */
    static final String SENTRY_DIR_NAME = "Sentry";

    /** First picture this long after sentry starts, so the cameras and recording have settled. */
    static final long FIRST_SNAPSHOT_DELAY_MS = 15_000L;

    /** A usage-mode reading older than this is not trusted to start or stop anything. */
    static final long USAGE_STALE_MS = 10 * 60_000L;

    /** Files written this recently may still be open (the clip being recorded): never deleted. */
    static final long IN_PROGRESS_MS = 3 * 60_000L;

    static final long GB = 1024L * 1024L * 1024L;

    enum Action { START, STOP, NONE }

    /**
     * What sentry is doing, as reported to LocalStore (the wire name is the lower-case one):
     * ARMED recording because the car is left; WAITING on, but the car is in use or unlocked;
     * NO_SIGNALS on, but the car's signals can't be read, so it can't tell; OFF switched off.
     */
    enum State {
        OFF("off"), ARMED("armed"), WAITING("waiting"), NO_SIGNALS("no_signals");

        final String wire;

        State(String wire) {
            this.wire = wire;
        }
    }

    /** [usage] as for decide(): the effective, fresh usage mode, or null when it isn't known. */
    static State state(boolean enabled, boolean active, Integer usage) {
        if (!enabled) return State.OFF;
        if (active || usage != null && isLeft(usage)) return State.ARMED;
        if (usage == null) return State.NO_SIGNALS;
        return State.WAITING;
    }

    /** One line for the owner about [s]. */
    static String stateText(State s) {
        switch (s) {
            case ARMED: return "Sentry armed: the car is left, recording";
            case WAITING: return "Sentry waiting: it arms when the car is locked and left";
            case NO_SIGNALS: return "Sentry is on but can't read the car's signals, so it can't arm; it keeps trying";
            default: return "Sentry off";
        }
    }

    /** First retry after a failed vehicle HAL connection, and the most it waits between tries. */
    static final long RETRY_FIRST_MS = 5_000L;
    static final long RETRY_MAX_MS = 60_000L;
    /** While the vehicle HAL stays unreachable, the error is logged at most this often. */
    static final long ERROR_LOG_EVERY_MS = 10 * 60_000L;

    /** Wait before try number [failedTries] + 1 to reach the vehicle HAL: 5 s, doubling, capped at 1 min. */
    static long retryDelayMs(int failedTries) {
        if (failedTries <= 1) return RETRY_FIRST_MS;
        long d = RETRY_FIRST_MS << Math.min(failedTries - 1, 20);
        return Math.min(d, RETRY_MAX_MS);
    }

    /** Whether to log failed try number [failedTries]: the first one, then once every ERROR_LOG_EVERY_MS. */
    static boolean shouldLogFailure(int failedTries, long nowMs, long lastLoggedMs) {
        return failedTries <= 1 || lastLoggedMs <= 0 || nowMs - lastLoggedMs >= ERROR_LOG_EVERY_MS;
    }

    /** The setting's value, or the default when it isn't one of the choices. */
    static int normalizeInterval(int minutes) {
        for (int c : INTERVAL_CHOICES_MIN) if (c == minutes) return minutes;
        return DEFAULT_INTERVAL_MIN;
    }

    /** Index of [minutes] in INTERVAL_CHOICES_MIN (the default's index if it isn't a choice). */
    static int intervalIndex(int minutes) {
        int m = normalizeInterval(minutes);
        for (int i = 0; i < INTERVAL_CHOICES_MIN.length; i++) if (INTERVAL_CHOICES_MIN[i] == m) return i;
        return 0;
    }

    /**
     * Whether sentry should start, stop or stay as it is.
     * [usage] is the car's usage mode, or null when it isn't known (no reading, or a stale one):
     * then nothing changes, except that switching the setting off always stops sentry.
     */
    static Action decide(boolean enabled, Integer usage, boolean active) {
        if (!enabled) return active ? Action.STOP : Action.NONE;
        if (usage == null) return Action.NONE;
        boolean left = isLeft(usage);
        if (left && !active) return Action.START;
        if (!left && active) return Action.STOP;
        return Action.NONE;
    }

    /**
     * Left = 1 (left) or 0 (going to sleep) only. Anything else counts as in use: 2 parked with someone
     * there, 13 while driving (seen on the EX2 2026-10-07: sentry took 13 for "left" and kept restarting
     * the recording and bringing EVCam forward during a drive), and any value we haven't seen.
     */
    static boolean isLeft(int usage) {
        return usage == USAGE_LEFT || usage == USAGE_SLEEPING;
    }

    /**
     * The usage mode sentry acts on. Camping mode keeps the car "in use" (2) even when the owner has
     * locked it and walked away (EX2 2026-10-07): locked + camping counts as left, so sentry records
     * while the car is held on that way.
     */
    static Integer effectiveUsage(Integer usage, Integer lock, Integer camping) {
        if (usage != null && usage == USAGE_IN_USE && lock != null && lock == LOCKED && camping != null && camping == 1) return USAGE_LEFT;
        return usage;
    }

    static Integer freshUsage(Integer usage, long readAtMs, long nowMs) {
        if (usage == null || readAtMs <= 0 || nowMs - readAtMs > USAGE_STALE_MS) return null;
        return usage;
    }

    /**
     * Milliseconds until the next picture. [lastShotMs] is when the last one was taken (elapsed
     * time), or -1 when none has been taken since sentry started at [activeSinceMs].
     */
    static long nextSnapshotDelayMs(long nowMs, long activeSinceMs, long lastShotMs, int intervalMin) {
        long due = lastShotMs < 0
                ? activeSinceMs + FIRST_SNAPSHOT_DELAY_MS
                : lastShotMs + normalizeInterval(intervalMin) * 60_000L;
        return Math.max(0L, due - nowMs);
    }

    /** The sentry folder: a sibling of EVCam's photo folder, e.g. <stick>/DCIM/Sentry. */
    /**
     * Clips to take a sentry picture from, for one camera position: its .mp4 files, newest first.
     * The newest is often still being written (no index yet, can't be read), so the caller tries the
     * next one when it fails. Names are EVCam's own: <yyyyMMdd_HHmmss>_<position>.mp4.
     */
    static List<File> clipsFor(File[] files, String position) {
        List<File> out = new ArrayList<>();
        if (files == null) return out;
        String suffix = "_" + position + ".mp4";
        for (File f : files) {
            if (f != null && f.getName().endsWith(suffix) && f.length() > 0) out.add(f);
        }
        Collections.sort(out, Comparator.comparing(File::getName).reversed());
        return out;
    }

    static File sentryDir(File photoDir) {
        File parent = photoDir.getParentFile();
        return parent != null ? new File(parent, SENTRY_DIR_NAME) : new File(photoDir, SENTRY_DIR_NAME);
    }

    /**
     * Whether a picture file belongs to the shot taken at [timestamp]: EVCam names pictures
     * <yyyyMMdd_HHmmss>_<front|back|left|right>.jpg.
     */
    static boolean isShotFile(String name, String timestamp) {
        return name.startsWith(timestamp + "_") && name.endsWith(".jpg");
    }

    /**
     * The cameras sentry may use: the four outside positions only, and never Camera2 IDs 0 and 1
     * (the EX2's driver-monitoring and cabin cameras), whatever the camera setup says.
     */
    static boolean isOutsideCamera(String position, String cameraId) {
        boolean outsidePosition = "front".equals(position) || "back".equals(position)
                || "left".equals(position) || "right".equals(position);
        return outsidePosition && cameraId != null && !"0".equals(cameraId) && !"1".equals(cameraId);
    }

    /** Free space below which sentry starts deleting: 1.5 GB, or 10% of a drive smaller than 15 GB. */
    static long lowWaterBytes(long totalBytes) {
        long fixed = 3 * GB / 2;
        if (totalBytes <= 0) return fixed;
        return Math.min(fixed, totalBytes / 10);
    }

    /** Free space sentry deletes up to once it has started: twice the low-water mark. */
    static long targetFreeBytes(long totalBytes) {
        return lowWaterBytes(totalBytes) * 2;
    }

    /** A file on the drive, as far as cleanup is concerned. */
    static final class Entry {
        final String path;
        final long size;
        final long modifiedMs;

        Entry(String path, long size, long modifiedMs) {
            this.path = path;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /**
     * What to delete when the drive is short of space: nothing while [freeBytes] is at or above the
     * low-water mark; otherwise the oldest sentry pictures first, then the oldest recordings, until
     * the target is reached. Files changed in the last IN_PROGRESS_MS are never picked.
     */
    static List<Entry> planCleanup(List<Entry> sentryFiles, List<Entry> videoFiles,
                                   long freeBytes, long totalBytes, long nowMs) {
        List<Entry> out = new ArrayList<>();
        if (freeBytes < 0 || freeBytes >= lowWaterBytes(totalBytes)) return out;
        long need = targetFreeBytes(totalBytes) - freeBytes;
        need = pickOldest(sentryFiles, need, nowMs, out);
        if (need > 0) pickOldest(videoFiles, need, nowMs, out);
        return out;
    }

    private static long pickOldest(List<Entry> files, long need, long nowMs, List<Entry> out) {
        List<Entry> sorted = new ArrayList<>(files);
        Collections.sort(sorted, Comparator.<Entry>comparingLong(e -> e.modifiedMs).thenComparing(e -> e.path));
        for (Entry e : sorted) {
            if (need <= 0) break;
            if (nowMs - e.modifiedMs < IN_PROGRESS_MS) continue;
            out.add(e);
            need -= e.size;
        }
        return need;
    }
}
