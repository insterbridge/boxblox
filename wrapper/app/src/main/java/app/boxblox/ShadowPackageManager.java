package app.boxblox;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.util.Log;

/**
 * The guest's {@code PackageManager}: three answers from the archive, every
 * other question the platform's.
 *
 * <b>Why almost everything here is a one-line delegation.</b>
 * {@code PackageManager} declares ninety-four abstract methods, nearly all
 * installer, permission and instant-app plumbing that the wrapper has no
 * business reimplementing. A stub that returned a plausible value would be a
 * lie the guest acts on; a delegation is the platform's real answer, which is
 * correct for every question the wrapper has no opinion about.
 *
 * <b>The answers that are not delegated, each measured from the shipped dex:</b>
 * <ul>
 *   <li>{@link #hasSystemFeature(String)} — {@code PlatformParams.isMouseDevice}
 *       is literally this call with {@code android.hardware.type.pc}, and
 *       {@code isTouchDevice}/{@code isKeyboardDevice} are the same call with
 *       {@code touchscreen}/{@code keyboard}. This is the whole desktop
 *       identification surface.</li>
 *   <li>{@link #getActivityInfo} — AGDK's {@code GameActivity.onCreate} reads
 *       {@code android.app.lib_name} from it to pick the {@code .so} to load.</li>
 *   <li>{@link #getApplicationInfo} — supplies {@code nativeLibraryDir}, where
 *       that {@code .so} lives, and {@code dataDir}.</li>
 * </ul>
 *
 * Every one of those call sites starts from {@code Context.getPackageManager()},
 * so returning this object from {@link GuestContext#getPackageManager()} is
 * what routes them here.
 */
final class ShadowPackageManager extends PackageManager {

    private static final String TAG = "RobloxWrapper";

    private final String hostPackageName;
    private final PackageManager host;
    private final RobloxStore.Build build;
    private final PackageInfo pkg;
    private final String sessionMode;

    ShadowPackageManager(String hostPackageName, PackageManager host, RobloxStore.Build build,
            PackageInfo pkg, String sessionMode) {
        this.hostPackageName = hostPackageName;
        this.host = host;
        this.build = build;
        this.pkg = pkg;
        this.sessionMode = sessionMode;
    }

    boolean matches(RobloxStore.Build candidate, String mode) {
        return build.getSha256().equals(candidate.getSha256()) && sessionMode.equals(mode);
    }

    /**
     * The real platform manager behind the shadow.
     *
     * <p>Importing the installed Roblox has to ask the platform: the shadow
     * answers for {@code com.roblox.client} out of the archive, which has no
     * split paths.
     */
    PackageManager hostManager() {
        return host;
    }

    // ------------------------------------------------------- the shadowed few

    @Override
    public boolean hasSystemFeature(String name) {
        // One source of truth for what the session claims to be.
        final boolean pc = SessionMode.desktopIdentity(sessionMode);
        if ("android.hardware.type.pc".equals(name)) {
            // PlatformParams.isMouseDevice. A mouse is what this wrapper is for.
            return pc;
        }
        if ("android.hardware.touchscreen".equals(name)) {
            // PlatformParams.isTouchDevice. Reported absent only in the full
            // desktop session; the touch session keeps it present, which is
            // Sober's fake-off: PC layout, screen still usable.
            return SessionMode.reportTouchscreen(sessionMode);
        }
        if ("android.hardware.keyboard".equals(name)) {
            // PlatformParams.isKeyboardDevice.
            return pc;
        }
        return host.hasSystemFeature(name);
    }

    @Override
    public boolean hasSystemFeature(String name, int version) {
        return hasSystemFeature(name);
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags) throws NameNotFoundException {
        if (component != null && pkg.packageName.equals(component.getPackageName())) {
            ActivityInfo info = new ActivityInfo();
            info.packageName = pkg.packageName;
            info.name = component.getClassName();
            info.applicationInfo = getApplicationInfo(pkg.packageName, 0);
            info.metaData = activityMetaData();
            info.exported = true;
            info.enabled = true;
            Log.i(TAG, "getActivityInfo(" + component.getClassName() + ") -> lib_name=roblox");
            return info;
        }
        return host.getActivityInfo(component, flags);
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
        if (pkg.packageName.equals(packageName)) {
            ApplicationInfo info = pkg.applicationInfo;
            if (info == null) {
                info = new ApplicationInfo();
                info.packageName = pkg.packageName;
            } else {
                // pkg.applicationInfo is the LoadedApk-owned instance for app.boxblox, not a
                // copy (GuestInstrumentation.packageInfo()/WrapperApplication.shadowPackageInfo()
                // both hand out base.getApplicationInfo()), so the path fields below must land on
                // a private copy instead of rewriting the wrapper's own ApplicationInfo.
                info = new ApplicationInfo(info);
            }
            // Where the engine's own System.loadLibrary("roblox") resolves.
            info.nativeLibraryDir = build.getNativeDir().getAbsolutePath();
            info.dataDir = build.getDataDir().getAbsolutePath();
            info.sourceDir = build.getApk().getAbsolutePath();
            info.publicSourceDir = info.sourceDir;
            return info;
        }
        return host.getApplicationInfo(packageName, flags);
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        if (pkg.packageName.equals(packageName)) {
            return pkg;
        }
        return host.getPackageInfo(packageName, flags);
    }

