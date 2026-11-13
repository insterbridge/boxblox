package app.boxblox;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;

/**
 * The one hook that makes in-process hosting possible: the framework's own
 * activity factory.
 *
 * <p><b>Why this and not a wrapper activity.</b> The engine's activity is
 * {@code com.roblox.client.startup.MainGameActivity}, which extends AGDK's
 * {@code GameActivity}; {@code GameActivity} creates its own
 * {@code SurfaceView}, calls {@code setContentView} and owns the native window.
 * A different activity class standing in for it cannot host that: the engine's
 * lifecycle methods operate on their own {@code this}, with their own fields and
 * their own window. The guest activity has to be <em>the</em> guest activity,
 * created by the platform.
 *
 * <p><b>What this intercepts, and why both halves are needed.</b>
 * {@code ActivityThread} asks its {@code Instrumentation} to (1) instantiate an
 * activity and (2) run its lifecycle. Overriding the first makes the class
 * resolve from the archive's loader; overriding the second installs the
 * {@link GuestContext} <em>before</em> {@code onCreate}, which is the last
 * moment the engine has not yet asked the platform who it is.
 *
 * <p>Everything else is the base class's own implementation — the methods on
 * {@code Instrumentation} that run a lifecycle are functional and call through
 * to {@code Activity.performCreate} and friends, so nothing is reimplemented
 * here.
 */
final class GuestInstrumentation extends Instrumentation {

    private static final String TAG = "RobloxWrapper";

    private final Instrumentation original;
    private MouseCaptureController mouseCapture;

    GuestInstrumentation(Instrumentation original) {
        this.original = original;
    }

    /** Whether this activity belongs to the guest rather than to the wrapper. */
    private static boolean isGuest(Activity activity) {
        String name = activity.getClass().getName();
        return name.startsWith(RobloxStore.ROBLOX_PACKAGE + ".");
    }

