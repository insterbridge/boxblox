package app.boxblox;

import android.app.Application;
import android.util.Log;

/** Makes the retained, verified guest APK visible to the process's class loader. */
final class ClassLoaderInstaller {
    private static final String TAG = "RobloxWrapper";

    private ClassLoaderInstaller() {
    }

    static ClassLoader install(Application application, RobloxStore.Build build) {
        try {
            // The store retains a read-only private snapshot before verification.
            // Loading that exact file avoids a second, size-only cached dex copy.
            ClassLoader parent = application.getClassLoader();
            if (!LoaderPath.append(parent, build.getApk(), build.getNativeDir())) {
                Log.w(TAG, "could not append the archive to the app loader");
                return null;
            }
            Log.i(TAG, "app class loader extended with the archive");
            return parent;
        } catch (Throwable t) {
            Log.w(TAG, "could not install the guest loader", t);
            return null;
        }
    }
}
