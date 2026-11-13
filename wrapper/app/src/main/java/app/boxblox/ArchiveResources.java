package app.boxblox;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * The guest's own {@code Resources}, opened from the archive.
 *
 * Roblox's Java resolves its layouts, strings and assets from its own
 * {@code resources.arsc}. The wrapper's resources have none of them, so a
 * {@code Resources} over the archive is what the guest has to be handed — the
 * same thing the platform does when it loads an installed app.
 *
 * The archive is added to a fresh {@code AssetManager} by path. That is the
 * documented-but-hidden mechanism the platform itself uses, and it reads the
 * file without extracting it.
 */
final class ArchiveResources {

    private static final String TAG = "RobloxWrapper";

    private final RobloxStore.Build build;
    private final Resources host;

    ArchiveResources(RobloxStore.Build build, Resources host) {
        this.build = build;
        this.host = host;
    }

    /**
     * A {@code Resources} over the archive, or the host's own when the archive
     * cannot be opened.
     *
     * Falling back is deliberate: the guest's resources are how it draws its
     * own UI, but a client that cannot open them still has a native surface,
     * and losing the whole session over a resource table would be worse than
     * losing the Java-drawn chrome.
     */
    Resources open() {
        try {
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object cookie = addAssetPath.invoke(assets, build.getApk().getAbsolutePath());
            if (cookie instanceof Integer id && id == 0) {
                Log.w(TAG, "addAssetPath refused " + build.getApk());
                return host;
            }
            Resources resources = new Resources(
                    assets,
                    host.getDisplayMetrics(),
                    host.getConfiguration());
            Log.i(TAG, "opened the archive's resources: " + build.getApk());
            return resources;
        } catch (Throwable t) {
            Log.w(TAG, "could not open the archive's resources", t);
            return host;
        }
    }
}
