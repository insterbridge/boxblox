package app.boxblox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the wrapper does to a real Roblox archive.
 *
 * <p>These run on the JVM against the archive the project keeps in
 * {@code ../../../apk}, and are skipped when it is absent — the repository never
 * ships Roblox code, so a checkout without a downloaded build must still pass.
 *
 * <p>They check what is actually inside the archive — the base identity, and
 * the engine in the arm64 split — because the wrapper's own logic cannot be
 * exercised on the JVM. They do <b>not</b> cover the wrapper: nothing here
 * calls {@code RobloxStore}, so these tests say nothing about how production
 * opens the archive.
 */
public final class ArchiveTest {
    @org.junit.Rule
    public final org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();

    /** Same walk-up the build uses; tolerant of a missing archive. */
    private static File archive() {
        File dir = new File(".").getAbsoluteFile();
        for (int depth = 0; depth < 6 && dir != null; depth++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "apk/roblox.apk");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private static File xapk() {
        File dir = new File(".").getAbsoluteFile();
        for (int depth = 0; depth < 6 && dir != null; depth++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "apk/roblox.xapk");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    public void theBaseArchiveCarriesTheRobloxIdentity() throws Exception {
        File apk = archive();
        Assume.assumeNotNull(apk);
        try (ZipFile zip = new ZipFile(apk)) {
            assertNotNull("manifest", zip.getEntry("AndroidManifest.xml"));
            assertNotNull("classes.dex", zip.getEntry("classes.dex"));
            // The base archive has no natives at all; that is why the split or
            // the XAPK is required, and the wrapper says so rather than
            // failing later inside the linker.
            assertNull(zip.getEntry("lib/arm64-v8a/libroblox.so"));
        }
    }

    @Test
    public void theSplitCarriesTheEngineTheWrapperUnpacks() throws Exception {
        File bundle = xapk();
        Assume.assumeNotNull(bundle);
        File split = extractSplit(bundle);
        try (ZipFile zip = new ZipFile(split)) {
            ZipEntry engine = zip.getEntry("lib/arm64-v8a/libroblox.so");
            assertNotNull("libroblox.so in the arm64 split", engine);
            assertTrue("engine is a real library", engine.getSize() > 100L * 1024 * 1024);
        }
    }

    /**
     * Covers the JDK reader path only: {@link ZipFile} opens read-only, so this
     * hash comparison is guaranteed by the library, not by the wrapper. It is
     * kept as a guard against a future change that writes through the same
     * file, and it is <b>not</b> evidence that {@code RobloxStore} leaves the
     * archive untouched — that property is not testable on the JVM.
     */
    @Test
    public void readingTheArchiveLeavesItByteIdentical() throws Exception {
        File apk = archive();
        Assume.assumeNotNull(apk);
        String before = hash(apk);
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    in.readAllBytes();
                }
            }
        }
        assertEquals("the archive must not be modified by reading it", before, hash(apk));
    }

    private File extractSplit(File bundle) throws Exception {
        File target = new File(temporary.newFolder(), "config.arm64_v8a.apk");
        try (ZipFile zip = new ZipFile(bundle)) {
            ZipEntry entry = zip.getEntry("config.arm64_v8a.apk");
            if (entry == null) {
                fail("the XAPK carries no arm64 split");
            }
            try (InputStream in = zip.getInputStream(entry);
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                Streams.copy(in, out);
            }
        }
        return target;
    }

    private static String hash(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            while (true) {
                int read = in.read(buffer);
                if (read <= 0) break;
                md.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static void assertNull(Object value) {
        org.junit.Assert.assertNull(value);
    }
}