    /**
     * Permission rationale, forwarded to the host.
     *
     * <p>This one is easy to miss: it is not in the SDK stub this project
     * compiles against, so it never appeared in the generated delegate list, but
     * at runtime {@code Activity.shouldShowRequestPermissionRationale} forwards
     * straight to the activity's PackageManager. With the method missing, the
     * call threw {@code AbstractMethodError} inside a JNI scope, and the engine
     * treats a pending JNI exception as fatal — it logged
     * {@code RBXCRASH: JNIException} and then segfaulted while tearing down
     * (measured, right after the login screen).
     */
    public boolean shouldShowRequestPermissionRationale(String permission) {
        // Reflective because the method is newer than the compile SDK: it exists
        // on the running platform (API 36) and on Activity, which forwards here,
        // but not in the android.jar this project builds against. Declaring it as
        // an @Override would not compile, and omitting it entirely throws
        // AbstractMethodError at runtime.
        try {
            java.lang.reflect.Method m = PackageManager.class
                    .getMethod("shouldShowRequestPermissionRationale", String.class);
            Object answer = m.invoke(host, permission);
            return answer instanceof Boolean && (Boolean) answer;
        } catch (Throwable t) {
            // No rationale to show is a safe answer: it means "just ask".
            return false;
        }
    }

    // ---------------------------------------------------------- permission plumbing

    /**
     * Hidden API; present at runtime, absent from the SDK stubs.
     *
     * <p>Use the platform's chosen permission controller. An unresolvable
     * package produces an ActivityNotFoundException inside the guest's JNI
     * scope, which the engine treats as fatal. GuestContext adopts the host's
     * activity identity, and checkPermission maps archive queries to the host.
     */
    public String getPermissionControllerPackageName() {
        try {
            java.lang.reflect.Method method = PackageManager.class
                    .getMethod("getPermissionControllerPackageName");
            Object answer = method.invoke(host);
            if (answer instanceof String && !((String) answer).isEmpty()) {
                return (String) answer;
            }
        } catch (ReflectiveOperationException | SecurityException e) {
            Diag.set("permission controller API unavailable: " + e);
        }
        String resolved = resolvePermissionController();
        if (resolved == null) {
            throw new IllegalStateException("No system permission controller resolves REQUEST_PERMISSIONS");
        }
        return resolved;
    }

    /** System-only fallback for releases that do not expose the hidden API. */
    private String resolvePermissionController() {
        try {
            Intent probe = new Intent("android.content.pm.action.REQUEST_PERMISSIONS");
            java.util.List<ResolveInfo> found = host.queryIntentActivities(probe, MATCH_SYSTEM_ONLY);
            if (found != null) {
                for (ResolveInfo info : found) {
                    if (info.activityInfo != null && info.activityInfo.packageName != null) {
                        return info.activityInfo.packageName;
                    }
                }
            }
        } catch (Throwable t) {
            Diag.set("could not resolve the permission controller: " + t);
        }
        return null;
    }

    /**
     * Report permission state for the archive's package.
     *
     * <p>The guest asks about {@code com.roblox.client}, which this process is
     * not; the host answers "denied" for every permission it does not own, and
     * the engine then routes into the request path. Answering with this app's
     * own state is what keeps a declared-and-granted permission from looking
     * absent, which is what made the engine request POST_NOTIFICATIONS on every
     * launch and crash on the denial.
     */
    @Override
    public int checkPermission(java.lang.String permission, java.lang.String packageName) {
        if (pkg.packageName.equals(packageName)) {
            // The archive's package name stands in for the wrapper's own; the
            // host manager knows nothing about it, so ask about the app that
            // actually holds the permission.
            return host.checkPermission(permission, hostPackageName);
        }
        return host.checkPermission(permission, packageName);
    }

