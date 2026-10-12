package com.kooo.evcam;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

/**
 * AppsForMyCar: on the Geely EX2, open only outside cameras (1280x800), whatever their Camera2 ID.
 *
 * Why: the head unit's camera service lists only the cameras that are free when it starts. At boot
 * that is six (0 driver monitoring, 1 cabin, 2 right, 3 back, 4 front, 5 left); after the camera
 * service restarts while the car is on, driver monitoring, cabin and the 360 system hold theirs, and
 * it lists four, renumbered 0 right, 1 back, 2 front, 3 left (Test car 1, 2026-10-12). Another car
 * listed ten. A camera number therefore says nothing about which camera it is; the size does: outside
 * cameras are 1280x800, driver monitoring 1280x960, cabin 1920x1080. Reads descriptions only, never
 * opens a camera to find out.
 */
public final class AfmcCameraGuard {
    private static final String TAG = "AfmcCameraGuard";

    private AfmcCameraGuard() {}

    /** Pure, for tests: may a camera with this pixel-array size be opened? Unknown size: yes (as before). */
    static boolean mayOpen(boolean ex2, int[] size) {
        if (!ex2 || size == null || size.length != 2) return true;
        return size[0] == AfmcGeelyEx2Defaults.OUTSIDE_W && size[1] == AfmcGeelyEx2Defaults.OUTSIDE_H;
    }

    /** True when [cameraId] must not be opened on this head unit (logged). */
    public static boolean refuses(CameraManager cm, String cameraId) {
        int[] size = null;
        try {
            android.util.Size s = cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (s != null) size = new int[]{s.getWidth(), s.getHeight()};
        } catch (Exception ignored) {
        }
        boolean refuse = !mayOpen(AfmcGeelyEx2Defaults.isEx2HeadUnit(), size);
        if (refuse) {
            AppLog.e(TAG, "camera " + cameraId + " is " + size[0] + "x" + size[1]
                    + ", not an outside camera: not opened (camera numbers change when the car's camera service restarts)");
        }
        return refuse;
    }
}
