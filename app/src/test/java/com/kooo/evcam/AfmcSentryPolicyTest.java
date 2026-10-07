package com.kooo.evcam;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AfmcSentryPolicyTest {
    private static final long MIN = 60_000L;
    private static final long GB = AfmcSentryPolicy.GB;
    private static final long MB = 1024L * 1024L;

    // ---------- start / stop from the car's usage mode ----------

    @Test
    public void startsWhenTheCarIsLeftAndSentryIsOn() {
        assertEquals(AfmcSentryPolicy.Action.START, AfmcSentryPolicy.decide(true, 1, false));
        assertEquals(AfmcSentryPolicy.Action.START, AfmcSentryPolicy.decide(true, 0, false));
    }

    @Test
    public void stopsWhenTheCarIsInUseAgain() {
        assertEquals(AfmcSentryPolicy.Action.STOP, AfmcSentryPolicy.decide(true, 2, true));
    }

    @Test
    public void nothingWhileInUseOrAlreadyRunning() {
        assertEquals(AfmcSentryPolicy.Action.NONE, AfmcSentryPolicy.decide(true, 2, false));
        assertEquals(AfmcSentryPolicy.Action.NONE, AfmcSentryPolicy.decide(true, 1, true));
    }

    @Test
    public void offByTheSettingNeverStartsAndStopsARunningSentry() {
        assertEquals(AfmcSentryPolicy.Action.NONE, AfmcSentryPolicy.decide(false, 1, false));
        assertEquals(AfmcSentryPolicy.Action.STOP, AfmcSentryPolicy.decide(false, 1, true));
        assertEquals(AfmcSentryPolicy.Action.STOP, AfmcSentryPolicy.decide(false, null, true));
    }

    @Test
    public void unknownUsageChangesNothing() {
        assertEquals(AfmcSentryPolicy.Action.NONE, AfmcSentryPolicy.decide(true, null, false));
        assertEquals(AfmcSentryPolicy.Action.NONE, AfmcSentryPolicy.decide(true, null, true));
    }

    @Test
    public void staleUsageReadingsAreNotActedOn() {
        assertEquals(Integer.valueOf(1), AfmcSentryPolicy.freshUsage(1, 1_000, 1_000 + 9 * MIN));
        assertNull(AfmcSentryPolicy.freshUsage(1, 1_000, 1_000 + 11 * MIN));
        assertNull(AfmcSentryPolicy.freshUsage(1, 0, 5_000));
        assertNull(AfmcSentryPolicy.freshUsage(null, 1_000, 2_000));
    }

    // ---------- interval ----------

    @Test
    public void intervalDefaultsToFiveMinutesAndOnlyTakesTheChoices() {
        assertEquals(5, AfmcSentryPolicy.DEFAULT_INTERVAL_MIN);
        for (int c : new int[]{1, 2, 5, 10, 15}) assertEquals(c, AfmcSentryPolicy.normalizeInterval(c));
        assertEquals(5, AfmcSentryPolicy.normalizeInterval(0));
        assertEquals(5, AfmcSentryPolicy.normalizeInterval(7));
        assertEquals(5, AfmcSentryPolicy.normalizeInterval(-3));
        assertEquals(2, AfmcSentryPolicy.intervalIndex(5));
        assertEquals(4, AfmcSentryPolicy.intervalIndex(15));
        assertEquals(2, AfmcSentryPolicy.intervalIndex(99));
    }

    @Test
    public void firstPictureShortlyAfterSentryStarts() {
        long since = 100_000;
        assertEquals(AfmcSentryPolicy.FIRST_SNAPSHOT_DELAY_MS,
                AfmcSentryPolicy.nextSnapshotDelayMs(since, since, -1, 5));
        assertEquals(0, AfmcSentryPolicy.nextSnapshotDelayMs(since + 20_000, since, -1, 5));
    }

    @Test
    public void thenEveryIntervalAfterTheLastPicture() {
        long last = 1_000_000;
        assertEquals(5 * MIN, AfmcSentryPolicy.nextSnapshotDelayMs(last, 0, last, 5));
        assertEquals(2 * MIN, AfmcSentryPolicy.nextSnapshotDelayMs(last + 3 * MIN, 0, last, 5));
        assertEquals(0, AfmcSentryPolicy.nextSnapshotDelayMs(last + 5 * MIN, 0, last, 5));
        assertEquals(0, AfmcSentryPolicy.nextSnapshotDelayMs(last + 9 * MIN, 0, last, 5));
        assertEquals(MIN, AfmcSentryPolicy.nextSnapshotDelayMs(last, 0, last, 1));
        assertEquals(15 * MIN, AfmcSentryPolicy.nextSnapshotDelayMs(last, 0, last, 15));
        // a bad setting falls back to 5 minutes
        assertEquals(5 * MIN, AfmcSentryPolicy.nextSnapshotDelayMs(last, 0, last, 3));
    }

    // ---------- folder and files ----------

    @Test
    public void sentryFolderSitsNextToThePhotosOnTheSameDrive() {
        File photos = new File("/storage/1234-ABCD/DCIM/EVCam_Photo");
        assertEquals(new File("/storage/1234-ABCD/DCIM/Sentry"), AfmcSentryPolicy.sentryDir(photos));
        File internal = new File("/storage/emulated/0/DCIM/EVCam_Photo");
        assertEquals(new File("/storage/emulated/0/DCIM/Sentry"), AfmcSentryPolicy.sentryDir(internal));
    }

    @Test
    public void picksUpOnlyTheFilesOfItsOwnShot() {
        String ts = "20261006_221500";
        assertTrue(AfmcSentryPolicy.isShotFile("20261006_221500_front.jpg", ts));
        assertTrue(AfmcSentryPolicy.isShotFile("20261006_221500_right.jpg", ts));
        assertFalse(AfmcSentryPolicy.isShotFile("20261006_221501_front.jpg", ts));
        assertFalse(AfmcSentryPolicy.isShotFile("20261006_221500_front.mp4", ts));
        assertFalse(AfmcSentryPolicy.isShotFile("x20261006_221500_front.jpg", ts));
    }

    @Test
    public void neverTheInteriorCameras() {
        assertTrue(AfmcSentryPolicy.isOutsideCamera("front", "4"));
        assertTrue(AfmcSentryPolicy.isOutsideCamera("back", "3"));
        assertTrue(AfmcSentryPolicy.isOutsideCamera("left", "5"));
        assertTrue(AfmcSentryPolicy.isOutsideCamera("right", "2"));
        assertFalse(AfmcSentryPolicy.isOutsideCamera("front", "0"));
        assertFalse(AfmcSentryPolicy.isOutsideCamera("left", "1"));
        assertFalse(AfmcSentryPolicy.isOutsideCamera("cabin", "6"));
        assertFalse(AfmcSentryPolicy.isOutsideCamera("front", null));
    }

    // ---------- cleanup ----------

    private static AfmcSentryPolicy.Entry e(String path, long sizeMb, long modifiedMs) {
        return new AfmcSentryPolicy.Entry(path, sizeMb * MB, modifiedMs);
    }

    private static List<String> paths(List<AfmcSentryPolicy.Entry> es) {
        List<String> out = new ArrayList<>();
        for (AfmcSentryPolicy.Entry x : es) out.add(x.path);
        return out;
    }

    private static final List<AfmcSentryPolicy.Entry> NONE = Collections.emptyList();

    @Test
    public void nothingDeletedWithEnoughSpace() {
        long now = 100 * MIN;
        List<AfmcSentryPolicy.Entry> sentry = Arrays.asList(e("s1", 1, 0));
        assertTrue(AfmcSentryPolicy.planCleanup(sentry, NONE, 2 * GB, 64 * GB, now).isEmpty());
        assertTrue(AfmcSentryPolicy.planCleanup(sentry, NONE, -1, 64 * GB, now).isEmpty());
    }

    @Test
    public void lowWaterIsSmallerOnSmallDrives() {
        assertEquals(3 * GB / 2, AfmcSentryPolicy.lowWaterBytes(64 * GB));
        assertEquals(8 * GB / 10, AfmcSentryPolicy.lowWaterBytes(8 * GB));
        assertEquals(3 * GB / 2, AfmcSentryPolicy.lowWaterBytes(-1));
        assertEquals(3 * GB, AfmcSentryPolicy.targetFreeBytes(64 * GB));
    }

    @Test
    public void oldestSentryPicturesGoFirst() {
        long now = 100 * MIN;
        // 1 GB free of 64: needs 2 GB more to reach the 3 GB target
        List<AfmcSentryPolicy.Entry> sentry = Arrays.asList(
                e("s-new", 1024, 50 * MIN), e("s-old", 1024, 10 * MIN), e("s-mid", 1024, 30 * MIN));
        List<AfmcSentryPolicy.Entry> videos = Arrays.asList(e("v-old", 1024, MIN));
        assertEquals(Arrays.asList("s-old", "s-mid"),
                paths(AfmcSentryPolicy.planCleanup(sentry, videos, GB, 64 * GB, now)));
    }

    @Test
    public void thenOldestRecordingsWhenSentryPicturesAreNotEnough() {
        long now = 100 * MIN;
        List<AfmcSentryPolicy.Entry> sentry = Arrays.asList(e("s1", 100, 20 * MIN));
        List<AfmcSentryPolicy.Entry> videos = Arrays.asList(
                e("v3", 1024, 40 * MIN), e("v1", 1024, 5 * MIN), e("v2", 1024, 10 * MIN));
        assertEquals(Arrays.asList("s1", "v1", "v2"),
                paths(AfmcSentryPolicy.planCleanup(sentry, videos, GB, 64 * GB, now)));
    }

    @Test
    public void neverTouchesFilesStillBeingWritten() {
        long now = 100 * MIN;
        List<AfmcSentryPolicy.Entry> sentry = Arrays.asList(e("s-fresh", 1024, now - MIN));
        List<AfmcSentryPolicy.Entry> videos = Arrays.asList(e("v-recording", 1024, now - 30_000), e("v-done", 1024, now - 10 * MIN));
        assertEquals(Collections.singletonList("v-done"),
                paths(AfmcSentryPolicy.planCleanup(sentry, videos, GB, 64 * GB, now)));
    }

    @Test
    public void sameAgeIsOrderedByName() {
        long now = 100 * MIN;
        List<AfmcSentryPolicy.Entry> sentry = Arrays.asList(
                e("20261006_2200_right.jpg", 1024, MIN), e("20261006_2200_back.jpg", 1024, MIN));
        assertEquals(Arrays.asList("20261006_2200_back.jpg", "20261006_2200_right.jpg"),
                paths(AfmcSentryPolicy.planCleanup(sentry, NONE, GB, 64 * GB, now)));
    }

    @Test
    public void drivingAndUnknownUsageModesCountAsInUse() {
        // 13 while driving (EX2, 2026-10-07): sentry must not start, and must stop if it was on.
        assertEquals(AfmcSentryPolicy.Action.NONE, AfmcSentryPolicy.decide(true, 13, false));
        assertEquals(AfmcSentryPolicy.Action.STOP, AfmcSentryPolicy.decide(true, 13, true));
        assertEquals(AfmcSentryPolicy.Action.NONE, AfmcSentryPolicy.decide(true, 7, false));
        assertEquals(AfmcSentryPolicy.Action.START, AfmcSentryPolicy.decide(true, 1, false));
    }

    @Test
    public void picturesComeFromThatCamerasClipsNewestFirst() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("clips").toFile();
        String[] names = {"20261007_135046_front.mp4", "20261007_135146_front.mp4", "20261007_135146_back.mp4", "20261007_135146_front.jpg"};
        for (String n : names) {
            java.nio.file.Files.write(new java.io.File(dir, n).toPath(), new byte[]{1});
        }
        java.nio.file.Files.write(new java.io.File(dir, "20261007_135246_front.mp4").toPath(), new byte[0]);  // empty: being opened
        java.util.List<java.io.File> front = AfmcSentryPolicy.clipsFor(dir.listFiles(), "front");
        assertEquals(2, front.size());
        assertEquals("20261007_135146_front.mp4", front.get(0).getName());
        assertEquals("20261007_135046_front.mp4", front.get(1).getName());
        assertEquals(0, AfmcSentryPolicy.clipsFor(null, "front").size());
    }

    @Test
    public void lockedInCampingModeCountsAsLeft() {
        assertEquals(Integer.valueOf(1), AfmcSentryPolicy.effectiveUsage(2, 3, 1));   // locked, camping: sentry runs
        assertEquals(Integer.valueOf(2), AfmcSentryPolicy.effectiveUsage(2, 1, 1));   // camping but unlocked: someone may be there
        assertEquals(Integer.valueOf(2), AfmcSentryPolicy.effectiveUsage(2, 3, 0));   // locked while driving (auto-lock), no camping
        assertEquals(Integer.valueOf(13), AfmcSentryPolicy.effectiveUsage(13, 3, 1)); // driving is never left
        assertEquals(null, AfmcSentryPolicy.effectiveUsage(null, 3, 1));
    }
}
