package app.boxblox;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Owns the Roblox build the wrapper runs.
 *
 * <p>The archive is supplied by the user and is <b>never modified</b>: this
 * class only reads it, verifies that Roblox signed it, and unpacks its payload
 * into app-private storage under a directory keyed by the archives' combined
 * content hash. Nothing here writes to an archive or repackages one.
 *
 * <p><b>Why more than one archive.</b> Roblox ships as a base APK plus an ABI
 * split, and the two halves carry different halves of the payload:
 *
 * <pre>
 *   base APK      classes*.dex, assets/ (the engine's content), resources.arsc
 *   arm64 split   lib/arm64-v8a/libroblox.so (the engine), no assets at all
 * </pre>
 *
 * A wrapper that accepted only one of them would either have no engine or no
 * content. So the store takes the set, verifies every member, and unpacks
 * whichever half each member carries. A downloaded {@code .xapk} is accepted
 * too and expanded into that set.
 */
final class RobloxStore {

    private static final String TAG = "RobloxWrapper";

    /**
     * Roblox Corporation's signing certificate, SHA-256.
     *
     * Taken from the current build's v3.1 signer
     * ({@code apksigner verify --print-certs}): CN=Roblox Corporation,
     * OU=GameEngine. A build signed by anything else is refused, which is the
     * same rule Cordial applies and the reason a mirror is tolerable.
     */
    static final String ROBLOX_CERT_SHA256 =
            "2bebd189e8d3106401347056c93d045b61e20e22d0c3cbed85474aeb00a3d12a";

    static final String ROBLOX_PACKAGE = "com.roblox.client";

    /**
     * The Roblox build this wrapper's native hooks were written against.
     *
     * <p>Those hooks read offsets inside the engine (the mouse-lock field sits
     * in an internal struct), and Roblox moves them between releases. Sober
     * avoids the problem by pinning one build; Boxblox accepts any build, so
     * the hooks verify their own fingerprints at runtime and disable themselves
     * when the offsets have moved.
     *
     * <p>This constant is what the launcher compares against to say so out
     * loud. It is a warning, not a gate: every other feature is version
     * agnostic, and refusing to run a newer build would be worse than running
     * it with one input feature unavailable.
     */
    static final String HOOKED_VERSION = "2.745.1142";

    /** Roblox's own {@code Application}, whose onCreate does process-wide setup. */
    static final String APPLICATION_CLASS = "com.roblox.client.RobloxApplication";

    /** AGDK host: this is the activity that owns the surface and the engine. */
    static final String MAIN_GAME_ACTIVITY = "com.roblox.client.startup.MainGameActivity";
    static final String SPLASH_ACTIVITY = "com.roblox.client.startup.ActivityStartupSplash";

    /** The native AGDK resolves from {@code android.app.lib_name}. */
    static final String REQUIRED_LIB = "libroblox.so";

    /**
     * The ABI this wrapper unpacks.
     *
     * Roblox publishes {@code arm64-v8a} (APKPure serves no x86_64 split, and
     * asking for one returns the same arm64 bundle), so this is the only ABI
     * that can run here. It is derived rather than hardcoded so a future build
     * with an x86_64 split needs no change.
     */
    static final String ABI = android.os.Build.SUPPORTED_ABIS.length > 0
            ? android.os.Build.SUPPORTED_ABIS[0]
            : "arm64-v8a";

    private final Context context;

    RobloxStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private File root() {
        return new File(context.getFilesDir(), "roblox");
    }

    /** Where the user drops archives. */
    private File stagingDir() {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    /**
     * Every archive the user has staged, expanded.
     *
     * A {@code .xapk} is a zip of APKs; it is expanded into its members here so
     * the caller does not have to know that. Order is deterministic — the base
     * archive sorts before its split by name — which keeps the combined content
     * hash stable across launches.
     */
    /**
     * Copy an installed Roblox into the staging directory.
     *
     * <p>Reading another app's APK is allowed without root: the base and split
     * APKs are world-readable by design, and {@code PackageManager} hands back
     * their paths. The copy is made through this app's own file descriptors so
     * the result is a normal staged archive, which means the same signature
     * check and the same {@code prepare} path apply — installing Roblox is not a
     * way around verification.
     *
     * @return the staged files, or an empty list when Roblox is not installed.
     */
    List<File> importInstalledRoblox() throws Rejected {
        // Ask the platform, not this app's own PackageManager.
        //
        // WrapperApplication.getPackageManager() returns the shadow once a build
        // is prepared, and the shadow answers for com.roblox.client out of the
        // archive — so asking it about the installed app returned the wrapper's
        // synthetic record with no split paths, and the import copied the base
        // alone (measured: "installed Roblox reports 1 archive path(s)" while
        // the package really has base + config.arm64_v8a + gmasdk).
        android.content.pm.PackageManager pm =
                context.getApplicationContext().getPackageManager();
        if (pm instanceof ShadowPackageManager) {
            pm = ((ShadowPackageManager) pm).hostManager();
        }
        android.content.pm.PackageInfo info;
        try {
            // The split paths only come back when a match flag asks for them.
            // With flags 0 the platform returns the base and leaves
            // splitSourceDirs null, so the ABI split — the one carrying
            // libroblox.so — was silently dropped and the copy failed
            // verification with a message about the base not being official
            // (measured: "copied 1 archive(s)", then rejected).
            info = pm.getPackageInfo(ROBLOX_PACKAGE,
                    android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return List.of();
        }
        List<String> sources = new ArrayList<>();
        if (info.applicationInfo != null && info.applicationInfo.sourceDir != null) {
            sources.add(info.applicationInfo.sourceDir);
        }
        String[] splits = info.applicationInfo == null ? null
                : info.applicationInfo.splitSourceDirs;
        if (splits != null) {
            java.util.Collections.addAll(sources, splits);
        }
        // splitSourceDirs and splitNames both come back null on this platform,
        // so the ABI split — the one carrying libroblox.so — was dropped and the
        // copy failed as "not an official build" (measured: the package reports
        // splits=[base, config.arm64_v8a, gmasdk] while the API hands back one
        // path and no names). The split files sit beside the base APK under
        // their install names and are readable, so enumerate the directory
        // instead of trusting metadata the platform withholds.
        if (splits == null && info.applicationInfo != null
                && info.applicationInfo.sourceDir != null) {
            File baseDir = new File(info.applicationInfo.sourceDir).getParentFile();
            File[] siblings = baseDir == null ? null : baseDir.listFiles();
            if (siblings != null) {
                for (File sibling : siblings) {
                    String name = sibling.getName();
                    if (sibling.isFile() && name.startsWith("split_") && name.endsWith(".apk")) {
                        sources.add(sibling.getAbsolutePath());
                    }
                }
            }
        }
        if (sources.isEmpty()) {
            throw new Rejected("Roblox is installed but its files could not be read.");
        }
        Diag.set("installed Roblox reports " + sources.size() + " archive path(s)");

        File dir;
        try {
            java.nio.file.Files.createDirectories(root().toPath());
            dir = java.nio.file.Files.createTempDirectory(root().toPath(), "installed-").toFile();
        } catch (IOException e) {
            throw new Rejected("Could not stage installed Roblox: " + e.getMessage());
        }
        List<File> staged = new ArrayList<>();
        for (String source : sources) {
            File from = new File(source);
            if (!from.isFile()) {
                continue;
            }
            // Only the base and the arm64 split matter: the gmasdk and locale
            // splits carry no code the wrapper runs, and copying them would
            // double the storage for nothing.
            String name = from.getName();
            // The base APK carries the dex but no native libraries, so the
            // engine's .so lives in the ABI config split. Dropping that split
            // made the copy verify as "no arm64 libroblox.so" (measured: one
            // archive copied, then rejected). Everything that is not the base
            // and not the ABI split is still skipped — those carry resources for
            // other densities, languages and the GMA SDK, none of which this
            // wrapper runs.
            // The ABI appears in split file names with an underscore
            // (split_config.arm64_v8a.apk) while Build.SUPPORTED_ABIS uses a
            // hyphen ("arm64-v8a"), so a plain contains() never matched and the
            // engine's split was skipped (measured).
            String normalised = name.replace('-', '_');
            if (name.startsWith("split_") && !normalised.contains(ABI.replace('-', '_'))) {
                continue;
            }
            File to = new File(dir, name);
            try (java.io.InputStream in = new java.io.FileInputStream(from);
                 java.io.OutputStream out = new java.io.FileOutputStream(to)) {
                Streams.copy(in, out);
            } catch (java.io.IOException e) {
                throw new Rejected("Could not copy " + name + ": " + e.getMessage());
            }
            staged.add(to);
        }
        if (staged.isEmpty()) {
            throw new Rejected("No usable APK found in the installed Roblox.");
        }
        Diag.set("copied " + staged.size() + " archive(s) from the installed Roblox");
        return staged;
    }

    /**
     * The builds already unpacked under the staging directory.
     *
     * <p>Each build directory is named by its combined SHA-256 and carries the
     * unpacked archive plus its data dir, so a build can be listed and switched
     * to without re-reading the original APK. The version is read from the
     * unpacked base APK, which is the same file {@code prepare} used.
     */
    List<Build> installedBuilds() {
        File root = new File(root(), "builds");
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return List.of();
        }
        List<Build> out = new ArrayList<>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            List<File> archives = BuildFiles.archives(new File(dir, "archives"));
            if (archives.isEmpty() || !new File(dir, "prepared-v2").isFile()) {
                continue;
            }
            try {
                File base = archives.stream().filter(f -> Build.hasEntry(f, "classes.dex"))
                        .findFirst().orElseThrow(() -> new IOException("Missing base APK"));
                PackageInfo pkg = packageArchiveInfo(base);
                String version = pkg != null && pkg.versionName != null
                        ? pkg.versionName : "unknown";
                long code = pkg == null ? 0
                        : (android.os.Build.VERSION.SDK_INT >= 28
                                ? pkg.getLongVersionCode() : pkg.versionCode);
                out.add(new Build(archives, dir.getName(), version, code,
                        new File(dir, "lib"), new File(dir, "assets"), new File(dir, "data")));
            } catch (Throwable t) {
                Diag.set("could not read build " + dir.getName() + ": " + t);
            }
        }
        out.sort(Comparator.comparingLong(Build::getVersionCode).reversed());
        return out;
    }

    Build selectedBuild() {
        String selected = context.getSharedPreferences("boxblox.builds", Context.MODE_PRIVATE)
                .getString("selected", null);
        for (Build build : installedBuilds()) {
            if (build.getSha256().equals(selected)) return build;
        }
        return null;
    }

    void select(Build build) throws Rejected {
        if (!context.getSharedPreferences("boxblox.builds", Context.MODE_PRIVATE).edit()
                .putString("selected", build.getSha256()).commit()) {
            throw new Rejected("Could not save the selected build");
        }
    }

    /**
     * Remove one installed build and everything unpacked for it.
     *
     * <p>The active build is refused: the guest's engine is mapped from that
     * directory, so deleting it mid-process would pull the files out from under
     * a running engine. The caller selects another build first, which is a
     * restart.
     *
     * <p>The sign-in is not in here. The session lives in the app-level
     * {@code databases/roblox-database-default} and {@code shared_prefs/prefs.xml},
     * which this does not touch, so deleting a build does not log the user out.
     */
    void delete(Build build) throws Rejected {
        Build selected = selectedBuild();
        boolean isSelected = selected != null && selected.getSha256().equals(build.getSha256());
        // The active build is refused only while another build can take over:
        // the engine is mapped from that directory, so the process must be
        // pointing somewhere else first. When it is the only build left there
        // is nothing to switch to, and refusing made it undeletable forever —
        // the user would have had to import a replacement just to clean up.
        if (isSelected && installedBuilds().size() > 1) {
            throw new Rejected("Switch to another build before deleting this one.");
        }
        File dir = new File(new File(root(), "builds"), build.getSha256());
        try {
            BuildFiles.delete(dir);
        } catch (IOException e) {
            throw new Rejected("Could not delete " + build.getVersionName() + ": " + e.getMessage());
        }
        if (isSelected) {
            // Drop the selection so the next launch falls back to staged
            // archives instead of resolving to a directory that is gone.
            context.getSharedPreferences("boxblox.builds", Context.MODE_PRIVATE).edit()
                    .remove("selected").apply();
        }
        Diag.set("deleted build " + build.getVersionName() + " (" + build.getSha256() + ")");
    }

    List<File> launchArchives() throws Rejected {
        Build selected = selectedBuild();
        return selected == null ? stagedArchives() : selected.getArchives();
    }

    List<File> stagedArchives() throws Rejected {
        File dir = stagingDir();
        File[] found = dir.listFiles();
        if (found == null) {
            return List.of();
        }
        return expandArchives(Arrays.asList(found));
    }

    List<File> expandArchives(List<File> files) throws Rejected {
        List<File> apks = new ArrayList<>();
        List<File> bundles = new ArrayList<>();
        for (File file : files) {
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (!file.isFile()) continue;
            if (name.endsWith(".xapk") || name.endsWith(".apks") || name.endsWith(".zip")) {
                bundles.add(file);
            } else if (name.endsWith(".apk")) {
                apks.add(file);
            }
        }
        for (File bundle : bundles) {
            apks.addAll(expandBundle(bundle));
        }
        apks.sort(Comparator.comparing(File::getName));
        return apks;
    }

    /** Extract an archive-of-archives into a directory beside it. */
    private List<File> expandBundle(File bundle) throws Rejected {
        String bundleHash = sha256(bundle);
        File dir = new File(root(), "expanded/" + bundleHash);
        List<File> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(bundle)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    continue;
                }
                File target = new File(dir, new File(name).getName());
                if (out.contains(target)) {
                    throw new IOException("Duplicate APK name: " + target.getName());
                }
                if (!target.isFile() || target.length() != entry.getSize()) {
                    java.nio.file.Files.createDirectories(dir.toPath());
                    File temporary = File.createTempFile("member-", ".tmp", dir);
                    try {
                        try (InputStream in = zip.getInputStream(entry);
                             OutputStream os = new FileOutputStream(temporary)) {
                            Streams.copy(in, os);
                        }
                        java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        temporary.delete();
                    }
                }
                out.add(target);
            }
        } catch (IOException e) {
            throw new Rejected("could not read " + bundle.getName() + ": " + e.getMessage());
        }
        if (out.isEmpty()) {
            throw new Rejected(bundle.getName() + " contains no APKs");
        }
        Log.i(TAG, "expanded " + bundle.getName() + " into " + out.size() + " archive(s)");
        return out;
    }

    /**
     * Verify {@code archives} and unpack them. Throws {@link Rejected} with a
     * reason a user can act on rather than a stack trace.
     */
    Build prepare(List<File> archives) throws Rejected {
        if (archives == null || archives.isEmpty()) {
            throw new Rejected("no Roblox archive is staged.\n\n"
                    + "Put the base APK and the " + ABI + " split (or the XAPK) in\n"
                    + stagingDir());
        }

        // Verify the private snapshot, not a mutable external file that could
        // change between verification, hashing, unpacking, and class loading.
        File temporary = null;
        try {
            java.nio.file.Files.createDirectories(root().toPath());
            temporary = java.nio.file.Files.createTempDirectory(root().toPath(), "import-").toFile();
            return prepareSnapshot(BuildFiles.snapshot(archives, temporary), temporary);
        } catch (IOException e) {
            throw new Rejected("Could not retain the archive set: " + e.getMessage());
        } finally {
            if (temporary != null) {
                try { BuildFiles.delete(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private Build prepareSnapshot(List<File> archives, File snapshot) throws Rejected, IOException {
        StringBuilder combined = new StringBuilder();
        String versionName = "unknown";
        long versionCode = -1;
        boolean haveIdentity = false;

        for (File archive : archives) {
            if (!archive.isFile()) {
                throw new Rejected("no APK at " + archive.getAbsolutePath());
            }
            verifySigner(archive);
            ApkSigningBlock.Identity identity = ApkSigningBlock.identity(archive);
            if (!ROBLOX_PACKAGE.equals(identity.packageName)) {
                throw new Rejected("Archive does not belong to Roblox: " + archive.getName());
            }
            if (versionCode >= 0 && versionCode != identity.versionCode) {
                throw new Rejected("The base APK and splits must belong to the same Roblox version.");
            }
            versionCode = identity.versionCode;
            if (Build.hasEntry(archive, "classes.dex")) {
                if (haveIdentity) throw new Rejected("Choose one Roblox build, not multiple base APKs.");
                PackageInfo pkg = packageArchiveInfo(archive);
                if (pkg != null && pkg.versionName != null) versionName = pkg.versionName;
                haveIdentity = true;
            }
            combined.append(sha256(archive));
        }

        if (!haveIdentity) {
            throw new Rejected("no base archive among the staged files.\n\n"
                    + "The " + ABI + " split carries the engine but not the app; "
                    + "stage the base APK (roblox.apk) as well.");
        }

        String digest = sha256(combined.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File buildDir = new File(root(), "builds/" + digest);
        File nativeDir = new File(buildDir, "lib");
        File assetDir = new File(buildDir, "assets");
        File dataDir = new File(buildDir, "data");
        //noinspection ResultOfMethodCallIgnored
        nativeDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        assetDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        dataDir.mkdirs();

        File engine = new File(nativeDir, REQUIRED_LIB);
        File complete = new File(buildDir, "prepared-v2");
        if (!complete.isFile() || !engine.isFile()) {
            java.nio.file.Files.deleteIfExists(complete.toPath());
            // A library's mere presence does not prove an interrupted unpack
            // finished. Rebuild artifacts, but never delete per-build user data.
            BuildFiles.delete(nativeDir);
            BuildFiles.delete(assetDir);
            java.nio.file.Files.createDirectories(nativeDir.toPath());
            java.nio.file.Files.createDirectories(assetDir.toPath());
            for (File archive : archives) {
                unpack(archive, nativeDir, assetDir);
            }
        }
        if (!engine.isFile()) {
            throw new Rejected("no " + ABI + " " + REQUIRED_LIB + " in the staged archives.\n\n"
                    + "The base APK carries no native libraries; stage the " + ABI
                    + " split (config." + ABI.replace('-', '_') + ".apk) or the whole XAPK.");
        }

        File retained = new File(buildDir, "archives");
        if (!complete.isFile() || BuildFiles.archives(retained).size() != archives.size()) {
            BuildFiles.delete(retained);
            java.nio.file.Files.move(snapshot.toPath(), retained.toPath());
        }
        java.nio.file.Files.write(complete.toPath(), new byte[]{1});
        archives = BuildFiles.archives(retained);
        Log.i(TAG, "build " + versionName + " (" + versionCode + ") sha256=" + digest
                + " from " + archives.size() + " archive(s)");
        return new Build(archives, digest, versionName, versionCode, nativeDir, assetDir, dataDir);
    }

    private PackageInfo packageArchiveInfo(File apk) {
        return context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
    }

    /** Verify the signature and payload before accepting a base APK or config split. */
    private void verifySigner(File split) throws Rejected {
        try {
            if (ApkSigningBlock.signedBy(split, hexToBytes(ROBLOX_CERT_SHA256))) {
                return;
            }
        } catch (IOException e) {
            throw new Rejected("could not read " + split.getName() + ": " + e.getMessage());
        }
        throw new Rejected(split.getName() + " isn't an official Roblox build, or it was "
                + "copied incompletely. A config split is only usable together with the base "
                + "APK and the arm64 split from the same install.");
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * Unpack one archive's {@code lib/<ABI>/*.so} and {@code assets/}.
     *
     * <p><b>The read-only chmod is load-bearing.</b> Since Android 10 an app
     * targeting API 29 or higher may not {@code dlopen} a library that sits in
     * a writable location, and everything the app writes is writable by
     * definition. Clearing the write bit is the documented escape: the file is
     * then a read-only artifact, which is what the linker insists on. Without
     * it the engine fails to map with "is not accessible for the namespace".
     */
    private void unpack(File apk, File nativeDir, File assetDir) throws Rejected {
        String libPrefix = "lib/" + ABI + "/";
        // Canonical roots with a trailing separator, so that a sibling such as
        // ".../native-evil" cannot pass as ".../native". Resolved up front
        // because every entry is checked against them.
        String nativeRoot;
        String assetRoot;
        try {
            nativeRoot = nativeDir.getCanonicalPath() + File.separator;
            assetRoot = assetDir.getCanonicalPath() + File.separator;
        } catch (IOException e) {
            throw new Rejected("could not resolve " + nativeDir + ": " + e.getMessage());
        }
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                File target;
                boolean isLibrary;
                if (name.startsWith(libPrefix)) {
                    target = new File(nativeDir, name.substring(libPrefix.length()));
                    isLibrary = true;
                } else if (name.startsWith("assets/")) {
                    target = new File(assetDir, name.substring("assets/".length()));
                    isLibrary = false;
                } else {
                    continue;
                }
                // Zip-slip: an entry such as "lib/arm64-v8a/../../../evil.so"
                // would otherwise be written outside the build directory.
                // Compare canonical paths so the ".." segments are resolved
                // before the decision; the roots above carry a trailing
                // separator, so this also rejects a sibling like "...-evil".
                // A target equal to the root itself (an entry that names the
                // directory) is rejected too: it could not be written anyway.
                String targetPath;
                try {
                    targetPath = target.getCanonicalPath();
                } catch (IOException e) {
                    Log.w(TAG, "skipping " + name + ": " + e.getMessage());
                    continue;
                }
                if (!targetPath.startsWith(isLibrary ? nativeRoot : assetRoot)) {
                    Log.w(TAG, "skipping " + name + ": escapes the build directory");
                    continue;
                }
                if (target.isFile() && target.length() == entry.getSize()) {
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new Rejected("could not create " + parent);
                }
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = new FileOutputStream(target)) {
                    Streams.copy(in, out);
                }
                if (isLibrary) {
                    // Read-only, and no write bit anywhere: see the note above.
                    //noinspection ResultOfMethodCallIgnored
                    target.setWritable(false, false);
                    //noinspection ResultOfMethodCallIgnored
                    target.setReadable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    target.setExecutable(true, false);
                }
            }
        } catch (IOException e) {
            throw new Rejected("could not read " + apk.getName() + ": " + e.getMessage());
        }
        Log.i(TAG, "unpacked " + apk.getName() + " into " + nativeDir.getParent());
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of the platform", e);
        }
    }

    private static String sha256(File file) throws Rejected {
        try (InputStream in = new java.io.FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            while (true) {
                int read = in.read(buffer);
                if (read <= 0) break;
                md.update(buffer, 0, read);
            }
            return hex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new Rejected("could not hash " + file.getName() + ": " + e.getMessage());
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    /** One verified build, unpacked and ready to run. */
    static final class Build {
        private final List<File> archives;
        private final String sha256;
        private final String versionName;
        private final long versionCode;
        private final File nativeDir;
        private final File assetDir;
        private final File dataDir;

        Build(List<File> archives, String sha256, String versionName, long versionCode,
              File nativeDir, File assetDir, File dataDir) {
            this.archives = List.copyOf(archives);
            this.sha256 = sha256;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.nativeDir = nativeDir;
            this.assetDir = assetDir;
            this.dataDir = dataDir;
        }

        List<File> getArchives() { return archives; }

        /** The archive the class loader reads dex from: the base, which has it. */
        File getApk() {
            for (File archive : archives) {
                if (hasEntry(archive, "classes.dex")) return archive;
            }
            return archives.get(0);
        }

        String getSha256() { return sha256; }
        String getVersionName() { return versionName; }
        long getVersionCode() { return versionCode; }
        File getNativeDir() { return nativeDir; }
        File getAssetDir() { return assetDir; }
        File getDataDir() { return dataDir; }
        String getPackageName() { return ROBLOX_PACKAGE; }

        private static boolean hasEntry(File archive, String name) {
            try (ZipFile zip = new ZipFile(archive)) {
                return zip.getEntry(name) != null;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "Build{" + versionName + " (" + versionCode + "), sha256=" + sha256
                    + ", archives=" + Arrays.toString(archives.stream().map(File::getName).toArray()) + '}';
        }
    }

    /** A refusal with a reason, surfaced in the UI verbatim. */
    static final class Rejected extends Exception {
        Rejected(String message) {
            super(message);
        }
    }
}
