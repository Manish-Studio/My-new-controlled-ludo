package com.vinaykpro.ludoking.update;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Pure Java streaming SHA-256 checksum verifier for downloaded APK files.
 */
public final class Sha256Verifier {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private Sha256Verifier() {}

    public static boolean isValidSha256Hex(String sha256) {
        if (sha256 == null) {
            return false;
        }
        String trimmed = sha256.trim();
        if (trimmed.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = trimmed.charAt(i);
            boolean isHex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!isHex) {
                return false;
            }
        }
        return true;
    }

    public static String computeSha256(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Target APK file does not exist.");
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
            return computeSha256(in);
        }
    }

    public static String computeSha256(InputStream inputStream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm unavailable.", e);
        }
    }

    /**
     * Verifies file integrity against expected 64-char hex SHA-256.
     * Uses constant-time byte comparison on the hex digest.
     */
    public static boolean verifyFileSha256(File file, String expectedSha256Hex) {
        if (!isValidSha256Hex(expectedSha256Hex)) {
            return false;
        }
        try {
            String actualHex = computeSha256(file).toLowerCase(Locale.US);
            String expectedNorm = expectedSha256Hex.trim().toLowerCase(Locale.US);
            return MessageDigest.isEqual(
                    actualHex.getBytes(StandardCharsets.US_ASCII),
                    expectedNorm.getBytes(StandardCharsets.US_ASCII)
            );
        } catch (IOException e) {
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(out);
    }
}
