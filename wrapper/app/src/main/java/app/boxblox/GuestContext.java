package app.boxblox;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;

/**
 * The {@code Context} the guest is handed, in place of the activity the
 * platform created.
 *
 * <b>Why the activity and not the application.</b> The engine builds its
 * {@code PlatformParams} from the <em>activity</em>:
 * {@code wh/i0.q()} is {@code ExperienceSession.H()}, which returns the
 * {@code Activity}, and {@code p()} then calls
 * {@code q().getPackageManager().hasSystemFeature(...)} on it. Answering at the
 * application would be answering a different question than the one asked.
 *
 * <b>Why a {@code ContextWrapper}.</b> The real activity is the base, so
 * window, input channel, view root and lifecycle still come from the platform.
 * Only the questions whose answers belong to the guest are redirected: its
 * resources, its package identity, and its private data directory.
 */
final class GuestContext extends ContextWrapper {

    private static final String TAG = "RobloxWrapper";

    private final RobloxStore.Build build;
    private final Resources resources;
    private ShadowPackageManager packageManager;
    private final PackageInfo packageInfo;
    private Resources.Theme theme;

    GuestContext(Context base, RobloxStore.Build build, Resources resources, PackageInfo pkg) {
        super(base);
        this.build = build;
        this.resources = resources;
        this.packageInfo = pkg;
        FlagStore flags = new FlagStore(base);
        this.packageManager = new ShadowPackageManager(base.getPackageName(), base.getPackageManager(),
                build, pkg, SessionMode.read(flags));
    }

