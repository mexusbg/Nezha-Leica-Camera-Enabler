package io.github.mexusbg.leicaframes;

import android.util.SparseArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.AnnotationMatcher;
import org.luckypray.dexkit.query.matchers.AnnotationsMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPOSED entry point.
 *
 * Problem: on Xiaomi 17 Ultra (nezha) the HyperOS gallery photo editor
 * (com.miui.mediaeditor) rejects 6 Leica watermark "frames" with
 * "verify watermark failed, res is leica but photo is not supported".
 *
 * Root cause: a per-photo eligibility gate, {@code xi.y.a(Hi.a, String, D5.b)},
 * returns an UnSupport verdict ({@code Di.a$b}) unless the PHOTO's EXIF marks it
 * as a Leica-cobrand capture (EXIF XiaomiProduct / Model / themeCustomize=="madrid").
 * Photos shot on nezha classify as xiaomi_series, not leica_series, so the Leica
 * frames are blocked. The gate is a pure filter and feeds no render data, so
 * forcing it to always return the Support singleton ({@code Di.a$a.a}) unblocks
 * every frame and renders correctly (missing EXIF text fields render blank, no crash).
 *
 * We hook by reflection rather than fixed parameter-type strings so the hook keeps
 * working when a HyperOS build reshuffles the obfuscated helper class names.
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "LeicaFramePatcher";
    private static final String PKG_MEDIAEDITOR = "com.miui.mediaeditor";
    private static final String PKG_CAMERA = "com.android.camera";
    private static final String PKG_SETTINGS = "com.android.settings";

    // System Settings shows a "Camera ring" (camera_mr) entry gated by
    // SettingsFeatures.SUPPORT_CAMERA_MR_FUNCTION, which is set from
    // miui.hardware.input.InputFeature.supportCameraMRFunction(). nezha reports
    // true (the Leica camera-island ring), but this variant has no such ring.
    // Forcing it false in the Settings process removes the entry. Does NOT touch
    // the Photography Kit "Camera Grip" (separate: com.android.settings.cameragrip).
    private static final String CLS_INPUT_FEATURE = "miui.hardware.input.InputFeature";

    // MiuiCamera Leica-theme (LCC) operation. Real (non-obfuscated) name.
    // supportHandleRing() hard-returns true under this theme, which is why the
    // camera control-ring settings appear on nezha (which has no ring hardware).
    private static final String CLS_THEME_LC =
            "com.android.camera2.compat.theme.custom.lc.MiThemeOperationCommonLC";

    // Primary (known-good on OS3.0.332 / mediaeditor 6.x) obfuscated names.
    private static final String CLS_GATE = "xi.y";        // Lxi/y;  (eligibility gate, method a())
    private static final String CLS_VERDICT = "Di.a";     // LDi/a;  (verdict base type)
    private static final String CLS_UNSUPPORT = "Di.a$b"; // LDi/a$b; (UnSupport verdict)
    private static final String CLS_PHOTO = "D5.b";       // LD5/b;  (photo/EXIF info, C0813b)
    private static final String CLS_WM_VIEWMODEL =
            "com.miui.mediaeditor.photo.watermark.PhotoWatermarkViewModel";

    // Device name to show on the watermark, replacing "Leitzphone Powered by Xiaomi".
    private static final String WATERMARK_NAME = "Xiaomi 17 Ultra by Leica";
    // Camera watermark provider returns [manufacturer, device]. The frame renders a
    // brand line (logo font, derived from the device string's first token -> "XIAOMI")
    // followed by the device string verbatim. So the device slot ([1]) must omit the
    // leading "Xiaomi" to avoid "XIAOMI Xiaomi 17 Ultra ..."; slot [0] is unused here.
    private static final String WATERMARK_MFR = "";
    private static final String WATERMARK_DEV = "17 Ultra by Leica";

    // Gallery (mediaeditor) watermark EXIF/device-name helper (jadx: p1124mj.C13391g).
    // n() = model line (hashCode switch), k() = "manufacturer model" combiner.
    private static final String CLS_WM_EXIF = "mj.g";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (PKG_MEDIAEDITOR.equals(lpparam.packageName)) {
            try {
                hookHideUnsupportedFrames(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed to install frame-filter hook: " + t);
            }
            try {
                hookGalleryWatermarkName(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed to install gallery watermark-name hook: " + t);
            }
        }
        if (PKG_CAMERA.equals(lpparam.packageName)) {
            try {
                hookHideHandleRing(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed to install ring hook: " + t);
            }
            try {
                hookCameraWatermarkName(lpparam.classLoader, lpparam.appInfo.sourceDir);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed to install camera watermark-name hook: " + t);
            }
        }
        if (PKG_SETTINGS.equals(lpparam.packageName)) {
            try {
                hookHideCameraRingEntry(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed to install camera-ring entry hook: " + t);
            }
        }
    }

    /**
     * Hide the system-Settings "Camera ring" (camera_mr) entry by forcing
     * {@code miui.hardware.input.InputFeature.supportCameraMRFunction()} to false.
     * SettingsFeatures reads it once into SUPPORT_CAMERA_MR_FUNCTION and
     * MiuiSettings drops the header when it is false. Runs before SettingsFeatures'
     * static initializer, so the value takes. Photography-Kit "Camera Grip" is a
     * separate feature and is untouched.
     */
    private void hookHideCameraRingEntry(ClassLoader cl) {
        Class<?> inputFeature = XposedHelpers.findClassIfExists(CLS_INPUT_FEATURE, cl);
        if (inputFeature == null) {
            XposedBridge.log(TAG + ": " + CLS_INPUT_FEATURE + " not found — camera-ring entry hook skipped.");
            return;
        }
        XposedHelpers.findAndHookMethod(inputFeature, "supportCameraMRFunction",
                XC_MethodReplacement.returnConstant(false));
        XposedBridge.log(TAG + ": system-settings Camera ring entry hidden (supportCameraMRFunction=false).");
    }

    /**
     * Camera watermark device name. The live-shot Leica watermark gets its
     * "manufacturer / device" text from a family of provider classes (jadx:
     * subclasses of {@code C15705}) whose {@code mo26211d()} returns a
     * {@code SparseArray<String[]>} = {@code [manufacturer, device]}. Both the
     * class and method names are non-ASCII obfuscated and shift every build, so we
     * locate them at runtime with DexKit by shape — {@code SparseArray}-returning,
     * zero-arg, with the {@code SparseArray<String[]>} generic-signature annotation
     * — exactly as HyperCeiler's {@code camera/CustomWatermark} does. Each match is
     * forced to return {@link #WATERMARK_MFR} / {@link #WATERMARK_DEV}.
     *
     * DexKit scans the ~200 MB APK, so run it off the main thread.
     */
    private void hookCameraWatermarkName(final ClassLoader cl, final String apkPath) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.loadLibrary("dexkit");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": could not load libdexkit — camera watermark skipped: " + t);
                    return;
                }
                try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                    MethodDataList providers = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .returnType(SparseArray.class)
                                    .paramCount(0)
                                    .annotations(AnnotationsMatcher.create()
                                            .add(AnnotationMatcher.create()
                                                    .usingStrings("Ljava/lang/String;")))));
                    XC_MethodHook overrideName = new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            SparseArray<String[]> sa = new SparseArray<>(1);
                            sa.put(0, new String[]{WATERMARK_MFR, WATERMARK_DEV});
                            param.setResult(sa);
                        }
                    };
                    int hooked = 0;
                    for (MethodData md : providers) {
                        try {
                            XposedBridge.hookMethod(md.getMethodInstance(cl), overrideName);
                            hooked++;
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": failed to hook watermark provider " + md + ": " + t);
                        }
                    }
                    XposedBridge.log(TAG + ": camera watermark providers hooked = " + hooked
                            + " ('" + WATERMARK_MFR + "' / '" + WATERMARK_DEV + "').");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": DexKit camera watermark search failed: " + t);
                }
            }
        }, "LeicaFP-DexKit").start();
    }

    /**
     * Gallery (mediaeditor) watermark device name. Class {@code mj.g} derives the
     * device-name text from the photo's EXIF Model; {@code n()} produces the model
     * line and {@code k()} combines "manufacturer model". Like the camera frame, the
     * gallery frame renders its own brand line (logo font, "XIAOMI") ahead of this
     * text, so both are forced to {@link #WATERMARK_DEV} ("17 Ultra by Leica") to
     * avoid "XIAOMI Xiaomi 17 Ultra ...".
     *
     * Names are obfuscated (single letters) and may change on a mediaeditor update;
     * if the class or methods are gone, we log candidates for re-mapping.
     */
    private void hookGalleryWatermarkName(ClassLoader cl) {
        Class<?> exif = XposedHelpers.findClassIfExists(CLS_WM_EXIF, cl);
        if (exif == null) {
            XposedBridge.log(TAG + ": " + CLS_WM_EXIF + " not found — gallery name hook skipped "
                    + "(mediaeditor update likely renamed it).");
            return;
        }
        XC_MethodReplacement toName = XC_MethodReplacement.returnConstant(WATERMARK_DEV);
        int hooked = 0;
        for (Method m : exif.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != String.class) continue;
            if (m.getParameterTypes().length != 3) continue;
            String n = m.getName();
            if (!"n".equals(n) && !"k".equals(n)) continue;
            XposedBridge.hookMethod(m, toName);
            hooked++;
            XposedBridge.log(TAG + ": hooked gallery watermark-name method " + m);
        }
        if (hooked == 0) {
            XposedBridge.log(TAG + ": no gallery watermark-name method matched on " + CLS_WM_EXIF
                    + " — dumping static 3-arg String methods for re-mapping:");
            for (Method m : exif.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class
                        && m.getParameterTypes().length == 3) {
                    XposedBridge.log(TAG + ":   candidate " + m);
                }
            }
        } else {
            XposedBridge.log(TAG + ": gallery watermark name override active ("
                    + hooked + " method(s) -> '" + WATERMARK_DEV + "').");
        }
    }

    /**
     * Hide the camera control-ring UI (settings entry, on-screen guide, and the
     * ring gesture handler) by forcing {@code supportHandleRing()} to false.
     * A single method gates all of them via {@code getOperationCommon().supportHandleRing()}.
     */
    private void hookHideHandleRing(ClassLoader cl) {
        Class<?> theme = XposedHelpers.findClassIfExists(CLS_THEME_LC, cl);
        if (theme == null) {
            XposedBridge.log(TAG + ": " + CLS_THEME_LC + " not found — ring hook skipped "
                    + "(HyperOS build may have renamed the Leica theme class).");
            return;
        }
        XposedHelpers.findAndHookMethod(theme, "supportHandleRing",
                XC_MethodReplacement.returnConstant(false));
        XposedBridge.log(TAG + ": camera control-ring UI hidden (supportHandleRing=false).");
    }

    /**
     * Remove the Leica frames that don't work on this device from the picker,
     * instead of force-enabling them (their working duplicates appear later in the
     * list, so nothing is lost — just the dead entries).
     *
     * The whole frame list flows from one map — {@code PhotoWatermarkViewModel}'s
     * static {@code (PhotoWatermarkViewModel, Map)->void} processor (jadx: m13024R),
     * invoked right after {@code viewModel.f34494d = map}. We hook it and drop, from
     * every category's list, the items whose eligibility verdict
     * {@code xi.y.a(template, categoryTag, photo)} is UnSupport ({@code Di.a$b}) —
     * exactly the ones that would otherwise show "photo is not supported". Both the
     * stored map and the LiveData reference the same object, so the in-place edit
     * hides the frames everywhere.
     */
    private void hookHideUnsupportedFrames(ClassLoader cl) {
        Class<?> vmCls = XposedHelpers.findClassIfExists(CLS_WM_VIEWMODEL, cl);
        Class<?> gate = XposedHelpers.findClassIfExists(CLS_GATE, cl);
        Class<?> verdict = XposedHelpers.findClassIfExists(CLS_VERDICT, cl);
        final Class<?> unsupport = XposedHelpers.findClassIfExists(CLS_UNSUPPORT, cl);
        final Class<?> photoCls = XposedHelpers.findClassIfExists(CLS_PHOTO, cl);
        if (vmCls == null || gate == null || verdict == null || unsupport == null) {
            XposedBridge.log(TAG + ": frame-filter name lookup failed (vm=" + vmCls + " gate="
                    + gate + " verdict=" + verdict + " unsupport=" + unsupport + ") — skipped.");
            return;
        }
        final Method gateMethod = findGateMethod(gate, verdict);
        if (gateMethod == null) {
            XposedBridge.log(TAG + ": eligibility method not found on " + CLS_GATE + " — filter skipped.");
            return;
        }
        gateMethod.setAccessible(true);

        // The map processor: static void (PhotoWatermarkViewModel, Map).
        Method processor = null;
        for (Method m : vmCls.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == vmCls && Map.class.isAssignableFrom(p[1])) {
                processor = m;
                break;
            }
        }
        if (processor == null) {
            XposedBridge.log(TAG + ": watermark map processor not found on " + CLS_WM_VIEWMODEL + " — filter skipped.");
            return;
        }
        final ClassLoader cll = cl;
        XposedBridge.hookMethod(processor, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    filterFrameMap(param.args[0], param.args[1], gateMethod, unsupport, photoCls, cll);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": frame filter error: " + t);
                }
            }
        });
        XposedBridge.log(TAG + ": unsupported-frame filter active on " + processor);
    }

    /** xi.y.a(Hi.a, String, D5.b) -> Di.a, matched by shape (static, 3 args, 2nd String). */
    private Method findGateMethod(Class<?> gate, Class<?> verdict) {
        for (Method m : gate.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != verdict) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[1] == String.class) return m;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void filterFrameMap(Object viewModel, Object mapObj, Method gateMethod,
                                Class<?> unsupport, Class<?> photoCls, ClassLoader cl) {
        if (!(mapObj instanceof Map)) return;
        Map<Object, Object> map = (Map<Object, Object>) mapObj;
        Object photo = findPhoto(viewModel, photoCls, cl);
        if (photo == null) {
            XposedBridge.log(TAG + ": frame filter — photo not found, leaving list unchanged.");
            return;
        }
        int removed = 0, kept = 0;
        for (Object category : new ArrayList<>(map.keySet())) {
            String tag;
            try {
                Object t = XposedHelpers.getObjectField(category, "f34714c");
                tag = (t instanceof String) ? (String) t : firstStringField(category);
            } catch (Throwable ignored) {
                tag = firstStringField(category);
            }
            Object listObj = map.get(category);
            if (!(listObj instanceof List)) continue;
            List<Object> items = (List<Object>) listObj;
            List<Object> keptItems = new ArrayList<>(items.size());
            for (Object item : items) {
                boolean drop = false;
                try {
                    Object template;
                    try {
                        template = XposedHelpers.getObjectField(item, "f34715c");
                    } catch (Throwable e) {
                        template = fieldByType(item, gateMethod.getParameterTypes()[0]);
                    }
                    Object v = gateMethod.invoke(null, template, tag, photo);
                    drop = unsupport.isInstance(v);
                } catch (Throwable ignored) {
                }
                if (drop) removed++;
                else {
                    keptItems.add(item);
                    kept++;
                }
            }
            if (keptItems.size() != items.size()) {
                try {
                    items.clear();
                    items.addAll(keptItems);
                } catch (Throwable t1) {
                    try {
                        map.put(category, keptItems);
                    } catch (Throwable t2) {
                        XposedBridge.log(TAG + ": couldn't apply filter to a category: " + t2);
                    }
                }
            }
        }
        XposedBridge.log(TAG + ": frame filter — removed " + removed + ", kept " + kept + ".");
    }

    /**
     * The edited photo (C0813b). Preferred source is the shared photo-info service
     * — the exact object the app's own eligibility click-gate uses:
     * {@code xj.e$a.c(Th.f.class).obtainPhotoInfo()} -> holder whose C0813b field is
     * the photo. Falls back to scanning the view model if the service names shift.
     */
    private Object findPhoto(Object viewModel, Class<?> photoCls, ClassLoader cl) {
        if (photoCls == null) return null;
        Object p = photoFromService(cl, photoCls);
        if (p != null) return p;
        // Fallback: view model's render data / any photo-typed field.
        try {
            Object rd = XposedHelpers.getObjectField(viewModel, "f34509s");
            p = fieldByType(rd, photoCls);
            if (p != null) return p;
        } catch (Throwable ignored) {
        }
        p = fieldByType(viewModel, photoCls);
        if (p != null) return p;
        for (Field f : viewModel.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object nested = f.get(viewModel);
                if (nested != null && !(nested instanceof String)) {
                    Object pp = fieldByType(nested, photoCls);
                    if (pp != null) return pp;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Photo from the watermark photo-info service locator (xj.e$a.c(Th.f).obtainPhotoInfo()). */
    private Object photoFromService(ClassLoader cl, Class<?> photoCls) {
        try {
            Class<?> registry = XposedHelpers.findClassIfExists("xj.e$a", cl);
            Class<?> iface = XposedHelpers.findClassIfExists("Th.f", cl);
            if (registry == null || iface == null) return null;
            Object service = XposedHelpers.callStaticMethod(registry, "c", iface);
            if (service == null) return null;
            Object photoInfo = XposedHelpers.callMethod(service, "obtainPhotoInfo");
            if (photoInfo == null) return null;
            return fieldByType(photoInfo, photoCls);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": photo service lookup failed: " + t);
            return null;
        }
    }

    /** First non-null instance field assignable to type, searching the class hierarchy. */
    private Object fieldByType(Object obj, Class<?> type) {
        if (obj == null || type == null) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!type.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v != null) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** First non-null String instance field (the category's tag), searching the hierarchy. */
    private String firstStringField(Object obj) {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof String) return (String) v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
