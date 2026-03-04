package top.mores.battlefield.server;

public final class LicenseVerifier {
    private LicenseVerifier() {
    }

    public static VerificationResult verify(String licensePath) {
        return VerificationResult.valid("license verification disabled");
    }

    public record VerificationResult(boolean valid, String message) {
        public static VerificationResult valid(String message) {
            return new VerificationResult(true, message == null ? "ok" : message);
        }

        public static VerificationResult invalid(String message) {
            return new VerificationResult(false, message == null ? "invalid" : message);
        }
    }
}
