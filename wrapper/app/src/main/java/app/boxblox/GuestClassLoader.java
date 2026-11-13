package app.boxblox;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Loads the guest's classes from the archive it was verified from.
 *
 * The engine's Java imports framework classes ({@code android.*},
 * {@code androidx.*}, {@code kotlin.*}, {@code okhttp3.*}, ...) that Roblox
 * bundles in its own dex. Those dex files are appended to the guest's class
 * search path and resolved from the archive, in order, with the wrapper's own
 * classpath as the parent so the platform and the wrapper's entry points stay
 * reachable.
 *
 * <b>Why this is a plain {@link DexClassLoader} and not a dex surgery.</b> The
 * wrapper ships no third-party libraries precisely so that appending Roblox's
 * dex cannot produce a duplicate-class collision: the only names on both sides
 * are the wrapper's own, and those are parent-first by construction.
 *
 * The archive is never copied, rewritten, or repackaged — the loader reads it
 * where the user put it.
 */
final class GuestClassLoader {

    private static final String TAG = "RobloxWrapper";

    private GuestClassLoader() {
    }

    /**
     * Build a loader over {@code apk}'s dex, with {@code optimizedDir} as the
     * oat output.
     *
     * A failed dex2oat is not fatal: the loader falls back to interpreting,
     * which is slow but is the difference between a working client and a
     * startup crash on a device with no room for the oat cache.
     */
    static DexClassLoader create(File apk, File optimizedDir, ClassLoader parent) throws IOException {
        if (!optimizedDir.isDirectory() && !optimizedDir.mkdirs()) {
            throw new IOException("could not create " + optimizedDir);
        }
        String path = apk.getAbsolutePath();
        Log.i(TAG, "guest class path: " + path);
        Log.i(TAG, "guest oat dir:    " + optimizedDir);
        return new DexClassLoader(path, optimizedDir.getAbsolutePath(), null, parent);
    }

    /** The guest's entry-point class names, in the order the wrapper needs them. */
    static List<String> entryPoints() {
        List<String> names = new ArrayList<>();
        names.add(RobloxStore.SPLASH_ACTIVITY);
        names.add(RobloxStore.MAIN_GAME_ACTIVITY);
        return names;
    }

    /** Report which of the guest's entry points this build actually carries. */
    static void probe(ClassLoader loader) {
        for (String name : entryPoints()) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                Log.i(TAG, "guest class present: " + type.getName()
                        + " extends " + type.getSuperclass().getName());
            } catch (ClassNotFoundException e) {
                Log.w(TAG, "guest class missing: " + name);
            }
        }
    }
}
