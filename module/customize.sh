#!/system/bin/sh
#
# Magisk / KSU customize.sh
# Installs the bundled LSPOSED companion APK (Leica Frame Patcher) during flash.
# The system props are applied by Magisk/KSU from system.prop automatically.
#
# NOTE: flashing runs inside Android (Magisk app / KSU-Next), where `pm` exists,
# so the APK installs at flash time. Recovery flashing has no `pm` and will skip
# the APK install (props still apply).

APK="$MODPATH/LeicaFramePatcher.apk"

ui_print "- Nezha Leica Camera Enabler"
ui_print "- Applying Leica system properties"

if [ -f "$APK" ]; then
  ui_print "- Installing LSPOSED module APK (Leica Frame Patcher)"
  if command -v pm >/dev/null 2>&1 && pm install -r -g "$APK" >/dev/null 2>&1; then
    ui_print "  APK installed."
    ui_print "  >> Open LSPOSED, ENABLE 'Leica Frame Patcher',"
    ui_print "  >> set scope: Camera + Gallery editor, then reboot."
    rm -f "$APK"
  else
    ui_print "! Auto-install failed (recovery flash, or pm unavailable)."
    ui_print "! Install it manually after boot:"
    ui_print "!   /data/adb/modules/$MODID/LeicaFramePatcher.apk"
    ui_print "! Then enable it in LSPOSED (scope: Camera + Gallery editor)."
  fi
else
  ui_print "! Bundled APK not found — props-only install."
fi
