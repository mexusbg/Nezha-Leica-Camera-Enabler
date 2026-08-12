# Leica Camera Enabler (Safe)

A Magisk/KernelSU module (+ bundled LSPOSED companion) that enables Leica features
for the Xiaomi 17 Ultra (nezha).

## Features

**Module (system props):**
- Sets `ro.theme_customize=LCC`
- Sets `ro.theme_leica=true`
- Sets `camera.debug.safe.check.disable=true`

**Bundled LSPOSED module** (`LeicaFramePatcher.apk`, installed automatically on flash — see [`lsposed-module/`](lsposed-module/)):
- Removes the non-working Leica gallery frames from the picker (their working duplicates remain further down the list, so nothing is lost)
- Hides the Leica camera-island "Camera ring" settings this variant has no hardware for — both in the camera and in system Settings (the Photography Kit "Camera Grip" is left working)
- Renames the watermark device name to **"XIAOMI 17 Ultra by Leica"** in both the camera and the gallery editor

> Requires ticking scope **Camera + Gallery editor + Settings** in the LSPOSED manager after install.

## Project structure

- [`module/`](module/) — Magisk/KernelSU module payload (props, `customize.sh`, Magisk installer). The release zip is built from here.
- [`lsposed-module/`](lsposed-module/) — the LSPOSED/Xposed companion (Gradle project). CI builds its APK and bundles it into the zip.
- [`.github/workflows/release.yml`](.github/workflows/release.yml) — builds the APK, assembles and verifies the flashable zip, and attaches it to tagged releases.

## Credits & Attribution

- **Discovery:** Special thanks to **Max Weinbach** for discovering these flags. 
  - [Original X Post by Max Weinbach](https://x.com/mweinbach/status/2038763588655993271)
- **Author:** mexus

## Requirements

- Xiaomi 17 Ultra (nezha), rooted with Magisk or KernelSU / KSU-Next
- LSPOSED (or a compatible Xposed framework) installed and active

## Installation

1. Download the zip from the [Releases](https://github.com/mexusbg/Nezha-Leica-Camera-Enabler/releases) page
   (use the **Release asset**, not the Actions "artifact" — that one is double-zipped by GitHub and won't flash).
2. Flash it in the **Magisk app** or **KernelSU / KSU-Next** manager. This applies the props
   and auto-installs the bundled LSPOSED APK (flashing runs inside Android, where `pm` is available).
3. Open **LSPOSED** → enable **Leica Frame Patcher** → set its scope to **Camera** + **Gallery editor** + **Settings**.
4. Reboot.
5. Clear Camera app data.

> The LSPOSED enable + scope step (3) is manual — LSPOSED has no supported way to auto-enable a module.
> If auto-install didn't run (e.g. recovery flash), install `/data/adb/modules/leica_camera_enabler/LeicaFramePatcher.apk` manually.

The LSPOSED module source and build details are in [`lsposed-module/`](lsposed-module/).
