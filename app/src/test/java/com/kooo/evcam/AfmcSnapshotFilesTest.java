package com.kooo.evcam;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Snapshots taken for LocalStore are cleaned up after a day (2026-10-12 audit). */
public class AfmcSnapshotFilesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long DAY = AfmcSnapshotFiles.KEEP_MS;

    @Test
    public void onlySnapshotNamesCount() {
        assertTrue(AfmcSnapshotFiles.isSnapshotName("20261012_101500_front.jpg"));
        assertTrue(AfmcSnapshotFiles.isSnapshotName("20261012_101500_right.jpg"));
        assertFalse(AfmcSnapshotFiles.isSnapshotName("20261012_101500_cabin.jpg"));
        assertFalse(AfmcSnapshotFiles.isSnapshotName("20261012_101500_front.mp4"));
        assertFalse(AfmcSnapshotFiles.isSnapshotName("notes.jpg"));
        assertFalse(AfmcSnapshotFiles.isSnapshotName("x20261012_101500_front.jpg"));
        assertFalse(AfmcSnapshotFiles.isSnapshotName(null));
    }

    @Test
    public void keptForADay() {
        long now = 10 * DAY;
        assertFalse(AfmcSnapshotFiles.isStale(now - DAY + 1, now));
        assertTrue(AfmcSnapshotFiles.isStale(now - DAY - 1, now));
        assertFalse("dated in the future", AfmcSnapshotFiles.isStale(now + DAY * 3, now));
        assertFalse("no date", AfmcSnapshotFiles.isStale(0, now));
    }

    @Test
    public void onlyOldSnapshotsAreListed() throws Exception {
        long now = System.currentTimeMillis();
        File old = touch("20261010_080000_front.jpg", now - 2 * DAY);
        touch("20261012_080000_back.jpg", now - 60_000L);
        touch("my_photo.jpg", now - 5 * DAY);
        touch("20261001_080000_front.mp4", now - 5 * DAY);
        List<File> stale = AfmcSnapshotFiles.stale(tmp.getRoot().listFiles(), now);
        assertEquals(1, stale.size());
        assertEquals(old.getName(), stale.get(0).getName());
        assertTrue(AfmcSnapshotFiles.stale(null, now).isEmpty());
    }

    @Test
    public void theFolderIsInsideThePhotoFolder() {
        File photo = new File("/storage/emulated/0/DCIM/EVCam_Photo");
        assertEquals(new File(photo, "AfmcSnapshots"), AfmcSnapshotFiles.dir(photo));
    }

    private File touch(String name, long at) throws Exception {
        File f = tmp.newFile(name);
        assertTrue(f.setLastModified(at));
        return f;
    }
}