    private android.os.Bundle activityMetaData() {
        android.os.Bundle meta = new android.os.Bundle();
        // AGDK builds "lib" + lib_name + ".so" from this, so the guest's own
        // manifest value is what is returned.
        meta.putString("android.app.lib_name", "roblox");
        return meta;
    }

    // ------------------------------------------------- everything else

    @Override
    public PackageInfo getPackageInfo(android.content.pm.VersionedPackage versionedPackage, int flags)
            throws NameNotFoundException {
        if (versionedPackage != null && pkg.packageName.equals(versionedPackage.getPackageName())) {
            return pkg;
        }
        return host.getPackageInfo(versionedPackage, flags);
    }

    /**
     * The {@code PackageInfoFlags} overloads.
     *
     * <p>These are the ones that matter, and they are easy to miss: the
     * {@code (String, int)} form is abstract, so an unshadowed instance cannot
     * even be constructed — but {@code PackageManager}'s concrete
     * {@code PackageInfoFlags} methods have a body that throws
     * {@code UnsupportedOperationException: getPackageInfo not implemented in
     * subclass} (measured, from AppsFlyer via
     * {@code com.roblox.client.startup.NativeHelper.Q}). Overriding only the
     * abstract form therefore does not help a caller that uses the flags form.
     */
    @android.annotation.TargetApi(33)
    @Override
    public PackageInfo getPackageInfo(String packageName,
                                      android.content.pm.PackageManager.PackageInfoFlags flags)
            throws NameNotFoundException {
        if (pkg.packageName.equals(packageName)) {
            return pkg;
        }
        return host.getPackageInfo(packageName, flags);
    }

    @android.annotation.TargetApi(33)
    @Override
    public PackageInfo getPackageInfo(android.content.pm.VersionedPackage versionedPackage,
                                      android.content.pm.PackageManager.PackageInfoFlags flags)
            throws NameNotFoundException {
        if (versionedPackage != null && pkg.packageName.equals(versionedPackage.getPackageName())) {
            return pkg;
        }
        return host.getPackageInfo(versionedPackage, flags);
    }


