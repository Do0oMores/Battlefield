package top.mores.battlefield.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ServerKeyValidator {

    // 这是合法密钥的 SHA-256 值（十六进制小写）。
    // 如需更换授权密钥，请先计算新密钥的 SHA-256 并替换此值。
    private static final String EXPECTED_KEY_SHA256 = "213438fefe67517de8ea43e893f851a6a5782bb522f611387090300102c23bb9";

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
