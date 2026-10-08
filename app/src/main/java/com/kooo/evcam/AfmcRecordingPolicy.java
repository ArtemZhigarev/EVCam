package com.kooo.evcam;

/**
 * AppsForMyCar fork: the decisions behind "Record while driving" switched from LocalStore
 * (AfmcRecordingReceiver). No Android in here, so it can be unit-tested.
 *
 * "Record while driving" is upstream's "Record automatically" setting (AppConfig auto_start_recording).
 * Upstream only acts on it the next time EVCam starts; switched from LocalStore it also takes effect
 * at once:
 *  - switched on while the car is in use (screen on) and EVCam isn't recording: start recording now,
 *    even if the driver had stopped a recording by hand in EVCam (switching on is asking for it);
 *  - switched off while it was on and EVCam is recording: stop that recording;
 *  - never touches a recording sentry mode is running (the car is left; sentry stops it itself);
 *  - a repeated "off" when it was already off leaves a recording the driver started by hand alone.
 */
final class AfmcRecordingPolicy {
    enum Action { START, STOP, NONE }

    static final String STORAGE_USB = "usb";
    static final String STORAGE_INTERNAL = "internal";

    private AfmcRecordingPolicy() {}

    static Action decide(boolean wasOn, boolean on, boolean carInUse, boolean sentryHoldsCameras, boolean recording) {
        if (sentryHoldsCameras) return Action.NONE;
        if (on) return carInUse && !recording ? Action.START : Action.NONE;
        return wasOn && recording ? Action.STOP : Action.NONE;
    }

    /**
     * Where the clips go: the USB stick when the storage setting says USB and a stick is in, else
     * the head unit's own storage (upstream falls back to it when no stick is found).
     */
    static String storage(boolean usbChosen, boolean usbPresent) {
        return usbChosen && usbPresent ? STORAGE_USB : STORAGE_INTERNAL;
    }
}
