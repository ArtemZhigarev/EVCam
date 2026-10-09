package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * AppsForMyCar fork: out-of-the-box camera setup for the Geely EX2 (shared EX2/EX5 head unit,
 * product antora1000_e22h). See docs/cars/geely-ex2.md in the AppsForMyCar repo.
 *
 * Applied once, on a fresh install only (no car model saved yet), and only when all four outside
 * cameras exist — lower EX2 trims have no 360° cameras and keep upstream's defaults.
 *
 * Camera2 IDs on the EX2: 4 front, 3 back, 5 left, 2 right (0 and 1: likely interior, unused).
 * A second head-unit variant (10 camera devices) has the right camera on ID 6 instead, and an ID 2
 * that won't open: see chooseRightCamera (2026-10-09, same rule as LocalStore's CameraVariant).
 * The front camera's picture is NOT mirrored: no mirror flag is set (afmc.4 to afmc.12 set
 * camera_front_mirror = true, which mirrored the live view; see applyFrontMirrorFix).
 *
 * Since 1.6.6-afmc.4 it also turns on upstream's "Start when the car starts" and "Record
 * automatically" settings once on every EX2, so EVCam records without being opened and keeps
 * recording behind other apps (see applyBackgroundRecording).
 */
final class AfmcGeelyEx2Defaults {
    private static final String TAG = "AfmcEx2";
    private static final String KEY_BACKGROUND_RECORDING_APPLIED = "afmc_background_recording_applied";
    private static final String KEY_USB_STORAGE_APPLIED = "afmc_usb_storage_applied";
    private static final String KEY_FRONT_MIRROR_FIXED = "afmc_front_mirror_fixed";

    private AfmcGeelyEx2Defaults() {}

    static boolean isEx2HeadUnit() {
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        return product.startsWith("antora1000_e22h") || model.contains("EX5") || model.contains("EX2");
    }

