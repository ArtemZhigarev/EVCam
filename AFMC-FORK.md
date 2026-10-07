# EVCam — AppsForMyCar fork

This is the source of the "EVCam dashcam" app AppsForMyCar offers for the Geely EX2
(`org.ex2.evcam`). It is EVCam by suyunkai with the changes listed below, under the same licence,
GPL-3.0 (see LICENSE). Public source: https://github.com/ArtemZhigarev/EVCam, branch `afmc`; each
released version is tagged (`v1.6.6-afmc.N`).

To build it yourself: `./gradlew assembleDebug` (JDK 17, Android SDK 36). A release build is signed
with your own key: put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in
`~/.appsformycar/localstore-signing.properties`. A build signed with a different key installs as a
separate copy only after the AppsForMyCar-signed one is removed.

Upstream: https://github.com/suyunkai/EVCam (GPL-3.0), imported at master `0876b97` (2026-06-22,
version 1.6.6, versionCode 77) with `git subtree --squash`. Licence: GPL-3.0 (see LICENSE) — this
fork stays GPL-3.0 and its source must be published alongside any APK we distribute.

| Change | Why |
|---|---|
| `applicationId` `org.ex2.evcam`, version `1.6.6-afmc.N` (versionCode 77000+N) | installs next to upstream / Eucalyptus copies |
| Signed with the AppsForMyCar key (`~/.appsformycar/localstore-signing.properties`) | upstream uses the public AOSP test key |
| App name "EVCam" | was 电车记录仪 |
| `AfmcGeelyEx2Defaults` (called from `AppConfig`) | Geely EX2 cameras set up out of the box: 4 front (mirrored), 3 back, 5 left, 2 right; fresh installs on 360° EX2s only |
| English UI: `afmc-i18n/apply.py` + `zh-en.json` (text) + `fixups.json` (source fix-ups), applied in place — the committed sources are already English; rerun after every upstream pull | upstream is Chinese-only. Logs, comments and bot-command keywords stay Chinese |
| `AfmcSnapshotReceiver` (+ `org.ex2.permission.CAR_SNAPSHOT`, one line in `MainActivity`) | LocalStore gets pictures from the cameras EVCam has open; falls back to upstream's `CameraManagerHolder` when MainActivity didn't create the camera manager |
| Background recording (afmc.4): `AfmcGeelyEx2Defaults` turns on upstream's "Start when the car starts" (`auto_start_on_boot`) and "Record automatically" (`auto_start_recording`) once on every EX2; `MainActivity` (`afmc*` members) moves EVCam back to the background once an automatic recording runs and, after a wake, brings it to the front briefly if the cameras won't open in the background; `CameraForegroundService` keeps the "EVCam is recording" notification while recording | owner wants EVCam recording without opening it, also behind Waze/LocalStore. Everything else (foreground service, boot receiver, keep-alive, resume after screen-on) is upstream's. Off switch: Settings → Record automatically |
| Sentry mode (afmc.7): `AfmcSentryMode` (+ `AfmcSentryPolicy`, `AfmcUsageModeReader`, `AfmcVhalProps`, `AfmcSentrySettings`; a few `AfmcSentryMode` lines in `MainActivity`, one in `SettingsFragment`, a card in `fragment_settings.xml`) | owner request: off by default; Settings → Sentry mode (on/off, picture every 1/2/5/10/15 min, default 5). While the car is left (usage mode 0x21408030 ≠ 2, read-only from the vehicle HAL's gRPC stream on 127.0.0.1:40004) it keeps recording (also with the screen off) and saves a picture from each outside camera to `DCIM/Sentry/<yyyyMMdd_HHmmss>_<position>.jpg` on the recording drive; when that drive is short it deletes the oldest sentry pictures first, then the oldest finished clips. Never Camera2 IDs 0/1. Stops when the car is in use. Logcat tag `AfmcSentry` |

Build: `bash android/evcam/build.sh` (builds outside OneDrive; APK in `~/build/evcam/...`).
Pull upstream changes: `git subtree pull --prefix android/evcam https://github.com/suyunkai/EVCam.git master --squash`.

Planned: interior cameras (Camera2 IDs 0/1), snapshot hand-off to LocalStore while recording.

## Status
- 2026-10-06: **afmc.7** built (not installed): sentry mode, see the table. The LocalStore snapshot receiver now
  takes its picture timestamp from `AfmcSentryMode.claimTimestamp()` so the two never share a second. Unit tests:
  `AfmcSentryPolicyTest`, `AfmcVhalPropsTest` (`build.sh testDebugUnitTest assembleRelease`). Needs the on-car test.
- 2026-10-05: 1.6.6-afmc.1 installed on the test EX2 (replacing the upstream/Eucalyptus copy); EX2 defaults
  applied on first start; cameras 2-5 open in org.ex2.evcam. Copy of the APK: `OneDrive/Claude/keys/releases/`.
- 2026-10-05: 1.6.6-afmc.4 built (afmc.3 was the snapshot hand-off build): English UI finished (60 more strings, date/status
- 2026-10-05: **afmc.6**: recordings default to a USB stick when one is plugged in (upstream's storage-location
  setting, switched on once on the EX2; no stick = the head unit's own storage, as before). LocalStore grants
  the "all files" access writing to a stick needs. Not yet tried with a stick in the car.
- 2026-10-05: **afmc.5**: restart test on the car showed two things. (1) The start with the car was refused by
  Android until EVCam had "display over other apps" (LocalStore 0.0.19 grants it; granted by hand on the test
  car) — after that it recorded 34 s after a head-unit restart. (2) EVCam then stayed on screen. afmc.5 goes to
  the background once the recording has run 3 s after any start it made itself (car start, wake, its own start
  request), and only a touch on its screen cancels that (was: any key event, which the car sends itself).
  Logcat tag `EVCamAfmc`.
- 2026-10-05: **afmc.4 installed on the test EX2.** Opened once: recording started by itself; with LocalStore
  in front the four cameras kept recording (new one-minute segments for front/back/left/right). Still to
  check on the car: start after a head-unit reboot without touching it, screen off/on, the first clip after
  boot, a snapshot asked from the account while EVCam is in the background.
  fix-ups, see `afmc-i18n/`), background recording on by default on the EX2 (starts with the car, keeps
  recording behind other apps, "EVCam is recording" notification), snapshot receiver no longer depends on
  MainActivity having created the cameras. Needs an on-car test: boot, another app in front, screen off/on.
- **Source publication**: owner decision — publish this fork's source (public GitHub fork) when AppsForMyCar
  goes live, before/with the first customer install.
- To do: interior cameras (IDs 0/1), snapshot hand-off to LocalStore,
  LocalStore presets/snapshots pointed at `org.ex2.evcam`.
