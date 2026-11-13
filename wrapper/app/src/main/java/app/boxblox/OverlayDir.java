package app.boxblox;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The user-editable asset overlay: one directory, applied to the build's
 * asset tree on every launch.
 *
 * <p>Mirrors Sober's extra-asset folder: anything placed here lands on top of
 * the unpacked archive's assets, so a file at
 * {@code content/textures/ui/dialog.png} replaces that asset for the next
 * launch. The directory is private to the app, so it is reached through
 * {@link OverlayDocumentsProvider} — the files app shows it as a documents
 * root — and the README regenerates itself whenever it is missing, because a
 * directory whose only documentation can be deleted is a directory nobody can
 * figure out twice.
 */
final class OverlayDir {

    /** The overlay's own name inside the app's files directory. */
    static final String NAME = "overlay";

    private OverlayDir() {
    }

    static File dir(android.content.Context context) {
        return new File(context.getFilesDir(), NAME);
    }

    /** The README's text; one place so regeneration is always current. */
    static String readme() {
        return "Boxblox asset overlay\n"
                + "=====================\n"
                + "\n"
                + "Files placed in this directory are copied over the Roblox\n"
                + "archive's assets the next time you launch. The layout matches\n"
                + "the archive's asset tree, so a file at\n"
                + "\n"
                + "    content/textures/ui/dialog.png\n"
                + "\n"
                + "replaces that texture, and a file that exists in no build is\n"
                + "simply copied along.\n"
                + "\n"
                + "How to use it: open your files app, pick Boxblox (this root is\n"
                + "published as a documents provider), and drop files in. Nothing\n"
                + "is applied to a running session — relaunch to take effect.\n"
                + "\n"
                + "This README comes back if deleted; the rest of the directory is\n"
                + "yours.\n";
    }

    /**
     * Recreate the README when absent, and the directory when absent.
     *
     * <p>Called whenever the directory is listed or the wrapper starts, so the
     * documentation cannot stay missing. Never throws: an overlay that cannot
     * be created must not stop a launch, and a README that cannot be written
     * still leaves the directory usable.
     */
    static void ensure(android.content.Context context) {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File readme = new File(dir, "README.txt");
        if (!readme.isFile()) {
            try {
                Files.write(readme.toPath(), readme().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Regeneration is best-effort by design; see the javadoc.
            }
        }
    }

    /**
     * Copy the overlay over the build's unpacked assets.
     *
     * <p>Never deletes: the overlay only ever adds or replaces, so removing a
     * file here restores the archive's original at the next rebuild of the
     * build's asset tree rather than at the next launch.
     *
     * @return how many files were copied; 0 when the overlay holds nothing
     *         beyond its README.
     */
    static int apply(android.content.Context context, File assetRoot) {
        File dir = dir(context);
        File[] entries = dir.listFiles();
        if (entries == null) {
            return 0;
        }
        int copied = 0;
        for (File entry : entries) {
            if ("README.txt".equals(entry.getName())) {
                continue;
            }
            try {
                copied += copy(entry, new File(assetRoot, entry.getName()));
            } catch (IOException e) {
                android.util.Log.w("RobloxWrapper", "overlay: " + entry.getName(), e);
            }
        }
        return copied;
    }

    private static int copy(File source, File target) throws IOException {
        if (source.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            target.mkdirs();
            int copied = 0;
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copied += copy(child, new File(target, child.getName()));
                }
            }
            return copied;
        }
        //noinspection ResultOfMethodCallIgnored
        target.getParentFile().mkdirs();
        Files.copy(source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return 1;
    }
}
