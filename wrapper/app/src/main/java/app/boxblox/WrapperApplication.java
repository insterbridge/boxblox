package app.boxblox;

import android.app.Application;
import android.content.res.Resources;
import android.util.Log;

import java.util.List;

/**
 * The wrapper's process entry point.
 *
 * <p>It does one thing the framework cannot do for it: install the verified
 * guest's dex into this process's class path before any activity is created, so
 * the platform can instantiate the guest's own activities.
 *
 * <p><b>The desktop identity is not installed here.</b> The engine builds its
 * {@code PlatformParams} from the <em>activity</em> it is running in
 * ({@code wh/i0.q()} → {@code ExperienceSession.H()}), so the shadow belongs on
 * that activity — see {@link GuestContext}. Overriding the application's
 * answers would be answering a question the engine does not ask.
 *
 * <p>Nothing is loaded from the guest until the user has chosen an archive, and
 * nothing is loaded from an archive Roblox did not sign — the verification in
 * {@link RobloxStore} runs first, in {@link MainActivity}, and only its success
 * path reaches {@link #prepare}.
 */
public final class WrapperApplication extends Application {

    private static final String TAG = "RobloxWrapper";

    private static WrapperApplication instance;

    private RobloxStore.Build build;
    private ClassLoader guestLoader;
    private boolean engineLoaded;
    private boolean overridesPreloaded;
    /**
     * True once a guest activity has been created in this process.
     *
     * <p>The engine cannot serve a second session in the same process: its
     * {@code JNI_OnLoad} is made inert after the first run and ART dedupes a
     * repeat {@code System.load}, so a relaunch starts against natives the
     * engine unloaded when the first session ended. The launch path restarts the
     * process instead of pretending otherwise.
     */
    private boolean engineUsed;
    private ShadowPackageManager shadowPackageManager;
    private Resources archiveResources;

    /** Whether a guest session has already run in this process. */
    boolean engineWasUsed() {
        return engineUsed;
    }

    /**
     * Record that a guest activity was created.
     *
     * <p>Called from {@link GuestInstrumentation} once the guest's own
     * {@code onCreate} has run, which is the first moment a session really
     * exists in this process.
     */
    void markEngineUsed() {
        engineUsed = true;
    }

    static WrapperApplication get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Platform.install(this);
        Diag.install(getFilesDir());
        OverlayDir.ensure(this);
        Log.i(TAG, "wrapper starting; desktop host=" + Platform.isDesktopHost());