    @Override
    public Context getApplicationContext() {
        // The guest's own "application context" is still this wrapper: handing
        // back the host Application would drop the shadow the moment any guest
        // code calls getApplicationContext() and caches the result.
        return this;
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    /**
     * The guest's theme, built on the archive's resources.
     *
     * <p>Without this override the inherited implementation delegates to
     * {@code mBase.getTheme()}, and the activity's base is now this object — an
     * immediate cycle. It is also the theme the activity ends up adopting:
     * {@code ContextThemeWrapper.initializeTheme} seeds the activity's theme with
     * {@code getBaseContext().getTheme()}, so whatever is returned here is what
     * AppCompat validates.
     */
    @Override
    public Resources.Theme getTheme() {
        if (theme == null) {
            Resources.Theme fresh = resources.newTheme();
            int style = resources.getIdentifier("Theme.Roblox", "style", build.getPackageName());
            if (style != 0) {
                fresh.applyStyle(style, true);
            }
            theme = fresh;
        }
        return theme;
    }

    @Override
    public PackageManager getPackageManager() {
        String mode = SessionMode.read(new FlagStore(getBaseContext()));
        if (!packageManager.matches(build, mode)) {
            packageManager = new ShadowPackageManager(getBaseContext().getPackageName(),
                    getBaseContext().getPackageManager(), build, packageInfo, mode);
        }
        return packageManager;
    }

    /**
     * Make the activity's own identity match the process it runs in.
     *
     * <p>{@code Activity.getPackageName()} does not consult the context at all:
     * it reads {@code mActivityInfo.packageName}, which the platform filled in
     * from this app's manifest. Because the guest activity is declared under
     * {@code com.roblox.client} so the platform would instantiate it, that field
     * says {@code com.roblox.client} while the process runs as
     * {@code app.boxblox}.
     *
     * <p>That mismatch is fatal as soon as the guest asks for a runtime
     * permission: {@code Activity.requestPermissions} puts the package name into
     * an intent and calls {@code startActivity}, the system server checks it
     * against the calling uid, and refuses with
     * {@code SecurityException: Permission Denial: package=com.roblox.client does
     * not belong to uid=…}. The exception escapes into a JNI scope and the engine
     * aborts (measured, right after login).
     *
     * <p>Rewriting the field is safe here: it is a plain reference, it is only
     * read for identity, and the archive's own resources are resolved through
     * {@link #getResources()} rather than through this.
     */
    /** Log the identity values the permission path actually reads. */
    static void logIdentityForPermissions(Activity activity) {
        try {
            java.lang.reflect.Field f = android.app.Activity.class
                    .getDeclaredField("mActivityInfo");
            f.setAccessible(true);
            Object info = f.get(activity);
            String infoPkg = info instanceof android.content.pm.ActivityInfo
                    ? ((android.content.pm.ActivityInfo) info).packageName : "?";
            android.util.Log.i("RobloxWrapper",
                    "permission identity: activity.getPackageName()=" + activity.getPackageName()
                            + " mActivityInfo.packageName=" + infoPkg
                            + " component=" + activity.getComponentName());
        } catch (Throwable t) {
            android.util.Log.w("RobloxWrapper", "identity probe failed", t);
        }
    }

    static void adoptHostIdentity(Activity activity, String hostPackage) {
        try {
            java.lang.reflect.Field f = android.app.Activity.class
                    .getDeclaredField("mActivityInfo");
            f.setAccessible(true);
            Object info = f.get(activity);
            if (info instanceof android.content.pm.ActivityInfo) {
                ((android.content.pm.ActivityInfo) info).packageName = hostPackage;
                Diag.set("activity identity -> " + hostPackage);
            }
        } catch (Throwable t) {
            Diag.set("could not adopt host identity: " + t);
        }
    }

    /**
     * The REAL package name, not the archive's.
     *
     * <p>Every value the guest reads for display or for its own bookkeeping can
     * be shadowed, but the package name is used to build {@code ComponentName}s
     * that cross into the system server, which validates them against the
     * caller's UID. Shadowing it produces
     * {@code SecurityException: Package com.roblox.client does not belong to
     * <uid>} from {@code AppWidgetManager.getAppWidgetIds} (measured).
     *
     * <p>Archive resource lookups do not depend on this method: they use
     * {@code build.getPackageName()} directly.
     */
    @Override
    public String getPackageName() {
        return getBaseContext().getPackageName();
    }

    /**
     * Return the real package name to platform services that validate ownership.
     *
     * <p>The guest builds components under its own package name —
     * {@code AppWidgetManager.getAppWidgetIds(new ComponentName(RobloxApplication.f(),
     * RecentlyPlayedWidgetProvider))} — and the system server rejects a component
     * whose package the calling UID does not own:
     * {@code SecurityException: Package com.roblox.client does not belong to
     * <uid>} (measured). Those calls need the real package, which is what the
     * base context answers, so the shadow is not applied to them.
     *
     * <p>The distinction is made by call site rather than by rewriting the
     * component, because {@code AppWidgetManager} is final and cannot be
     * wrapped.
     */
    String hostPackageName() {
        return getBaseContext().getPackageName();
    }

    /**
     * Replace an activity's base context with a guest one and install the
     * archive's theme on it.
     *
     * <p>Called from {@code newActivity}, i.e. before the platform attaches the
     * activity, so {@code attachBaseContext} is still honoured.
     */
    static boolean installOn(Activity activity, GuestContext context) {
        boolean ok = false;
        try {
            // ContextThemeWrapper refuses a second attach ("Base context already
            // set"), so mBase is cleared first.
            try { setField(activity, "mBase", null); } catch (Throwable ignored) { }
            java.lang.reflect.Method attach = ContextWrapper.class
                    .getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(activity, context);
            ok = true;
        } catch (Throwable t) {
            Diag.set("attachBaseContext: " + t + " cause=" + t.getCause());
        }
        try {
            setField(activity, "mBase", context);
            ok = true;
        } catch (Throwable t) {
            Diag.set("mBase swap failed: " + t);
        }
        try {
            setField(activity, "mResources", context.getResources());
            ok = true;
        } catch (Throwable t) {
            Diag.set("mResources swap failed: " + t);
        }
        // The activity's theme has to be the archive's: Roblox's MainGameActivity
        // requires an AppCompat descendant, and its own Theme.Roblox is one. The
        // wrapper's manifest cannot name it — the resource exists only in the
        // archive — so it is applied here.
        try {
            int theme = context.getResources().getIdentifier(
                    "Theme.Roblox", "style", context.build.getPackageName());
            if (theme == 0) {
                Diag.set("archive has no Theme.Roblox");
            } else {
                // setTheme(Resources.Theme) — the object overload — installs the
                // archive's theme object itself. API 35's ContextThemeWrapper
                // declares no theme cache field (measured: only mInflater,
                // mResources, mThemeResource), so an int id would be resolved
                // against the activity's own Resources, which are still the
                // host's, and a setTo() copy keeps the destination's resource
                // table, so the AppCompat attribute stayed absent (measured:
                // has=false for 0x7f04052b even after a successful setTo).
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    activity.setTheme(context.getTheme());
                } else {
                    setField(activity, "mTheme", context.getTheme());
                }
                setField(activity, "mThemeResource", theme);
                int appcompatActionBar = context.getResources().getIdentifier(
                        "windowActionBar", "attr", context.build.getPackageName());
                android.content.res.TypedArray ta = activity.getTheme()
                        .obtainStyledAttributes(new int[]{appcompatActionBar});
                boolean has = ta.hasValue(0);
                boolean value = has && ta.getBoolean(0, true);
                ta.recycle();
                Diag.set("theme installed resId=0x" + Integer.toHexString(theme)
                        + " windowActionBar has=" + has + " value=" + value);
            }
        } catch (Throwable t) {
            Diag.set("could not apply the archive theme: " + t);
        }
        Diag.set("guest context installed=" + ok
                + " resources=" + (activity.getResources() == context.getResources()
                        ? "archive" : "host"));
        return ok;
    }

