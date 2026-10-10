package io.wyrmgate.iam.idp.protocol;

final class IdpJoseAlgorithms {

    private IdpJoseAlgorithms() {}

    static String joseName(String signingAlgorithm) {
        if (signingAlgorithm == null || signingAlgorithm.isBlank()) {
            throw new IllegalStateException("signing algorithm must not be blank");
        }
        return switch (signingAlgorithm.trim().toUpperCase()) {
            case "RS256", "SHA256WITHRSA" -> "RS256";
            case "RS384", "SHA384WITHRSA" -> "RS384";
            case "RS512", "SHA512WITHRSA" -> "RS512";
            default -> throw new IllegalStateException(
                    "first-party IdP currently supports RSA RS256/RS384/RS512 signing only");
        };
    }
}
