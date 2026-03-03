package top.mores.battlefield.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ServerKeyValidator {

    private static final String EXPECTED_KEY_SHA256 = "933ea88ccc0777a9c3fb444bfab8f37376a1b57ee83e2c5d0d5fc4fbdf8870d7";

    private ServerKeyValidator() {
    }

    public static boolean isValid(String providedKey) {
        if (providedKey == null || providedKey.isBlank()) {
            return false;
        }

        String providedHash = sha256Hex(providedKey.trim());
        return MessageDigest.isEqual(
                providedHash.getBytes(StandardCharsets.UTF_8),
                EXPECTED_KEY_SHA256.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
