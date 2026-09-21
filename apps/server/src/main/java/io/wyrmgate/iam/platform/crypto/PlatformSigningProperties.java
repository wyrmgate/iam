package io.wyrmgate.iam.platform.crypto;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.signing")
public record PlatformSigningProperties(
        boolean enabled,
        String keyId,
        String keyAlgorithm,
        String signingAlgorithm,
        String privateKeyPath,
        String publicKeyPath,
        String verificationKeys) {

    public String requiredKeyId() {
        String value = requireText(keyId, "key-id");
        if (!value.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalStateException("iam.signing.key-id must contain only letters, digits, '_' or '-'");
        }
        return value;
    }

    public String requiredKeyAlgorithm() {
        return requireText(keyAlgorithm, "key-algorithm");
    }

    public String requiredSigningAlgorithm() {
        return requireText(signingAlgorithm, "signing-algorithm");
    }

    public Path requiredPrivateKeyPath() {
        return Path.of(requireText(privateKeyPath, "private-key-path"));
    }

    public Path requiredPublicKeyPath() {
        return Path.of(requireText(publicKeyPath, "public-key-path"));
    }

    public Map<String, Path> verificationPublicKeyPaths() {
        Map<String, Path> result = new LinkedHashMap<>();
        if (verificationKeys == null || verificationKeys.isBlank()) {
            return Map.of();
        }
        for (String entry : verificationKeys.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new IllegalStateException(
                        "iam.signing.verification-keys must use keyId=/path/to/public.pem entries separated by ';'");
            }
            String id = trimmed.substring(0, separator).trim();
            String path = trimmed.substring(separator + 1).trim();
            if (!id.matches("[A-Za-z0-9_-]{1,100}")) {
                throw new IllegalStateException("verification key IDs must contain only letters, digits, '_' or '-'");
            }
            if (result.putIfAbsent(id, Path.of(path)) != null) {
                throw new IllegalStateException("duplicate iam.signing verification key ID " + id);
            }
        }
        return Map.copyOf(result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("iam.signing." + name + " is required when signing is enabled");
        }
        return value;
    }
}
