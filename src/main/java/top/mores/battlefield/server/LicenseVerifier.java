package top.mores.battlefield.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class LicenseVerifier {

    // TODO: 替换成你自己生成并分发许可证时配套的 Ed25519 公钥（X.509/SPKI Base64）
    private static final String PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAE4h37PiGA6A+4sHV8x4N5fI4fCGa2eB8mKX9UQ9r9FY=";

    private LicenseVerifier() {
    }

    public static VerificationResult verify(String licensePath) {
        if (licensePath == null || licensePath.isBlank()) {
            return VerificationResult.invalid("未配置 licenseFile");
        }

        Path path = Path.of(licensePath.trim());
        if (!path.isAbsolute()) {
            path = Path.of("").resolve(path).normalize();
        }

        if (!Files.exists(path)) {
            return VerificationResult.invalid("license 文件不存在: " + path);
        }

        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String declaration = root.get("declaration").getAsString();
            String signatureBase64 = root.get("signature").getAsString();

            boolean ok = verifyEd25519(
                    declaration.getBytes(StandardCharsets.UTF_8),
                    Base64.getDecoder().decode(signatureBase64),
                    loadPublicKey()
            );

            if (!ok) {
                return VerificationResult.invalid("license 验签失败");
            }
            return VerificationResult.valid(declaration);
        } catch (RuntimeException | IOException | GeneralSecurityException e) {
            return VerificationResult.invalid("license 读取/验签异常: " + e.getMessage());
        }
    }

    private static PublicKey loadPublicKey() throws GeneralSecurityException {
        byte[] keyBytes = Base64.getDecoder().decode(PUBLIC_KEY_BASE64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        return keyFactory.generatePublic(keySpec);
    }

    private static boolean verifyEd25519(byte[] payload, byte[] signatureBytes, PublicKey publicKey)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payload);
        return verifier.verify(signatureBytes);
    }

    public record VerificationResult(boolean valid, String message) {
        public static VerificationResult valid(String message) {
            return new VerificationResult(true, message);
        }

        public static VerificationResult invalid(String message) {
            return new VerificationResult(false, message);
        }
    }
}
