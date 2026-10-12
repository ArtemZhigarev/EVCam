package com.kooo.evcam;

/**
 * AppsForMyCar fork (afmc.23): separate storage limits for the head unit's own storage and a USB stick.
 *
 * Upstream has one video and one picture limit, applied to whichever drive EVCam records to. A stick
 * is usually far bigger than the car's storage, and LocalStore moves clips left on the car to the
 * stick, so one limit either fills the car or throws away most of the stick (2026-10-12: 4 GB of
 * moved clips pushed the stick's folder over 10 GB and the hourly clean-up deleted them).
 *
 * Now: the upstream limits ("On the car") apply to the car's own DCIM folders, always. The stick has
 * its own limits (blank or 0 = use the whole stick). Whatever the limits, the stick always keeps
 * [lowWaterBytes] free: once it is below that, the oldest clips are deleted up to [targetFreeBytes].
 * Kept free of Android so it can be unit tested.
 */
public final class AfmcStorageLimits {
    static final long GB = 1024L * 1024L * 1024L;

    /** How often the stick's free space is looked at (a cheap StatFs; files are listed only when short). */
    static final long STICK_CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    private AfmcStorageLimits() {}

    /** Free space a stick always keeps: 2 GB, or 5% of a stick bigger than 40 GB. */
    static long lowWaterBytes(long totalBytes) {
        return Math.max(2 * GB, totalBytes / 20);
    }

    /** Free space deleting goes up to once it has started: 2 GB above the low-water mark. */
    static long targetFreeBytes(long totalBytes) {
        return lowWaterBytes(totalBytes) + 2 * GB;
    }

    /** Bytes of the oldest clips to delete from the stick now; 0 when it has enough room or the sizes are unknown. */
    static long bytesToFree(long freeBytes, long totalBytes) {
        if (freeBytes < 0 || totalBytes <= 0 || freeBytes >= lowWaterBytes(totalBytes)) return 0;
        return targetFreeBytes(totalBytes) - freeBytes;
    }

    /** A limit typed in the settings: blank, 0 or anything unreadable is 0 (no limit). */
    static int parseLimitGb(String text) {
        if (text == null) return 0;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 5) return 0;
        try {
            return Math.max(0, Integer.parseInt(t));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
