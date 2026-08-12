package io.github.mexusbg.leicaframes;

import android.util.Log;
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

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Modern (libxposed API 100+) Xposed module for the Xiaomi 17 Ultra (nezha) Leica tweaks.
 *
 * <ul>
 *   <li><b>Frames</b> — removes the Leica gallery frames that don't work on this device
 *       from the picker (their working duplicates remain later in the list).</li>
 *   <li><b>Ring</b> — hides the Leica camera-island "Camera ring" settings (in the camera
 *       and in system Settings); the Photography-Kit "Camera Grip" is left working.</li>
 *   <li><b>Watermark</b> — renames the device name to "XIAOMI 17 Ultra by Leica" in the
 *       camera and the gallery editor.</li>
 * </ul>
 *
 * Hooks use the interceptor-chain API: {@code hook(method).intercept(chain -> result)}.
 * Returning a value without calling {@code chain.proceed()} replaces the result and skips
 * the original. libxposed provides no XposedHelpers, so reflection is done by hand below.
 */
public class HookEntry extends XposedModule {

    static final String TAG = "LeicaFramePatcher";

    private static final String PKG_MEDIAEDITOR = "com.miui.mediaeditor";
    private static final String PKG_CAMERA = "com.android.camera";
    private static final String PKG_SETTINGS = "com.android.settings";

    // MiuiCamera Leica-theme (LCC) operation. Real (non-obfuscated) name.
    private static final String CLS_THEME_LC =
            "com.android.camera2.compat.theme.custom.lc.MiThemeOperationCommonLC";
    // System Settings "Camera ring" (camera_mr) gate. Real framework name.
    private static final String CLS_INPUT_FEATURE = "miui.hardware.input.InputFeature";

    // mediaeditor obfuscated names (OS3.0.332 / mediaeditor 6.x).
    private static final String CLS_GATE = "xi.y";        // eligibility gate, method a()
    private static final String CLS_VERDICT = "Di.a";     // verdict base type
    private static final String CLS_UNSUPPORT = "Di.a$b"; // UnSupport verdict
    private static final String CLS_PHOTO = "D5.b";       // photo/EXIF info (C0813b)
    private static final String CLS_WM_EXIF = "mj.g";     // watermark EXIF/device-name helper
    private static final String CLS_WM_VIEWMODEL =
            "com.miui.mediaeditor.photo.watermark.PhotoWatermarkViewModel";

    // Watermark device name. The frame renders a brand line ("XIAOMI", logo font) ahead
    // of the device text, so the device slot omits the leading "Xiaomi".
    private static final String WATERMARK_MFR = "";
    private static final String WATERMARK_DEV = "17 Ultra by Leica";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader cl = param.getDefaultClassLoader();
        if (cl == null) return;