    @Override
    public String getOpPackageName() {
        // AppOps verifies the op package against this uid, so it must be the
        // host's package, exactly like getPackageName().
        return getBaseContext().getPackageName();
    }

    @Override
    public AssetManager getAssets() {
        return resources.getAssets();
    }

    @Override
    public File getFilesDir() {
        return dir("files");
    }

    @Override
    public File getNoBackupFilesDir() {
        return dir("no_backup");
    }

    @Override
    public File getCacheDir() {
        return dir("cache");
    }

    @Override
    public File getCodeCacheDir() {
        return dir("code_cache");
    }

    @Override
    public File getDataDir() {
        return build.getDataDir();
    }

    @Override
    public File getObbDir() {
        return dir("obb");
    }

    @Override
    public File getExternalFilesDir(String type) {
        File base = dir("external");
        File target = type == null ? base : new File(base, type);
        //noinspection ResultOfMethodCallIgnored
        target.mkdirs();
        return target;
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        File one = getExternalFilesDir(type);
        return one == null ? null : new File[]{one};
    }

    @Override
    public File getExternalCacheDir() {
        return dir("external_cache");
    }

    private File dir(String name) {
        File dir = new File(build.getDataDir(), name);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    void logIdentity() {
        Log.i(TAG, "guest context: pkg=" + getPackageName()
                + " files=" + getFilesDir()
                + " data=" + build.getDataDir());
    }

    private static Object field(Object target, String name) {
        try {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) { }
            }
        } catch (Throwable ignored) { }
        return "?";
    }


    /**
     * Drop the cached {@code Resources.Theme} so {@code getTheme()} rebuilds it.
     *
     * <p>{@code Activity.getTheme()} returns {@code mTheme} when it is non-null
     * and only builds a fresh one from {@code getResources().newTheme()}
     * otherwise. The cache was populated before the archive's resources were
     * installed, which is why the theme still resolved AppCompat attributes
     * against the wrapper's resource table.
     */
    /**
     * Report which class in the activity's chain declares the theme fields.
     *
     * <p>Reflection against a field that a given Android release moved (or
     * removed) fails silently unless it is reported, and a silent failure here
     * shows up much later as a wrong theme.
     */
    private static void probeThemeFields(Object activity) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c = activity.getClass(); c != null; c = c.getSuperclass()) {
            for (String name : new String[]{"mTheme", "mThemeResource", "mResources"}) {
                try {
                    c.getDeclaredField(name);
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(c.getSimpleName()).append('.').append(name);
                } catch (NoSuchFieldException ignored) { }
            }
        }
        Diag.set("theme fields: " + sb);
        // Full dump: the cache's field name has moved between releases, so the
        // only reliable way to find it is to list what this build actually has.
        for (Class<?> c = activity.getClass(); c != null && c != android.content.Context.class;
             c = c.getSuperclass()) {
            StringBuilder f = new StringBuilder(c.getSimpleName()).append(": ");
            for (java.lang.reflect.Field x : c.getDeclaredFields()) {
                f.append(x.getName()).append('=').append(x.getType().getSimpleName()).append(' ');
            }
            Diag.set("fields " + f);
        }
    }

    private static void clearThemeCache(Object activity) {
        // Cleared by TYPE, not by name: the field that caches the theme is not
        // called mTheme on this platform (measured: the activity's chain declares
        // only mThemeResource and mResources), and a name that has already moved
        // once will move again. Any Resources.Theme field on the chain is the
        // cache.
        int cleared = 0;
        for (Class<?> c = activity.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != android.content.res.Resources.Theme.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(activity, null);
                    cleared++;
                } catch (Throwable t) {
                    Diag.set("theme cache " + c.getSimpleName() + "." + f.getName() + ": " + t);
                }
            }
        }
        Diag.set("cleared " + cleared + " cached theme field(s)");
    }

    /** Set a field declared on a specific class, reporting rather than throwing. */
    private static void setFieldOn(Object target, Class<?> declaring, String name, Object value) {
        try {
            java.lang.reflect.Field f = declaring.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Throwable t) {
            Diag.set("field " + declaring.getSimpleName() + "." + name + ": " + t);
        }
    }

    /** Set a declared field on the class or any of its superclasses. */
    private static void setField(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep walking
            }
        }
        throw new NoSuchFieldException(name);
    }

}
