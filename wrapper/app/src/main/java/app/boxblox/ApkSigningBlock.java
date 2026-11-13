package app.boxblox;

import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** Verifies APK contents and signatures, including config splits and key rotation. */
final class ApkSigningBlock {
    private ApkSigningBlock() {
    }

    /** Only authenticated current signers, never certificates merely present in a block. */
    static List<byte[]> signerCertificates(File apk) throws IOException {
        try {
            // Apply one modern signing policy on every host OS. Roblox's current
            // pin is in v3.1; checking only an old host's v2 signer would reject
            // the same official archive on Android 8 while accepting it on 15.
            ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                    .setMinCheckedPlatformVersion(35)
                    .setMaxCheckedPlatformVersion(35)
                    .build().verify();
            if (!result.isVerified()) {
                return List.of();
            }
            List<byte[]> certificates = new ArrayList<>();
            for (X509Certificate certificate : result.getSignerCertificates()) {
                certificates.add(certificate.getEncoded());
            }
            return certificates;
        } catch (com.android.apksig.apk.ApkFormatException
                 | java.security.GeneralSecurityException | IllegalArgumentException e) {
            return List.of();
        }
    }

    static boolean signedBy(File apk, byte[] expectedSha256) throws IOException {
        List<byte[]> signers = signerCertificates(apk);
        // A co-signer must not authorize an otherwise untrusted archive.
        return signers.size() == 1
                && MessageDigest.isEqual(sha256(signers.get(0)), expectedSha256);
    }

    static final class Identity {
        final String packageName;
        final long versionCode;

        Identity(String packageName, long versionCode) {
            this.packageName = packageName;
            this.versionCode = versionCode;
        }
    }

    static Identity identity(File apk) throws IOException {
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(apk, "r")) {
            java.nio.ByteBuffer manifest = com.android.apksig.apk.ApkUtils.getAndroidManifest(
                    com.android.apksig.util.DataSources.asDataSource(input));
            return new Identity(com.android.apksig.apk.ApkUtils
                    .getPackageNameFromBinaryAndroidManifest(manifest.duplicate()),
                    com.android.apksig.apk.ApkUtils
                            .getLongVersionCodeFromBinaryAndroidManifest(manifest.duplicate()));
        } catch (com.android.apksig.apk.ApkFormatException e) {
            throw new IOException("Invalid APK manifest", e);
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of the platform", e);
        }
    }
}
