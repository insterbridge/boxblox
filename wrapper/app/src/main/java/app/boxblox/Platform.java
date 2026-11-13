package app.boxblox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * The one place the wrapper asks the host platform rather than answering.
 *
 * Kept separate so the shadow classes have no static path back to the real
 * {@code PackageManager}: every host question goes through the
 * {@link Context} the wrapper was installed as.
 */
final class Platform {

    private static final String TAG = "RobloxWrapper";

    private static Context host;

    private Platform() {
    }

    static void install(Context context) {
        host = context.getApplicationContext();
    }

    static boolean hostHasFeature(String name) {
        Context context = host;
        if (context == null) {
            return false;
        }
        return context.getPackageManager().hasSystemFeature(name);
    }

    static ApplicationInfo hostApplicationInfo() {
        Context context = host;
        if (context == null) {
            return null;
        }
        return context.getApplicationInfo();
    }

    static boolean isDesktopHost() {
        Context context = host;
        if (context == null) {
            return false;
        }
        PackageManager pm = context.getPackageManager();
        // A host that reports a PC type is already a desktop; the wrapper then
        // does not have to invent one. This is the same question Roblox asks.
        return pm.hasSystemFeature("android.hardware.type.pc");
    }

    static void log(String message) {
        Log.i(TAG, message);
    }
}
