# Leica Frame Patcher (LSPOSED)

Companion LSPOSED/Xposed module for the Nezha Leica Camera Enabler Magisk module.
The Magisk module sets the Leica system props; this module patches the app behaviour
that props alone cannot reach.

## What it fixes

1. **6 Leica gallery frames rejected** — the HyperOS photo editor
   (`com.miui.mediaeditor`) gates certain Leica frames behind a per-photo EXIF check
   (`xi.y.a(Hi.a, String, D5.b)` → `Di.a`). Photos shot on nezha classify as
   `xiaomi_series`, not Leica-cobrand, so those frames show
   *"photo is not supported"*. The hook forces the gate to always return the
   **Support** singleton (`Di.a$a.a`). The gate is a pure filter and feeds no render
   data, so frames render correctly; absent EXIF text fields render blank (no crash).

2. **Leica camera-island ring settings** — the Leica/Leitz variant has a control
   ring on the camera island; nezha does not, but the Leica props make its settings
   appear. Removed in two places (the Photography-Kit "Camera Grip" is a *separate*
   feature and is deliberately left working):
   - *In-camera* (`com.android.camera`): under the Leica theme (LCC),
     `MiThemeOperationCommonLC.supportHandleRing()` hard-returns `true`;
     `CameraCommonPreferenceFragment.addCurrentPreferences` (~line 240) gates the
     whole "Camera ring" block on it, as do the on-screen guide and gesture handler.
     Forced to `false`. Real (non-obfuscated) MiuiCamera name — stable.
   - *System Settings* (`com.android.settings`): a separate "Camera ring" header
     (`R.id.camera_mr_settings`, launching
     `com.miui.securitycore/…GestureShortcutCameraMrActivity`) is shown when
     `SettingsFeatures.SUPPORT_CAMERA_MR_FUNCTION` is true, which is set once from
     `miui.hardware.input.InputFeature.supportCameraMRFunction()`. The hook forces
     that method to `false` in the Settings process (before `SettingsFeatures`'
     static init runs), so `MiuiSettings` drops the header. Real framework name —
     stable. Does **not** touch `com.android.settings.cameragrip` (the Photography Kit).

3. **Watermark device name** — shows **"XIAOMI 17 Ultra by Leica"** (brand "XIAOMI"
   in the logo font, from the frame's own brand line) in both apps, replacing the
   stock "Leitzphone Powered by Xiaomi":
   - *Gallery* (`com.miui.mediaeditor`): the name comes from `mj.g.n()` (model line,
     EXIF-`Model` → hashCode switch) which `mj.g.k()` combines. Both are forced to
     `WATERMARK_NAME`. Obfuscated single-letter names — see the re-mapping note below.
   - *Camera* (`com.android.camera`): the live-shot watermark text comes from a family
     of provider classes (subclasses of `C15705`) whose `mo26211d()` returns a
     `SparseArray<String[]>` = `[manufacturer, device]`. Both the class and method
     names are **non-ASCII obfuscated and shift every build**, so they're located at
     runtime with **DexKit** (shape: `SparseArray`-returning, zero-arg, with the
     `SparseArray<String[]>` signature annotation) — exactly as HyperCeiler's
     `camera/CustomWatermark` does. The device slot is set to `WATERMARK_DEV`
     (`"17 Ultra by Leica"`); the frame prepends its own "XIAOMI" brand line.

   To change the text, edit `WATERMARK_NAME` / `WATERMARK_DEV` in `HookEntry.java`
   and rebuild.

## Build

Open `lsposed-module/` in Android Studio, or from a shell (the Gradle wrapper is
committed):

```sh
cd lsposed-module
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed → installable).

Dependencies: the Xposed API is `compileOnly` from `https://api.xposed.info/` (not
bundled — LSPOSED provides it at runtime); **DexKit** (`org.luckypray:dexkit`) *is*
bundled (native lib, ~4 MB APK) and powers the camera watermark hook.

## Install

1. Install the APK.
2. In the LSPOSED manager: enable the module, tick scope **Gallery editor**
   (`com.miui.mediaeditor`), **Camera** (`com.android.camera`), and **Settings**
   (`com.android.settings`).
3. **Force-stop** all three (or reboot) so the hooks load.
4. Verify: the 6 Leica frames apply in the gallery editor; the "Camera ring" entry
   is gone from both the camera settings and system Settings; and the watermark
   reads "XIAOMI 17 Ultra by Leica" (camera + gallery).

## If a HyperOS update breaks it

The hook matches by method *shape* (static, 3 params, 2nd is `String`, returns
`Di.a`) rather than fixed obfuscated names, so minor rebuilds usually survive.
If names shift wholesale, `logcat -s LeicaFramePatcher` prints the class-lookup
result and dumps candidate methods. Re-map `CLS_GATE` / `CLS_SUPPORT` / `CLS_VERDICT`
in `HookEntry.java`. Runtime markers to re-identify the gate class: the XOR-21123
string helper `E0.b.z`, family-tag literals `leica_series` / `xiaomi_series` /
`lcc_series`, and EXIF keys `XiaomiProduct`, `themeCustomize`, `ExposureBiasValue`.