    /**
     * Instantiate from the archive's loader.
     *
     * The platform would otherwise use the loader attached to the package it
     * thinks it is launching. For a class that only exists in the archive's dex,
     * that is the wrong loader, and the activity never resolves.
     */
    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ClassLoader guest = WrapperApplication.get() == null
                ? null
                : WrapperApplication.get().getGuestLoader();
        System.err.println("WRAPPER-DIAG newActivity " + className + " cl=" + cl + " guest=" + guest);
        if (guest != null && className.startsWith(RobloxStore.ROBLOX_PACKAGE + ".")) {
            Diag.set("newActivity(3-arg) " + className);
            Class<?> type = Class.forName(className, true, guest);
            System.err.println("WRAPPER-DIAG resolved " + className + " loader=" + type.getClassLoader());
            try {
                Activity activity = (Activity) type.getDeclaredConstructor().newInstance();
                // The context has to be installed here, not in
                // callActivityOnCreate: by then the platform has already
                // attached the activity and cached its Resources, so
                // attachBaseContext returns without changing them (measured:
                // "installed=false resources=host"). This is the last point
                // before attach, so it is where the swap has to happen.
                // Before the platform attaches: no window exists yet, so the
                // theme written here is the one Activity.attach builds.
                installGuestContext(activity);
                Diag.set("pre-attach theme done; window="
                        + (activity.getWindow() == null ? "null" : "created"));
                return activity;
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new InstantiationException("constructor threw: " + e.getCause());
            } catch (NoSuchMethodException e) {
                throw new InstantiationException("no no-arg constructor: " + e.getMessage());
            }
        }
        if (original != null) {
            return original.newActivity(cl, className, intent);
        }
        return super.newActivity(cl, className, intent);
    }

    /**
     * The overload the platform actually uses on API 28+.
     *
     * {@code ActivityThread.performLaunchActivity} calls this one, not the
     * {@code (ClassLoader, String, Intent)} form, which is why installing the
     * guest context from the other overload had no effect. This is the last
     * point before the activity is attached, so it is where the context and the
     * theme have to be installed: {@code setTheme} after attach cannot change
     * the already-created window theme.
     */
    @Override
    public Activity newActivity(Class<?> clazz, Context context, IBinder token,
                                Application application, Intent intent, ActivityInfo info,
                                CharSequence title, Activity parent, String id,
                                Object lastNonConfigurationInstance) throws IllegalAccessException, InstantiationException {
        Diag.set("newActivity(10-arg) " + clazz.getName());
        Activity activity = super.newActivity(clazz, context, token, application, intent, info,
                title, parent, id, lastNonConfigurationInstance);
        if (activity != null && isGuest(activity)) {
            installGuestContext(activity);
        }
        return activity;
    }

    /**
     * Install the guest context, then run the real {@code onCreate}.
     *
     * This is the earliest point at which the activity object exists but its
     * {@code onCreate} has not run, so it is the last chance to answer the
     * engine's {@code PlatformParams} questions from the archive.
     */
    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        Diag.set("callActivityOnCreate " + activity.getClass().getName()
                + " isGuest=" + isGuest(activity));
        if (isGuest(activity)) {
            installGuestContext(activity);
            // The guest's own onCreate is what loads the engine:
            // com.roblox.client.startup.a.t() calls
            // System.loadLibrary("roblox") from a class the archive defines,
            // which is why the load is left to it (see loadEngine()).
            loadEngine();
        }
        super.callActivityOnCreate(activity, icicle);
        if (isGuest(activity)) {
            // A guest session has now started in this process. Recorded here,
            // not in prepare(): prepare also runs from Application.onCreate, so
            // marking it there made the very first launch look like a relaunch
            // and restart the process in a loop.
            WrapperApplication app = WrapperApplication.get();
            if (app != null) {
                app.markEngineUsed();
            }
            // The engine's JNI_OnLoad has now run, so its natives are
            // registered and the wrapper can take the lock getter over.
            try {
                installEngineHooks(activity);
            } catch (Throwable t) {
                // Optional enhancement: never fail guest creation.
                Diag.set("mouse capture unavailable: install aborted: " + t);
            }
            installButtonObserver(activity);
        }
    }

    /**
     * Hand the engine load to the guest, exactly once.
     *
     * The library is not mapped here: Roblox's own startup class performs the
     * {@code System.loadLibrary("roblox")} from the archive's loader, which is
     * the loader its {@code JNI_OnLoad} needs. This method only records, once,
     * that the guest is now responsible for it.
     */
    private void loadEngine() {
        WrapperApplication app = WrapperApplication.get();
        if (app == null || app.getBuild() == null || app.getGuestLoader() == null) {
            return;
        }
        if (loaded) {
            return;
        }
        loaded = true;

        // The engine is already mapped by WrapperApplication.prepare, before the
        // guest Application runs (its onCreate needs the natives immediately).
        // Loading it again here would be a second ART entry keyed by the same
        // absolute path, which the linker dedupes — but the intent is clearer if
        // there is exactly one load site, so this one is gone.
        Diag.set("engine load left to prepare()");
    }

    private static volatile boolean loaded;


    /**
     * Gate the original engine lock getter without replacing its input handlers.
     *
     * <p>This is an optional enhancement: no failure here may escape guest
     * creation. The diagnostic/native sink is installed first so every later
     * install outcome is recorded, and a failure leaves the engine's own
     * behavior untouched.
     */
    private void installEngineHooks(Activity activity) {
        WrapperApplication app = WrapperApplication.get();
        boolean enabled = false;
        try {
            enabled = new FlagStore(activity).getBool("wrapper.mouse_lock", false);
        } catch (Throwable t) {
            Diag.set("mouse capture: feature flag unavailable: " + t);
        }

        if (mouseCapture != null && !mouseCapture.isFor(activity)) {
            // The guest window changed (activity recreation). Cancel the old
            // window's tracking before binding a controller to the new decor.
            try {
                mouseCapture.cancel("activity changed", true);
            } catch (Throwable t) {
                Diag.set("mouse capture: old window cancel failed: " + t);
            }
            mouseCapture = null;
        }
        if (mouseCapture == null) {
            try {
                mouseCapture = new MouseCaptureController(activity, enabled);
            } catch (Throwable t) {
                Diag.set("mouse capture unavailable: controller creation failed: " + t);
            }
        }
        if (mouseCapture != null) {
            try {
                // Before setMouseLockEnabled/installMouseLockHook, deliberately.
                mouseCapture.installTrace();
            } catch (Throwable t) {
                Diag.set("mouse capture unavailable: trace install failed: " + t);
            }
        }

        try {
            EngineBridge.setMouseLockEnabled(enabled);
        } catch (Throwable t) {
            Diag.set("mouse capture unavailable: setMouseLockEnabled failed: " + t);
            return;
        }
        Diag.set("mouse capture: feature " + (enabled ? "enabled" : "disabled")
                + (enabled ? "" : " (stock engine behavior)"));

        ClassLoader loader = app == null ? null : app.getGuestLoader();
        if (loader == null) {
            Diag.set("mouse capture unavailable: no guest loader");
            return;
        }
        Class<?> input;
        try {
            input = Class.forName("com.roblox.engine.jni.NativeInputInterface", true, loader);
        } catch (Throwable t) {
            Diag.set("mouse capture unsupported: NativeInputInterface missing: " + t);
            return;
        }
        boolean installed;
        try {
            installed = EngineBridge.installMouseLockHook(input);
        } catch (Throwable t) {
            Diag.set("mouse capture unavailable: installMouseLockHook failed: " + t);
            return;
        }
        String state = safeMouseState();
        int behavior = safeMouseBehavior();
        if (!installed) {
            Diag.set(enabled
                    ? "mouse capture unavailable: getter hook rejected; state=" + state
                    : "mouse capture disabled: optional getter hook not installed; state=" + state);
            return;
        }
        Diag.set("native mouse input preserved; feature " + (enabled ? "enabled" : "disabled")
                + "; getter hooked; behavior=" + behavior + "; state=" + state);
        if (enabled && behavior == -1) {
            Diag.set("mouse capture " + classifyMouseState(state)
                    + ": behavior unavailable; state=" + state);
        }
    }

    private static String classifyMouseState(String state) {
        String normalized = state == null ? "" : state.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("fingerprint")
                || normalized.contains("windowinputstate")
                || normalized.contains("out of range")) {
            return "unsupported";
        }
        return "unavailable";
    }

    private static String safeMouseState() {
        try {
            return String.valueOf(EngineBridge.mouseStateResult());
        } catch (Throwable t) {
            return "unavailable: " + t;
        }
    }

    private static int safeMouseBehavior() {
        try {
            return EngineBridge.mouseBehavior();
        } catch (Throwable t) {
            return -1;
        }
    }


    @Override
    public void callActivityOnResume(Activity activity) {
        if (isGuest(activity)) {
            boolean enabled = false;
            try {
                enabled = new FlagStore(activity).getBool("wrapper.mouse_lock", false);
                EngineBridge.setMouseLockEnabled(enabled);
            } catch (Throwable t) {
                Diag.set("mouse capture: resume enable failed: " + t);
            }
            if (mouseCapture != null) {
                mouseCapture.onResume(enabled);
            }
        }
        super.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        if (isGuest(activity)) {
            if (mouseCapture != null) {
                // One cancel path: stops frames, cancels pending request
                // tracking, releases actual capture, clears gesture state and
                // releases native tracking.
                mouseCapture.cancel("pause", true);
            } else if (activity.getWindow() != null) {
                releaseMouseCapture(activity.getWindow().getDecorView());
                try {
                    EngineBridge.releaseMouseLock("pause");
                } catch (Throwable t) {
                    // Best effort; the focus-loss path releases too.
                }
            }
        }
        super.callActivityOnPause(activity);
    }

    /**
     * Watch the secondary button without displacing the engine's listeners.
     *
     * <p>{@code Window.Callback} is upstream of the view hierarchy: the
     * framework hands it every generic-motion event before any view sees it, so
     * wrapping it observes the button while the engine keeps its own
     * {@code setOnGenericMotionListener} and {@code setOnCapturedPointerListener}
     * exactly as installed. Replacing those was the earlier mistake, and it cost
     * the engine all mouse buttons and the wheel.
     */
    private void installButtonObserver(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        Window.Callback current = window.getCallback();
        if (current instanceof ButtonObserver) {
            return;
        }
        if (current == null) {
            Diag.set("button observer skipped: no window callback");
            return;
        }
        ButtonObserver observer = new ButtonObserver(current, mouseCapture);
        window.setCallback(observer);
        observer.attachKeyboard(window.getDecorView());
        Diag.set("button observer installed");
    }

    private static void releaseMouseCapture(android.view.View view) {
        // Only the view that actually holds capture, never a subtree walk: the
        // engine captures on its own surface, and releasing another view's
        // capture is not this method's to do.
        if (view.hasPointerCapture()) {
            view.releasePointerCapture();
        }
    }

    private void installGuestContext(Activity activity) {
        WrapperApplication app = WrapperApplication.get();
        RobloxStore.Build build = app == null ? null : app.getBuild();
        if (build == null) {
            Log.w(TAG, "guest activity created with no verified build");
            return;
        }
        try {
            Context base = activity.getBaseContext();
            Resources resources = new ArchiveResources(build, base.getResources()).open();
            GuestContext context = new GuestContext(base, build, resources, packageInfo(build, base));
            context.logIdentity();
            boolean installed = GuestContext.installOn(activity, context);
            // The activity's own package name is what requestPermissions sends
            // to the system server; it has to be this app's, not the archive's.
            GuestContext.adoptHostIdentity(activity, base.getPackageName());
            GuestContext.logIdentityForPermissions(activity);
            logDesktopProbe(activity);
            logEngineIdentity(activity);
            Diag.set("guest context on " + activity.getClass().getSimpleName()
                    + ": installed=" + installed
                    + " resources=" + (activity.getResources() == resources ? "archive" : "host"));
        } catch (Throwable t) {
            Log.w(TAG, "could not install the guest context", t);
        }
    }

    /**
     * Record the identity the guest will hand the engine, before it is consumed.
     *
     * <p>{@code MainGameActivity.E2} builds {@code InitParams} from statics on
     * the guest's config class {@code sg.a1}: {@code j} is the User-Agent
     * (composed from real display geometry and {@code Build.VERSION}, a path
     * the PackageManager spoof never touches) and {@code k} is {@code isTablet}.
     * Both are plain statics set once during the guest Application's init, so
     * they are stable by the time any activity runs — which makes this the one
     * place the wrapper can see, and later answer for, the whole identity.
     */
    private static void logEngineIdentity(Activity activity) {
        WrapperApplication app = WrapperApplication.get();
        ClassLoader loader = app == null ? null : app.getGuestLoader();
        if (loader == null) {
            return;
        }
        try {
            Class<?> config = Class.forName("sg.a1", true, loader);
            StringBuilder line = new StringBuilder("engine identity:");
            for (String field : new String[]{"j", "k", "l", "m"}) {
                java.lang.reflect.Field f = config.getDeclaredField(field);
                f.setAccessible(true);
                Object value = f.get(null);
                line.append(' ').append(field).append('=')
                        .append(value == null ? "null" : value.toString());
            }
            Diag.set(line.toString());
            applyDesktopIdentity(config, activity);
        } catch (Throwable t) {
            Diag.set("engine identity unavailable: " + t);
        }
    }

    /**
     * Answer the device-class question the playability gate reads, as a tablet.
     *
     * <p>Measured, phone mode: the User-Agent cached in {@code sg.a1.j} carries
     * the token {@code Phone Hybrid}, and {@code isTablet} is false — and
     * Combat Initiation renders "not available on your device" while age-gated
     * experiences render "Unlock", so the gate is device-class, not account.
     * Sober plays the same APK with a desktop-sized screen, which is the
     * tablet arm of the same classification. The token and the flag are plain
     * statics on a guest class, read at {@code InitParams} construction, so
     * rewriting them here is an in-process answer to a question the guest asks
     * itself — the same standing as the PackageManager spoof.
     */
    private static void applyDesktopIdentity(Class<?> config, Context context) {
        FlagStore flags = new FlagStore(context);
        if (!SessionMode.desktopIdentity(SessionMode.read(flags))) {
            return;
        }
        try {
            java.lang.reflect.Field ua = config.getDeclaredField("j");
            ua.setAccessible(true);
            String current = (String) ua.get(null);
            // Sober's session sends no device parenthetical at all — its
            // runtime reports no display info — and Combat Initiation is
            // playable there while the same "Tablet Hybrid" token carrying a
            // phone fingerprint is not. The fingerprint is what the gate
            // reads, so the whole string is restated without it, keeping the
            // app's own version number.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("ROBLOX Android App (\\S+) (\\S+) Hybrid")
                    .matcher(String.valueOf(current));
            if (m.find()) {
                String version = m.group(1);
                ua.set(null, "Mozilla/5.0 AppleWebKit/605.1.15 (KHTML, like Gecko)  "
                        + "ROBLOX Android App " + version + " Tablet Hybrid()  GooglePlayStore "
                        + "RobloxApp/" + version + "(GlobalDist; GooglePlayStore)");
            }
            java.lang.reflect.Field tablet = config.getDeclaredField("k");
            tablet.setAccessible(true);
            tablet.setBoolean(null, true);
            Diag.set("desktop identity applied: ua="
                    + String.valueOf(ua.get(null)).contains("Tablet Hybrid")
                    + " isTablet=true");
        } catch (Throwable t) {
            Diag.set("desktop identity failed: " + t);
        }
    }

    /**
     * Log what the guest's PackageManager actually answers.
     *
     * <p>The desktop identity rests entirely on hasSystemFeature("android.hardware.type.pc"),
     * and there is no way to see the answer from the outside: the engine reads it
     * once during PlatformParams construction and never logs it. This records the
     * same two questions at the same point, so a wrong answer is visible.
     */
    private static void logDesktopProbe(Activity activity) {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            Diag.set("desktop probe: pm=" + pm.getClass().getName()
                    + " session=" + SessionMode.read(new FlagStore(activity))
                    + " type.pc=" + pm.hasSystemFeature("android.hardware.type.pc")
                    + " touchscreen=" + pm.hasSystemFeature("android.hardware.touchscreen")
                    + " keyboard=" + pm.hasSystemFeature("android.hardware.keyboard")
                    + " leanback=" + pm.hasSystemFeature("android.software.leanback"));
        } catch (Throwable t) {
            Diag.set("desktop probe failed: " + t);
        }
    }

    private static PackageInfo packageInfo(RobloxStore.Build build, Context base) {
        PackageInfo info = new PackageInfo();
        info.packageName = build.getPackageName();
        info.versionName = build.getVersionName();
        info.versionCode = (int) build.getVersionCode();
        info.applicationInfo = base.getApplicationInfo();
        return info;
    }

    /**
     * Put this instrumentation in front of the framework's.
     *
     * {@code ActivityThread.mInstrumentation} is what every activity goes
     * through; replacing it is the documented-in-practice seam every in-process
     * host uses. It is one reflective write, made before the first activity is
     * created.
     */
    static boolean install(android.app.Application application) {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method current =
                    activityThreadClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) {
                Log.w(TAG, "no ActivityThread yet");
                return false;
            }
            java.lang.reflect.Field field = null;
            for (Class<?> c = activityThreadClass; c != null; c = c.getSuperclass()) {
                try {
                    field = c.getDeclaredField("mInstrumentation");
                    break;
                } catch (NoSuchFieldException ignored) {
                    // keep walking
                }
            }
            if (field == null) {
                Log.w(TAG, "ActivityThread.mInstrumentation is not reachable");
                return false;
            }
            field.setAccessible(true);
            Object existing = field.get(thread);
            if (existing instanceof GuestInstrumentation) {
                return true;
            }
            field.set(thread, new GuestInstrumentation((Instrumentation) existing));
            Log.i(TAG, "instrumentation installed (was " + existing + ')');
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not install the instrumentation", t);
            return false;
        }
    }
}