    @Override
    public String[] getSystemSharedLibraryNames() {
        return host.getSystemSharedLibraryNames();
    }

    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        return host.getSystemAvailableFeatures();
    }


    @Override
    public void addPackageToPreferred(java.lang.String a0) {
        host.addPackageToPreferred(a0);
    }

    @Override
    public boolean addPermission(android.content.pm.PermissionInfo a0) {
        return host.addPermission(a0);
    }

    @Override
    public boolean addPermissionAsync(android.content.pm.PermissionInfo a0) {
        return host.addPermissionAsync(a0);
    }

    @Override
    public void addPreferredActivity(android.content.IntentFilter a0, int a1, android.content.ComponentName[] a2, android.content.ComponentName a3) {
        host.addPreferredActivity(a0, a1, a2, a3);
    }

    @Override
    public boolean canRequestPackageInstalls() {
        return host.canRequestPackageInstalls();
    }

    @Override
    public java.lang.String[] canonicalToCurrentPackageNames(java.lang.String[] a0) {
        return host.canonicalToCurrentPackageNames(a0);
    }

    @Override
    public int checkSignatures(int a0, int a1) {
        return host.checkSignatures(a0, a1);
    }

    @Override
    public int checkSignatures(java.lang.String a0, java.lang.String a1) {
        return host.checkSignatures(a0, a1);
    }

    @Override
    public void clearInstantAppCookie() {
        host.clearInstantAppCookie();
    }

    @Override
    public void clearPackagePreferredActivities(java.lang.String a0) {
        host.clearPackagePreferredActivities(a0);
    }

    @Override
    public java.lang.String[] currentToCanonicalPackageNames(java.lang.String[] a0) {
        return host.currentToCanonicalPackageNames(a0);
    }

    @Override
    public void extendVerificationTimeout(int a0, int a1, long a2) {
        host.extendVerificationTimeout(a0, a1, a2);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName a0) throws NameNotFoundException {
        return host.getActivityBanner(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent a0) throws NameNotFoundException {
        return host.getActivityBanner(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName a0) throws NameNotFoundException {
        return host.getActivityIcon(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent a0) throws NameNotFoundException {
        return host.getActivityIcon(a0);
    }

    // android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName a0, int a1) — answered from the archive; see above.

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName a0) throws NameNotFoundException {
        return host.getActivityLogo(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent a0) throws NameNotFoundException {
        return host.getActivityLogo(a0);
    }

    @Override
    public java.util.List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int a0) {
        return host.getAllPermissionGroups(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo a0) {
        return host.getApplicationBanner(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(java.lang.String a0) throws NameNotFoundException {
        return host.getApplicationBanner(a0);
    }

    @Override
    public int getApplicationEnabledSetting(java.lang.String a0) {
        return host.getApplicationEnabledSetting(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo a0) {
        return host.getApplicationIcon(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(java.lang.String a0) throws NameNotFoundException {
        return host.getApplicationIcon(a0);
    }

    // android.content.pm.ApplicationInfo getApplicationInfo(java.lang.String a0, int a1) — answered from the archive; see above.

    @Override
    public java.lang.CharSequence getApplicationLabel(android.content.pm.ApplicationInfo a0) {
        return host.getApplicationLabel(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo a0) {
        return host.getApplicationLogo(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(java.lang.String a0) throws NameNotFoundException {
        return host.getApplicationLogo(a0);
    }

    @Override
    public android.content.pm.ChangedPackages getChangedPackages(int a0) {
        return host.getChangedPackages(a0);
    }

    @Override
    public int getComponentEnabledSetting(android.content.ComponentName a0) {
        return host.getComponentEnabledSetting(a0);
    }

    @Override
    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        return host.getDefaultActivityIcon();
    }

    @Override
    public android.graphics.drawable.Drawable getDrawable(java.lang.String a0, int a1, android.content.pm.ApplicationInfo a2) {
        return host.getDrawable(a0, a1, a2);
    }

    @Override
    public java.util.List<android.content.pm.ApplicationInfo> getInstalledApplications(int a0) {
        return host.getInstalledApplications(a0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getInstalledPackages(int a0) {
        return host.getInstalledPackages(a0);
    }

    @Override
    public java.lang.String getInstallerPackageName(java.lang.String a0) {
        return host.getInstallerPackageName(a0);
    }

    @Override
    public byte[] getInstantAppCookie() {
        return host.getInstantAppCookie();
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        return host.getInstantAppCookieMaxBytes();
    }

    @Override
    public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName a0, int a1) throws NameNotFoundException {
        return host.getInstrumentationInfo(a0, a1);
    }

    @Override
    public android.content.Intent getLaunchIntentForPackage(java.lang.String a0) {
        return host.getLaunchIntentForPackage(a0);
    }

    @Override
    public android.content.Intent getLeanbackLaunchIntentForPackage(java.lang.String a0) {
        return host.getLeanbackLaunchIntentForPackage(a0);
    }

    @Override
    public java.lang.String getNameForUid(int a0) {
        return host.getNameForUid(a0);
    }

    @Override
    public int[] getPackageGids(java.lang.String a0) throws NameNotFoundException {
        return host.getPackageGids(a0);
    }

    @Override
    public int[] getPackageGids(java.lang.String a0, int a1) throws NameNotFoundException {
        return host.getPackageGids(a0, a1);
    }

    // android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage a0, int a1) — answered from the archive; see above.

    // android.content.pm.PackageInfo getPackageInfo(java.lang.String a0, int a1) — answered from the archive; see above.

    @Override
    public android.content.pm.PackageInstaller getPackageInstaller() {
        return host.getPackageInstaller();
    }

    @Override
    public int getPackageUid(java.lang.String a0, int a1) throws NameNotFoundException {
        return host.getPackageUid(a0, a1);
    }

    @Override
    public java.lang.String[] getPackagesForUid(int a0) {
        return host.getPackagesForUid(a0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] a0, int a1) {
        return host.getPackagesHoldingPermissions(a0, a1);
    }

    @Override
    public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(java.lang.String a0, int a1) throws NameNotFoundException {
        return host.getPermissionGroupInfo(a0, a1);
    }

    @Override
    public android.content.pm.PermissionInfo getPermissionInfo(java.lang.String a0, int a1) throws NameNotFoundException {
        return host.getPermissionInfo(a0, a1);
    }

    @Override
    public int getPreferredActivities(java.util.List<android.content.IntentFilter> a0, java.util.List<android.content.ComponentName> a1, java.lang.String a2) {
        return host.getPreferredActivities(a0, a1, a2);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int a0) {
        return host.getPreferredPackages(a0);
    }

    @Override
    public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName a0, int a1) throws NameNotFoundException {
        return host.getProviderInfo(a0, a1);
    }

    @Override
    public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName a0, int a1) throws NameNotFoundException {
        return host.getReceiverInfo(a0, a1);
    }

    @Override
    public android.content.res.Resources getResourcesForActivity(android.content.ComponentName a0) throws NameNotFoundException {
        return host.getResourcesForActivity(a0);
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo a0) throws NameNotFoundException {
        return host.getResourcesForApplication(a0);
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(java.lang.String a0) throws NameNotFoundException {
        return host.getResourcesForApplication(a0);
    }

    @Override
    public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName a0, int a1) throws NameNotFoundException {
        return host.getServiceInfo(a0, a1);
    }

    @Override
    public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int a0) {
        return host.getSharedLibraries(a0);
    }

    // android.content.pm.FeatureInfo[] getSystemAvailableFeatures() — answered from the archive; see above.

    // java.lang.String[] getSystemSharedLibraryNames() — answered from the archive; see above.

    @Override
    public java.lang.CharSequence getText(java.lang.String a0, int a1, android.content.pm.ApplicationInfo a2) {
        return host.getText(a0, a1, a2);
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable a0, android.os.UserHandle a1, android.graphics.Rect a2, int a3) {
        return host.getUserBadgedDrawableForDensity(a0, a1, a2, a3);
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable a0, android.os.UserHandle a1) {
        return host.getUserBadgedIcon(a0, a1);
    }

    @Override
    public java.lang.CharSequence getUserBadgedLabel(java.lang.CharSequence a0, android.os.UserHandle a1) {
        return host.getUserBadgedLabel(a0, a1);
    }

    @Override
    public android.content.res.XmlResourceParser getXml(java.lang.String a0, int a1, android.content.pm.ApplicationInfo a2) {
        return host.getXml(a0, a1, a2);
    }

    // boolean hasSystemFeature(java.lang.String a0) — answered from the archive; see above.

    // boolean hasSystemFeature(java.lang.String a0, int a1) — answered from the archive; see above.

    @Override
    public boolean isInstantApp() {
        return host.isInstantApp();
    }

    @Override
    public boolean isInstantApp(java.lang.String a0) {
        return host.isInstantApp(a0);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(java.lang.String a0, java.lang.String a1) {
        return host.isPermissionRevokedByPolicy(a0, a1);
    }

    @Override
    public boolean isSafeMode() {
        return host.isSafeMode();
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent a0, int a1) {
        return host.queryBroadcastReceivers(a0, a1);
    }

    @Override
    public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String a0, int a1, int a2) {
        return host.queryContentProviders(a0, a1, a2);
    }

    @Override
    public java.util.List<android.content.pm.InstrumentationInfo> queryInstrumentation(java.lang.String a0, int a1) {
        return host.queryInstrumentation(a0, a1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent a0, int a1) {
        return host.queryIntentActivities(a0, a1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName a0, android.content.Intent[] a1, android.content.Intent a2, int a3) {
        return host.queryIntentActivityOptions(a0, a1, a2, a3);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent a0, int a1) {
        return host.queryIntentContentProviders(a0, a1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent a0, int a1) {
        return host.queryIntentServices(a0, a1);
    }

    @Override
    public java.util.List<android.content.pm.PermissionInfo> queryPermissionsByGroup(java.lang.String a0, int a1) throws NameNotFoundException {
        return host.queryPermissionsByGroup(a0, a1);
    }

    @Override
    public void removePackageFromPreferred(java.lang.String a0) {
        host.removePackageFromPreferred(a0);
    }

    @Override
    public void removePermission(java.lang.String a0) {
        host.removePermission(a0);
    }

    @Override
    public android.content.pm.ResolveInfo resolveActivity(android.content.Intent a0, int a1) {
        return host.resolveActivity(a0, a1);
    }

    @Override
    public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String a0, int a1) {
        return host.resolveContentProvider(a0, a1);
    }

    @Override
    public android.content.pm.ResolveInfo resolveService(android.content.Intent a0, int a1) {
        return host.resolveService(a0, a1);
    }

    @Override
    public void setApplicationCategoryHint(java.lang.String a0, int a1) {
        host.setApplicationCategoryHint(a0, a1);
    }

    @Override
    public void setApplicationEnabledSetting(java.lang.String a0, int a1, int a2) {
        host.setApplicationEnabledSetting(a0, a1, a2);
    }

    @Override
    public void setComponentEnabledSetting(android.content.ComponentName a0, int a1, int a2) {
        host.setComponentEnabledSetting(a0, a1, a2);
    }

    @Override
    public void setInstallerPackageName(java.lang.String a0, java.lang.String a1) {
        host.setInstallerPackageName(a0, a1);
    }

    @Override
    public void updateInstantAppCookie(byte[] a0) {
        host.updateInstantAppCookie(a0);
    }

    @Override
    public void verifyPendingInstall(int a0, int a1) {
        host.verifyPendingInstall(a0, a1);
    }
}