        // A build that is already staged is prepared before any activity, so the
        // guest's activity finds its loader and its engine already in place.
        try {
            List<java.io.File> staged = new RobloxStore(this).launchArchives();
            if (!staged.isEmpty()) {
                prepare(staged);
            }
        } catch (Throwable t) {
            Log.w(TAG, "staged build was refused: " + t.getMessage());
        }
    }

    /**
     * Verify, unpack, load, and install a build.
     *
     * Idempotent for the same archives: a second call with the same combined
     * hash keeps the loader and the already-mapped engine.
     */
    synchronized RobloxStore.Build prepare(List<java.io.File> archives) throws RobloxStore.Rejected {
        RobloxStore store = new RobloxStore(this);
        RobloxStore.Build prepared = store.prepare(archives);
        if (build != null && !build.getSha256().equals(prepared.getSha256())) {
            throw new RobloxStore.Rejected("Restart the process before loading a different build.");
        }
        // Guest Application.onCreate can read settings too, so writing only
        // after prepare() returns is too late on a cold start.
        if (!new FlagStore(this).writeFor(prepared)) {
            throw new RobloxStore.Rejected("Could not write flag overrides; see diagnostics.");
        }

        installAssetFolders(prepared);
        if (build != null && build.getSha256().equals(prepared.getSha256()) && guestLoader != null) {
            // Nothing to do: the overrides were already handed to the guest when
            // this build was prepared, and the engine reads them once. Re-sending
            // here only duplicated the diagnostic on every second prepare()
            // (measured: "supplied 3 overrides" twice per launch).
            return build;
        }

        ClassLoader loader = ClassLoaderInstaller.install(this, prepared);
        if (loader == null) {
            throw new RobloxStore.Rejected(
                    "could not install the archive's classes into this process; "
                            + "the wrapper cannot start the guest (see logcat)");
        }

        // The engine is NOT loaded here, deliberately.
        //
        // AGDK's GameActivity.onCreate calls System.loadLibrary("roblox"), and
        // System.loadLibrary resolves through the loader of the class that
        // calls it — GameActivity, which the archive's loader defines. That is
        // also the loader the engine's JNI_OnLoad needs for its own FindClass
        // lookups, so letting the guest load itself is the only arrangement in
        // which both work. Loading it from libwrapper.so instead makes
        // JNI_OnLoad resolve against the wrapper's dex and abort with
        // ClassNotFoundException: ...NativeLocaleJavaInterface.
        //
        // What the wrapper owes that path is the library search directory,
        // which ClassLoaderInstaller sets to this build's unpacked natives.

        build = prepared;
        guestLoader = loader;
        archiveResources = new ArchiveResources(prepared, getBaseContext().getResources()).open();

        // AndroidX has to be brought up by hand.
        //
        // The archive's manifest declares androidx.startup.InitializationProvider,
        // and that provider is what normally runs WorkManager's, lifecycle's and
        // okhttp's initialisers before Application.onCreate. The wrapper's
        // manifest replaces the archive's, and declaring the provider here does
        // not work either: providers are instantiated before this application's
        // loader has been extended with the archive, so the platform fails with
        // ClassNotFoundException (measured). Calling the initialisers directly,
        // after the loader is installed, is the arrangement that works.
        runStartupInitializers(loader);
        preGrantPermissions();
        GuestClassLoader.probe(loader);
        preloadGuestLibraries(prepared);
        // The engine must be mapped BEFORE the guest Application runs: its
        // onCreate reaches com.roblox.client.JNIAAssetManagerSetup.initNative
        // immediately (measured: UnsatisfiedLinkError initNative when the load
        // happened later, in the activity path).
        loadEngine(prepared, loader);
        runGuestApplication(loader);
        // The activity factory has to be in place before the first activity is
        // created, which is why this is here and not in an activity.
        GuestInstrumentation.install(this);
        preloadFlagOverrides();
        Log.i(TAG, "prepared " + prepared);
        return build;
    }

    private void preloadFlagOverrides() {
        try {
            java.util.Map<String, Object> overrides = new FlagStore(this).engineOverrides();
            if (overrides.isEmpty() && !overridesPreloaded) return;
            // MainGameActivity.K2 calls this before GameActivity.onCreate, but
            // the production APK's nh.b.a() gate is constant false. Writing the
            // local file does not exercise that explicit-override handoff.
            // An empty payload is still sent after removing earlier overrides
            // while the launcher is open, before the first guest activity.
            GuestFlagOverrides.preload(guestLoader, overrides);
            overridesPreloaded = !overrides.isEmpty();
        } catch (Throwable t) {
            // Fail open, deliberately.
            //
            // This call finds MainGameActivity.nativePreloadFlagOverrides by
            // name, and Roblox renames these obfuscated members routinely. A
            // hard failure here would refuse to launch after a routine Roblox
            // update and lock the user out of the app until a wrapper update
            // shipped. Degrading is honest because the local settings file is
            // still written and still read: overrides fall back to the weaker
            // path, which is the behaviour before this preload existed.
            Throwable cause = t.getCause() == null ? t : t.getCause();
            Diag.set("flags: native preload unavailable, continuing: " + cause);
        }
    }


    /**
     * Run Roblox's own {@code Application.onCreate}.
     *
     * <p><b>Why this is required.</b> The wrapper's {@code Application} replaced
     * Roblox's, and Roblox's does the process-wide setup the engine assumes:
     * {@code sg/a1.i0(Context, …)} publishes the {@code SharedPreferences} that
     * {@code vi.h}'s static initialiser reads, plus the asset manager, local
     * storage, base-URL and web-login protocols. Without it the guest's first
     * static initialiser throws:
     *
     * <pre>
     * NullPointerException: SharedPreferences.getString(…) on a null object
     *   at vi.h.k → vi.h.&lt;init&gt; → vi.h$e.&lt;clinit&gt;
     * </pre>
     *
     * <p>Their class is instantiated through the archive's loader and given this
     * process's base context, so it initialises the same statics the engine
     * reads. Nothing in it is modified.
     */
    /**
     * Map the engine, once, before anything that needs its natives.
     *
     * <p>Two measured constraints fix this shape. The guest Application's
     * {@code onCreate} calls {@code JNIAAssetManagerSetup.initNative} straight
     * away, so the library has to be mapped before that runs. And by-name
     * lookup cannot find it here: {@code System.loadLibrary("roblox")} goes
     * through the linker namespace, which permits only this app's own
     * nativeLibraryDir, and that directory is read-only so the engine cannot be
     * mirrored into it (measured: {@code dlopen failed: library "libroblox.so"
     * not found}). An absolute path is therefore the only load that works.
     *
     * <p>ART keys its loaded-library table by the requested name, so the
     * archive's own later {@code System.loadLibrary("roblox")}
     * ({@code com.roblox.client.startup.a.t}) is a second entry that would run
     * {@code JNI_OnLoad} again and abort inside the engine. The exported
     * initialiser is patched to a no-op afterwards so that second run is
     * harmless.
     */
    /**
     * Take the runtime permissions the guest asks for before it asks.
     *
     * <p>The guest requests {@code POST_NOTIFICATIONS} from its own permission
     * helper ({@code k2.b.u}), which calls {@code Activity.requestPermissions}.
     * That call builds an intent naming the package the activity was declared
     * under, and the system server refuses it —
     * {@code Permission Denial: package=com.roblox.client does not belong to
     * uid=…} — because the activity is declared under the archive's name while
     * the process runs as this app. The exception escapes into a JNI scope and
     * the engine aborts.
     *
     * <p>When the permission is already held, {@code requestPermissions} returns
     * without building that intent at all. So the fix is to hold it first: this
     * app declares POST_NOTIFICATIONS in its own manifest, and granting it here
     * makes the guest's request a no-op (measured: the abort disappears
     * entirely).
     *
     * <p>Nothing else is requested, because nothing else the guest asks for is
     * needed to play. Notifications are optional; the client runs fine without
     * them ever being shown.
     */
    private void preGrantPermissions() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            String permission = "android.permission.POST_NOTIFICATIONS";
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Diag.set("POST_NOTIFICATIONS not granted; the guest's request will fail");
            } else {
                Diag.set("POST_NOTIFICATIONS already granted");
            }
        } catch (Throwable t) {
            Diag.set("permission probe failed: " + t);
        }
    }

    /**
     * Run the {@code androidx.startup} initialisers the archive's manifest lists.
     *
     * <p>Only the ones that matter to this process are run, and each is allowed
     * to fail on its own: a missing initialiser class is not a reason to stop
     * the guest from starting.
     */
    private void runStartupInitializers(ClassLoader loader) {
        // WorkManager is initialized directly, not through androidx.startup.
        //
        // The archive's manifest lists ProcessLifecycleInitializer under
        // InitializationProvider, and that initializer REFUSES to be created
        // lazily — it throws "ProcessLifecycleInitializer cannot be initialized
        // lazily" unless it was discovered through the provider's meta-data
        // (measured). The provider cannot be declared here either, because the
        // platform instantiates providers before this application's class loader
        // has been extended with the archive (measured:
        // ClassNotFoundException androidx.startup.InitializationProvider).
        //
        // WorkManager's own initializer is a plain static call, so it does not
        // need any of that. It is what the guest's onResume actually requires:
        // "WorkManager is not initialized properly" (measured).
        // WorkManager's own initializer, called directly. It is an
        // androidx.startup Initializer like the others, and unlike
        // ProcessLifecycleInitializer it does not refuse a direct create().
        try {
            Class<?> type = Class.forName(
                    "androidx.work.WorkManagerInitializer", true, loader);
            java.lang.reflect.Method create = type.getMethod(
                    "create", android.content.Context.class);
            Object result = create.invoke(type.getDeclaredConstructor().newInstance(), this);
            Diag.set("WorkManager initializer -> " + result);
        } catch (Throwable t) {
            Diag.set("WorkManager init failed: "
                    + (t.getCause() == null ? t : t.getCause()));
        }
        // The okhttp and profile-installer initializers are independent and safe
        // to call directly.
        for (String name : new String[]{
                "androidx.profileinstaller.ProfileInstallerInitializer",
                "okhttp3.internal.platform.PlatformInitializer",
        }) {
            try {
                Class<?> type = Class.forName(name, true, loader);
                java.lang.reflect.Method create = type.getMethod(
                        "create", android.content.Context.class);
                Object result = create.invoke(type.getDeclaredConstructor().newInstance(), this);
                Diag.set("initialiser " + name + " -> " + result);
            } catch (Throwable t) {
                Diag.set("initialiser " + name + " failed: "
                        + (t.getCause() == null ? t : t.getCause()));
            }
        }
    }

    private void loadEngine(RobloxStore.Build build, ClassLoader loader) {
        if (engineLoaded) {
            return;
        }
        java.io.File engine = new java.io.File(build.getNativeDir(), RobloxStore.REQUIRED_LIB);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            System.load(engine.getAbsolutePath());
            engineLoaded = true;
            Diag.set("engine loaded by absolute path");
            Diag.set("noteEngineLoaded=" + EngineBridge.noteEngineLoaded());
            Diag.set("disableJniOnLoad=" + EngineBridge.disableJniOnLoad());
            boolean mouseLock = new FlagStore(this).getBool("wrapper.mouse_lock", false);
            EngineBridge.setMouseLockEnabled(mouseLock);
            Diag.set("mouse capture enabled=" + mouseLock);
        } catch (Throwable t) {
            Diag.set("engine load failed: " + t);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Pre-load the archive's shared libraries by absolute path.
     *
     * <p>Roblox's Java resolves several of them by bare name
     * ({@code System.loadLibrary("zstd-jni-1.5.7-6")} and friends). By-name
     * lookup goes through the process's linker namespace, which is fixed when
     * the process starts and cannot be widened from Java — measured: the
     * archive's directory as the *first* entry of
     * {@code DexPathList.nativeLibraryDirectories} still produced
     * {@code dlopen failed: library "libzstd-jni-1.5.7-6.so" not found}.
     *
     * <p>{@code System.load} takes an absolute path, which the namespace does
     * not restrict. Loading each library once by path makes the later by-name
     * call resolve against the already-mapped library.
     */
    private void preloadGuestLibraries(RobloxStore.Build build) {
        java.io.File[] libraries = build.getNativeDir().listFiles(
                (dir, name) -> name.endsWith(".so"));
        if (libraries == null) {
            return;
        }
        // The engine is never loaded from here — it is only sorted last.
        //
        // The ordering is forced by the guest: MainGameActivity.onCreate calls
        // NativeSettingsInterface.nativeSetBaseUrl at dex line 65, which is
        // before it delegates to GameActivity.onCreate at line 88 — and line 88
        // is AGDK's System.loadLibrary("roblox"). So the *other* natives must
        // already be registered when onCreate starts.
        java.util.Arrays.sort(libraries, (a, b) -> {
            boolean aEngine = a.getName().equals(RobloxStore.REQUIRED_LIB);
            boolean bEngine = b.getName().equals(RobloxStore.REQUIRED_LIB);
            return Boolean.compare(aEngine, bEngine);
        });
        int loaded = 0;
        for (java.io.File library : libraries) {
            // The engine is skipped: the guest loads "roblox" by name itself
            // (com.roblox.client.startup.a.t -> System.loadLibrary("roblox")),
            // and a second entry keyed by this absolute path would run
            // JNI_OnLoad twice, which aborts inside libroblox.so.
            if (library.getName().equals(RobloxStore.REQUIRED_LIB)) {
                continue;
            }
            try {
                System.load(library.getAbsolutePath());
                loaded++;
            } catch (Throwable t) {
                Diag.set("preload " + library.getName() + ": " + t.getMessage());
            }
        }
        Diag.set("preloaded " + loaded + " guest libraries (engine last)");
        // The engine is loaded by the guest (com.roblox.client.startup.a.t ->
        // System.loadLibrary("roblox")), never here. Nothing else in this class
        // may load it.
    }

    private void runGuestApplication(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(RobloxStore.APPLICATION_CLASS, true, loader);
            Object application = type.getDeclaredConstructor().newInstance();
            // The guest application is attached to a guest context, so every
            // question it asks about its package, resources or data directory is
            // answered from the archive rather than from the wrapper.
            android.content.res.Resources resources =
                    new ArchiveResources(build, getBaseContext().getResources()).open();
            GuestContext context = new GuestContext(
                    getBaseContext(), build, resources, shadowPackageInfo(build));
            java.lang.reflect.Method attach = android.content.ContextWrapper.class
                    .getDeclaredMethod("attachBaseContext", android.content.Context.class);
            attach.setAccessible(true);
            attach.invoke(application, context);
            type.getMethod("onCreate").invoke(application);
            Diag.set("ran " + RobloxStore.APPLICATION_CLASS + ".onCreate");
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            Diag.set("guest application onCreate failed: " + cause);
        }
    }



    private android.content.pm.PackageInfo shadowPackageInfo(RobloxStore.Build prepared) {
        android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
        info.packageName = prepared.getPackageName();
        info.versionName = prepared.getVersionName();
        info.versionCode = (int) prepared.getVersionCode();
        info.applicationInfo = getBaseContext().getApplicationInfo();
        return info;
    }


    /**
     * The archive's resources, once a build is prepared.
     *
     * <p>This matters for one specific reason: {@code ActivityThread} attaches a
     * new activity with the application's base context, and
     * {@code Activity.getTheme()} resolves through the resources it was attached
     * with. AppCompat checks the theme during {@code Activity.attach}, before
     * {@code newActivity} has returned, so no later hook can influence it — the
     * resources have to be right here.
     */
    @Override
    public Resources getResources() {
        return archiveResources != null ? archiveResources : super.getResources();
    }

    /**
     * The archive's package answers, process-wide.
     *
     * <p>{@code Application.getPackageManager()} is what any library that keeps
     * the process-wide Application uses — AppsFlyer does exactly that from a
     * posted {@code Runnable}, reached via
     * {@code com.roblox.client.startup.NativeHelper.Q}, and the unshadowed host
     * manager throws {@code UnsupportedOperationException: getPackageInfo not
     * implemented in subclass} because the platform's base implementation is
     * abstract-on-purpose (measured).
     */
    /**
     * The real package name, so components built from this context are owned by
     * this UID.
     *
     * <p>{@code RobloxApplication.f()} caches a context and the guest builds
     * {@code new ComponentName(thatContext, RecentlyPlayedWidgetProvider)} from
     * it, then hands it to {@code AppWidgetManager}. The system server validates
     * the component's package against the caller's UID and refuses anything it
     * does not own: {@code SecurityException: Package com.roblox.client does not
     * belong to <uid>} (measured). The application context is the one place the
     * guest's real identity must show through.
     */
    @Override
    public String getPackageName() {
        return super.getPackageName();
    }

    @Override
    public android.content.pm.PackageManager getPackageManager() {
        if (build != null) {
            String mode = SessionMode.read(new FlagStore(this));
            if (shadowPackageManager == null || !shadowPackageManager.matches(build, mode)) {
                shadowPackageManager = new ShadowPackageManager(
                        getPackageName(), super.getPackageManager(), build, shadowPackageInfo(build), mode);
            }
        }
        return shadowPackageManager != null ? shadowPackageManager : super.getPackageManager();
    }

    /** Install the asset tree at the package-root path the engine actually opens. */
    private void installAssetFolders(RobloxStore.Build build) throws RobloxStore.Rejected {
        try {
            BuildFiles.mirror(build.getAssetDir(), new java.io.File(getDataDir(), "app_assets"));
        } catch (java.io.IOException e) {
            throw new RobloxStore.Rejected("Could not install assets: " + e.getMessage());
        }
        if (new FlagStore(this).getBool("wrapper.asset_overlay", false)) {
            int overlaid = OverlayDir.apply(this, new java.io.File(getDataDir(), "app_assets"));
            if (overlaid > 0) {
                Diag.set("overlay applied: " + overlaid + " files");
            }
        } else {
            Diag.set("overlay disabled");
        }
    }

    RobloxStore.Build getBuild() {
        return build;
    }

    ClassLoader getGuestLoader() {
        return guestLoader;
    }
}