        if (PKG_MEDIAEDITOR.equals(pkg)) {
            safe("frame-filter", () -> hookHideUnsupportedFrames(cl));
            safe("gallery watermark-name", () -> hookGalleryWatermarkName(cl));
        } else if (PKG_CAMERA.equals(pkg)) {
            safe("ring", () -> hookHideHandleRing(cl));
            String apk = param.getApplicationInfo() != null ? param.getApplicationInfo().sourceDir : null;
            if (apk != null) {
                final String apkPath = apk;
                safe("camera watermark-name", () -> hookCameraWatermarkName(cl, apkPath));
            }
        } else if (PKG_SETTINGS.equals(pkg)) {
            safe("camera-ring entry", () -> hookHideCameraRingEntry(cl));
        }
    }

    private interface HookInstall {
        void run() throws Throwable;
    }

    private void safe(String what, HookInstall install) {
        try {
            install.run();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to install " + what + " hook: " + t);
        }
    }

    // ---------------------------------------------------------------- ring (camera) -----

    /**
     * Hide the in-camera control-ring UI by forcing {@code supportHandleRing()} to false.
     * One method gates the settings entry, the on-screen guide, and the gesture handler.
     */
    private void hookHideHandleRing(ClassLoader cl) {
        Class<?> theme = findClass(CLS_THEME_LC, cl);
        if (theme == null) {
            log(Log.WARN, TAG, CLS_THEME_LC + " not found — ring hook skipped.");
            return;
        }
        Method m = findMethod(theme, "supportHandleRing", 0);
        if (m == null) {
            log(Log.WARN, TAG, "supportHandleRing() not found — ring hook skipped.");
            return;
        }
        hook(m).intercept(chain -> Boolean.FALSE);
        log(Log.INFO, TAG, "camera control-ring UI hidden (supportHandleRing=false).");
    }

    // ------------------------------------------------------------- ring (settings) ------

    /**
     * Hide the system-Settings "Camera ring" (camera_mr) entry by forcing
     * {@code miui.hardware.input.InputFeature.supportCameraMRFunction()} to false.
     * Runs before SettingsFeatures' static init, so the header is dropped. Does not touch
     * the Photography-Kit "Camera Grip" (a separate feature).
     */
    private void hookHideCameraRingEntry(ClassLoader cl) {
        Class<?> inputFeature = findClass(CLS_INPUT_FEATURE, cl);
        if (inputFeature == null) {
            log(Log.WARN, TAG, CLS_INPUT_FEATURE + " not found — camera-ring entry hook skipped.");
            return;
        }
        Method m = findMethod(inputFeature, "supportCameraMRFunction", 0);
        if (m == null) {
            log(Log.WARN, TAG, "supportCameraMRFunction() not found — camera-ring entry hook skipped.");
            return;
        }
        hook(m).intercept(chain -> Boolean.FALSE);
        log(Log.INFO, TAG, "system-settings Camera ring entry hidden (supportCameraMRFunction=false).");
    }

    // ------------------------------------------------------- watermark name (gallery) ---

    /**
     * Gallery watermark device name. {@code mj.g.n()} (model line) and {@code mj.g.k()}
     * (manufacturer+model) are forced to {@link #WATERMARK_DEV}; the frame prepends its own
     * "XIAOMI" brand line. Obfuscated single-letter names — logged for re-mapping if gone.
     */
    private void hookGalleryWatermarkName(ClassLoader cl) {
        Class<?> exif = findClass(CLS_WM_EXIF, cl);
        if (exif == null) {
            log(Log.WARN, TAG, CLS_WM_EXIF + " not found — gallery name hook skipped.");
            return;
        }
        int hooked = 0;
        for (Method m : exif.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != String.class) continue;
            if (m.getParameterTypes().length != 3) continue;
            String n = m.getName();
            if (!"n".equals(n) && !"k".equals(n)) continue;
            hook(m).intercept(chain -> WATERMARK_DEV);
            hooked++;
            log(Log.INFO, TAG, "hooked gallery watermark-name method " + m);
        }
        if (hooked == 0) {
            log(Log.WARN, TAG, "no gallery watermark-name method matched on " + CLS_WM_EXIF
                    + " — dumping static 3-arg String methods:");
            for (Method m : exif.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class
                        && m.getParameterTypes().length == 3) {
                    log(Log.WARN, TAG, "  candidate " + m);
                }
            }
        } else {
            log(Log.INFO, TAG, "gallery watermark name override active (" + hooked
                    + " method(s) -> '" + WATERMARK_DEV + "').");
        }
    }

    // -------------------------------------------------------- watermark name (camera) ---

    /**
     * Camera watermark device name. A family of provider classes (subclasses of C15705)
     * return a {@code SparseArray<String[]>} = [manufacturer, device] from {@code mo26211d()}.
     * Class + method names are non-ASCII obfuscated, so they are located at runtime with
     * DexKit (SparseArray-returning, zero-arg, with the SparseArray&lt;String[]&gt; signature
     * annotation) and forced to {@link #WATERMARK_MFR} / {@link #WATERMARK_DEV}.
     */
    private void hookCameraWatermarkName(final ClassLoader cl, final String apkPath) {
        new Thread(() -> {
            try {
                System.loadLibrary("dexkit");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "could not load libdexkit — camera watermark skipped: " + t);
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
                int hooked = 0;
                for (MethodData md : providers) {
                    try {
                        Method m = md.getMethodInstance(cl);
                        hook(m).intercept(chain -> {
                            SparseArray<String[]> sa = new SparseArray<>(1);
                            sa.put(0, new String[]{WATERMARK_MFR, WATERMARK_DEV});
                            return sa;
                        });
                        hooked++;
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "failed to hook watermark provider " + md + ": " + t);
                    }
                }
                log(Log.INFO, TAG, "camera watermark providers hooked = " + hooked
                        + " ('" + WATERMARK_MFR + "' / '" + WATERMARK_DEV + "').");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "DexKit camera watermark search failed: " + t);
            }
        }, "LeicaFP-DexKit").start();
    }

    // ------------------------------------------------------------- frames (gallery) -----

    /**
     * Remove the Leica frames that don't work on this device from the picker. The whole
     * list flows from one map — {@code PhotoWatermarkViewModel}'s static
     * {@code (PhotoWatermarkViewModel, Map)->void} processor — so we intercept it and drop,
     * from each category, the items whose eligibility verdict {@code xi.y.a(template, tag, photo)}
     * is UnSupport ({@code Di.a$b}). The stored map and the LiveData share the object, so the
     * in-place edit hides the frames everywhere.
     */
    private void hookHideUnsupportedFrames(final ClassLoader cl) {
        Class<?> vmCls = findClass(CLS_WM_VIEWMODEL, cl);
        Class<?> gate = findClass(CLS_GATE, cl);
        Class<?> verdict = findClass(CLS_VERDICT, cl);
        final Class<?> unsupport = findClass(CLS_UNSUPPORT, cl);
        final Class<?> photoCls = findClass(CLS_PHOTO, cl);
        if (vmCls == null || gate == null || verdict == null || unsupport == null) {
            log(Log.WARN, TAG, "frame-filter name lookup failed (vm=" + vmCls + " gate=" + gate
                    + " verdict=" + verdict + " unsupport=" + unsupport + ") — skipped.");
            return;
        }
        final Method gateMethod = findGateMethod(gate, verdict);
        if (gateMethod == null) {
            log(Log.WARN, TAG, "eligibility method not found on " + CLS_GATE + " — filter skipped.");
            return;
        }
        gateMethod.setAccessible(true);

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
            log(Log.WARN, TAG, "watermark map processor not found on " + CLS_WM_VIEWMODEL + " — filter skipped.");
            return;
        }
        hook(processor).intercept(chain -> {
            try {
                filterFrameMap(chain.getArg(0), chain.getArg(1), gateMethod, unsupport, photoCls, cl);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "frame filter error: " + t);
            }
            return chain.proceed();
        });
        log(Log.INFO, TAG, "unsupported-frame filter active on " + processor);
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
            log(Log.WARN, TAG, "frame filter — photo not found, leaving list unchanged.");
            return;
        }
        int removed = 0, kept = 0;
        for (Object category : new ArrayList<>(map.keySet())) {
            String tag = getStringField(category, "f34714c");
            if (tag == null) tag = firstStringField(category);
            Object listObj = map.get(category);
            if (!(listObj instanceof List)) continue;
            List<Object> items = (List<Object>) listObj;
            List<Object> keptItems = new ArrayList<>(items.size());
            for (Object item : items) {
                boolean drop = false;
                try {
                    Object template = getField(item, "f34715c");
                    if (template == null) template = fieldByType(item, gateMethod.getParameterTypes()[0]);
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
                        log(Log.ERROR, TAG, "couldn't apply filter to a category: " + t2);
                    }
                }
            }
        }
        log(Log.INFO, TAG, "frame filter — removed " + removed + ", kept " + kept + ".");
    }

    /** Edited photo (C0813b) from the shared photo-info service — same object the app uses. */
    private Object findPhoto(Object viewModel, Class<?> photoCls, ClassLoader cl) {
        if (photoCls == null) return null;
        Object p = photoFromService(cl, photoCls);
        if (p != null) return p;
        try {
            Object rd = getField(viewModel, "f34509s");
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
            Class<?> registry = findClass("xj.e$a", cl);
            Class<?> iface = findClass("Th.f", cl);
            if (registry == null || iface == null) return null;
            Object service = callStatic(registry, "c", new Class<?>[]{Class.class}, iface);
            if (service == null) return null;
            Object photoInfo = callInstance(service, "obtainPhotoInfo");
            if (photoInfo == null) return null;
            return fieldByType(photoInfo, photoCls);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "photo service lookup failed: " + t);
            return null;
        }
    }

    // ----------------------------------------------------------------- reflection -------

    private Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Method by name and parameter count (searches the class only). */
    private Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private Object callStatic(Class<?> cls, String name, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method m = cls.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private Object callInstance(Object obj, String name) throws Exception {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    return m.invoke(obj);
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** Instance field by exact name, searching the class hierarchy. */
    private Object getField(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private String getStringField(Object obj, String name) {
        Object v = getField(obj, name);
        return (v instanceof String) ? (String) v : null;
    }

    /** First non-null instance field assignable to type, searching the hierarchy. */
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

    /** First non-null String instance field, searching the hierarchy. */
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
