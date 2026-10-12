package com.kooo.evcam;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AppsForMyCar fork: where the pictures taken for LocalStore (AfmcSnapshotReceiver) are kept, and
 * which of them are old enough to delete. No Android in here, so it can be unit-tested.
 *
 * Why (2026-10-12 audit): LocalStore copies each snapshot and uploads its copy, but EVCam's originals
 * were never deleted, so every snapshot asked for from the owner's account stayed in EVCam's photo
 * folder for good. Now they go in their own folder inside EVCam's photo folder, [DIR_NAME], because
 * in the photo folder itself they have the same names as the owner's own photos
 * (<yyyyMMdd_HHmmss>_<position>.jpg) and could not be told apart. LocalStore deletes the originals as
 * soon as it has its copies; whatever is left (LocalStore wasn't there, the copy failed) is deleted by
 * EVCam on its next snapshot once it is [KEEP_MS] old.
 */
final class AfmcSnapshotFiles {
    static final String DIR_NAME = "AfmcSnapshots";
    static final long KEEP_MS = 24 * 60 * 60_000L;

    private static final Pattern NAME = Pattern.compile("\\d{8}_\\d{6}_(front|back|left|right)\\.jpg");

    private AfmcSnapshotFiles() {}

    static File dir(File photoDir) {
        return new File(photoDir, DIR_NAME);
    }

    /** A picture this receiver names: <yyyyMMdd_HHmmss>_<front|back|left|right>.jpg, nothing else. */
    static boolean isSnapshotName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Older than [KEEP_MS]. A file dated in the future (the clock moved) is kept. */
    static boolean isStale(long lastModifiedMs, long nowMs) {
        return lastModifiedMs > 0 && nowMs - lastModifiedMs > KEEP_MS;
    }

    /** The snapshot files in [files] old enough to delete; anything not named like a snapshot is left alone. */
    static List<File> stale(File[] files, long nowMs) {
        List<File> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            if (f != null && isSnapshotName(f.getName()) && isStale(f.lastModified(), nowMs)) out.add(f);
        }
        return out;
    }
}