    static boolean hasSurroundCameras(Context context) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            Set<String> ids = new HashSet<>(Arrays.asList(cm.getCameraIdList()));
            return ids.containsAll(Arrays.asList("2", "3", "4", "5"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Called from every AppConfig constructor, so it has to stay cheap once the defaults are in. */
    static void apply(Context context, SharedPreferences prefs) {
        applyBackgroundRecording(prefs);
        applyUsbStorage(prefs);
        applyFrontMirrorFix(prefs);
        applyIfFresh(context, prefs);
    }

    /**
     * Recordings go to a USB stick when one is plugged in (owner request, 2026-10-05). This is
     * upstream's "storage location" setting: with no stick present upstream records to the head
     * unit's own storage instead, and clears old clips when that runs low. Set once, also on
     * existing installs; after that whatever the owner picks in Settings stays.
     */
    private static void applyUsbStorage(SharedPreferences prefs) {
        if (prefs.contains(KEY_USB_STORAGE_APPLIED) || !isEx2HeadUnit()) return;
        prefs.edit()
                .putString("storage_location", AppConfig.STORAGE_EXTERNAL_SD)
                .putBoolean(KEY_USB_STORAGE_APPLIED, true)
                .apply();
        AppLog.d(TAG, "Geely EX2: recordings go to a USB stick when one is plugged in");
    }

    /**
     * EVCam starts with the car and starts recording by itself; upstream then keeps the cameras
     * and the recording going under its foreground service while another app is on screen. Both
     * are upstream settings (auto_start_on_boot, auto_start_recording — upstream's default for the
     * second is off). Set once, also on installs made before afmc.4; after that whatever the
     * owner picks in Settings stays.
     */
    private static void applyBackgroundRecording(SharedPreferences prefs) {
        if (prefs.contains(KEY_BACKGROUND_RECORDING_APPLIED) || !isEx2HeadUnit()) return;
        prefs.edit()
                .putBoolean("auto_start_on_boot", true)
                .putBoolean("auto_start_recording", true)
                .putBoolean(KEY_BACKGROUND_RECORDING_APPLIED, true)
                .apply();
        AppLog.d(TAG, "Geely EX2: start with the car + record automatically turned on");
    }

    /**
     * Our camera preset (afmc.4 to afmc.12) set camera_front_mirror = true. That flag only flips
     * the live view (CustomLayoutManager), and the front camera's own picture already reads the
     * right way round (recorded clips and sentry pictures, which ignore the flag, show shop signs
     * readable, 2026-10-07/08), so the live view came out mirrored (owner, 2026-10-09).
     * Once per install: clears the flag when it still looks like our preset (custom layout with the
     * front on camera 4). An owner who set it on purpose can't be told apart; they can switch it
     * back in the layout editor, and it is never changed again.
     */
    private static void applyFrontMirrorFix(SharedPreferences prefs) {
        if (prefs.contains(KEY_FRONT_MIRROR_FIXED) || !isEx2HeadUnit()) return;
        boolean ours = shouldClearFrontMirror(prefs.getBoolean("camera_front_mirror", false),
                prefs.getString("car_model", null), prefs.getString("camera_front_id", null));
        SharedPreferences.Editor e = prefs.edit().putBoolean(KEY_FRONT_MIRROR_FIXED, true);
        if (ours) e.putBoolean("camera_front_mirror", false);
        e.apply();
        if (ours) AppLog.i(TAG, "Geely EX2: front camera live view un-mirrored (our old preset had mirrored it)");
    }

    /** Whether the saved front-mirror flag is the one our old preset wrote. Pure, for tests. */
    static boolean shouldClearFrontMirror(boolean frontMirror, String carModel, String frontId) {
        return frontMirror && "custom".equals(carModel) && "4".equals(frontId);
    }

    /** The size of every EX2 outside camera. */
    static final int OUTSIDE_W = 1280;
    static final int OUTSIDE_H = 800;

    /**
     * The right-hand camera: "2" when ID 2 is an outside camera (1280x800), else "6" when ID 6 is,
     * else the usual "2". Sizes are the cameras' pixel-array sizes, null when missing or unreadable.
     * IDs 0 and 1 (likely interior driver-monitoring cameras) are never considered. Pure, for tests.
     */
    static String chooseRightCamera(int[] id2Size, int[] id6Size) {
        if (isOutside(id2Size)) return "2";
        if (isOutside(id6Size)) return "6";
        return "2";
    }

    private static boolean isOutside(int[] size) {
        return size != null && size.length == 2 && size[0] == OUTSIDE_W && size[1] == OUTSIDE_H;
    }

    /** Reads only the descriptions of IDs 2 and 6 (CameraCharacteristics); never opens a camera. */
    static String detectRightCamera(Context context) {
        int[] s2 = null, s6 = null;
        int count = 0;
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            Set<String> ids = new HashSet<>(Arrays.asList(cm.getCameraIdList()));
            count = ids.size();
            s2 = ids.contains("2") ? pixelArraySize(cm, "2") : null;
            s6 = ids.contains("6") ? pixelArraySize(cm, "6") : null;
        } catch (Exception ignored) {
        }
        String right = chooseRightCamera(s2, s6);
        AppLog.i(TAG, "Geely EX2: right camera = ID " + right + " (" + count + " cameras, ID 2 "
                + sizeText(s2) + ", ID 6 " + sizeText(s6) + ")");
        return right;
    }

    private static int[] pixelArraySize(CameraManager cm, String id) {
        try {
            android.util.Size s = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            return s == null ? null : new int[]{s.getWidth(), s.getHeight()};
        } catch (Exception e) {
            return null;
        }
    }

    private static String sizeText(int[] s) {
        return s == null ? "?" : s[0] + "x" + s[1];
    }

    private static void applyIfFresh(Context context, SharedPreferences prefs) {
        if (prefs.contains("car_model") || !isEx2HeadUnit() || !hasSurroundCameras(context)) return;
        String right = detectRightCamera(context);

        SharedPreferences.Editor e = prefs.edit()
                .putString("car_model", "custom")
                .putInt("camera_count", 4)
                .putString("camera_front_id", "4").putString("camera_front_name", "Front")
                .putString("camera_back_id", "3").putString("camera_back_name", "Back")
                .putString("camera_left_id", "5").putString("camera_left_name", "Left")
                .putString("camera_right_id", right).putString("camera_right_name", "Right")
                .putBoolean("camera_front_mirror", false)
                .putBoolean("recording_camera_front_enabled", true)
                .putBoolean("recording_camera_back_enabled", true)
                .putBoolean("recording_camera_left_enabled", true)
                .putBoolean("recording_camera_right_enabled", true)
                .putInt("custom_layout_version", 3)
                .putInt("custom_key_speed_prop_id", 291504647)
                .putFloat("custom_key_speed_threshold", 8.34f)
                .putInt("custom_key_button_prop_id", 557872183)
                .putString("custom_button_style", "standard")
                .putBoolean("custom_free_control_enabled", false)
                .putString("screen_orientation", "landscape");
        for (String pos : new String[]{"front", "back", "left", "right"}) {
            e.putInt("fullscreen_window_x_" + pos, 0).putInt("fullscreen_window_y_" + pos, 0)
                    .putInt("fullscreen_window_width_" + pos, 1280).putInt("fullscreen_window_height_" + pos, 645);
        }
        // The owner's preset (2026-10-09): no recording with the screen off, 5 GB each for clips
        // and pictures. Fresh installs only, and never over a value that is already there.
        if (!prefs.contains("screen_off_recording")) e.putBoolean("screen_off_recording", false);
        if (!prefs.contains("video_storage_limit_gb")) e.putInt("video_storage_limit_gb", 5);
        if (!prefs.contains("photo_storage_limit_gb")) e.putInt("photo_storage_limit_gb", 5);
        // A fresh install needs no mirror fix.
        e.putBoolean(KEY_FRONT_MIRROR_FIXED, true);
        e.apply();
        AppLog.d(TAG, "Geely EX2 camera defaults applied");
    }
}
