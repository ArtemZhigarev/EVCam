package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
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
 * The front camera's picture comes in mirrored, so it's un-mirrored here.
 *
 * Since 1.6.6-afmc.4 it also turns on upstream's "Start when the car starts" and "Record
 * automatically" settings once on every EX2, so EVCam records without being opened and keeps
 * recording behind other apps (see applyBackgroundRecording).
 */
final class AfmcGeelyEx2Defaults {
    private static final String TAG = "AfmcEx2";
    private static final String KEY_BACKGROUND_RECORDING_APPLIED = "afmc_background_recording_applied";

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
        applyIfFresh(context, prefs);
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

    private static void applyIfFresh(Context context, SharedPreferences prefs) {
        if (prefs.contains("car_model") || !isEx2HeadUnit() || !hasSurroundCameras(context)) return;

        SharedPreferences.Editor e = prefs.edit()
                .putString("car_model", "custom")
                .putInt("camera_count", 4)
                .putString("camera_front_id", "4").putString("camera_front_name", "Front")
                .putString("camera_back_id", "3").putString("camera_back_name", "Back")
                .putString("camera_left_id", "5").putString("camera_left_name", "Left")
                .putString("camera_right_id", "2").putString("camera_right_name", "Right")
                .putBoolean("camera_front_mirror", true)
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
        e.apply();
        AppLog.d(TAG, "Geely EX2 camera defaults applied");
    }
}
