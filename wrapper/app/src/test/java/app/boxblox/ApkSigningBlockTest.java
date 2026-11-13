package app.boxblox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * The signing-block reader, against the real archives.
 *
 * <p>This is the check that decides whether a config split is accepted, and it
 * cannot be exercised through {@code PackageManager} — the platform refuses to
 * parse a split at all. So it is tested here, on the JVM, against the archives
 * the project keeps in {@code ../../../apk}.
 */
public final class ApkSigningBlockTest {

    private static final byte[] ROBLOX_CERT =
            hexToBytes("2bebd189e8d3106401347056c93d045b61e20e22d0c3cbed85474aeb00a3d12a");

    @Test
    public void theBaseArchiveIsSignedByRoblox() throws Exception {
        File apk = find("apk/roblox.apk");
        Assume.assumeNotNull(apk);
        assertTrue("base archive must carry Roblox's certificate",
                ApkSigningBlock.signedBy(apk, ROBLOX_CERT));
    }

    @Test
    public void theConfigSplitIsSignedByRoblox() throws Exception {
        File split = find("apk/x/config.arm64_v8a.apk");
        Assume.assumeNotNull(split);
        // The split is what PackageManager cannot read, so this is the only
        // path by which it can be verified at all.
        assertTrue("config split must carry Roblox's certificate",
                ApkSigningBlock.signedBy(split, ROBLOX_CERT));
    }

    @Test
    public void theSignerListIsNotEmpty() throws Exception {
        File apk = find("apk/roblox.apk");
        Assume.assumeNotNull(apk);
        List<byte[]> certs = ApkSigningBlock.signerCertificates(apk);
        assertFalse("the archive carries signer certificates", certs.isEmpty());
    }

    @Test
    public void aDifferentCertificateIsRefused() throws Exception {
        File apk = find("apk/roblox.apk");
        Assume.assumeNotNull(apk);
        byte[] notRoblox = new byte[32];
        notRoblox[0] = 1;
        assertFalse("an unrelated digest must not match",
                ApkSigningBlock.signedBy(apk, notRoblox));
    }

    @Test
    public void aNonApkHasNoSigningBlock() throws Exception {
        File notAnApk = find("apk/manifest.xml");
        Assume.assumeNotNull(notAnApk);
        assertEquals("a file that is not an archive carries no signers",
                0, ApkSigningBlock.signerCertificates(notAnApk).size());
    }

    @org.junit.Rule
    public final org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();

    @Test
    public void tamperedBaseWithOriginalCertificatesIsRefused() throws Exception {
        assertTamperingRefused("apk/roblox.apk");
    }

    @Test
    public void tamperedSplitWithOriginalCertificatesIsRefused() throws Exception {
        assertTamperingRefused("apk/x/config.arm64_v8a.apk");
    }

    @Test
    public void truncatedInputIsRefusedWithoutAllocatingFromUntrustedLengths() throws Exception {
        File malformed = temporary.newFile("truncated.apk");
        java.nio.file.Files.write(malformed.toPath(), new byte[]{0x50, 0x4b, 3, 4});
        assertFalse(ApkSigningBlock.signedBy(malformed, ROBLOX_CERT));
    }

    private void assertTamperingRefused(String path) throws Exception {
        File source = find(path);
        Assume.assumeNotNull(source);
        File copy = new File(temporary.newFolder(), "tampered.apk");
        java.nio.file.Files.copy(source.toPath(), copy.toPath());
        // Alter payload bytes while retaining the entire original signing block.
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(copy, "rw")) {
            file.seek(1024);
            int original = file.read();
            file.seek(1024);
            file.write(original ^ 1);
        }
        assertFalse("a copied certificate must not authenticate modified contents",
                ApkSigningBlock.signedBy(copy, ROBLOX_CERT));
    }

    @Test
    public void splitIdentityIsReadWithoutThePlatformPackageParser() throws Exception {
        File base = find("apk/roblox.apk");
        File split = find("apk/x/config.arm64_v8a.apk");
        Assume.assumeNotNull(base, split);
        ApkSigningBlock.Identity baseIdentity = ApkSigningBlock.identity(base);
        ApkSigningBlock.Identity splitIdentity = ApkSigningBlock.identity(split);
        assertEquals("com.roblox.client", splitIdentity.packageName);
        assertEquals(baseIdentity.versionCode, splitIdentity.versionCode);
    }

    private static File find(String relative) {
        File dir = new File(".").getAbsoluteFile();
        for (int depth = 0; depth < 6 && dir != null; depth++, dir = dir.getParentFile()) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
