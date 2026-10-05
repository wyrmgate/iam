package io.wyrmgate.iam.authentication.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JWT/JWKS adapter over the Platform signing port; private key material is never requested or exposed. */
public final class OidcTokenSigner {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final SigningKeyProvider signingKeys;
    private final ObjectMapper json = new ObjectMapper();

    public OidcTokenSigner(SigningKeyProvider signingKeys) {
        this.signingKeys = Objects.requireNonNull(signingKeys, "signingKeys");
    }

    public String sign(Map<String,Object> claims) {
        SigningKeyMaterial key = signingKeys.currentSigningKey();
        String joseAlgorithm = joseAlgorithm(key.signingAlgorithm(), key.publicKey());
        Map<String,Object> header = Map.of(
                "alg", joseAlgorithm,
                "kid", key.keyId(),
                "typ", "JWT");
        String signingInput = encodeJson(header) + "." + encodeJson(normalizeClaims(claims));
        byte[] signature = signingKeys.sign(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64.encodeToString(signature);
    }

    public Map<String,Object> jwks() {
        SigningKeyMaterial key = signingKeys.currentSigningKey();
        return Map.of("keys", List.of(publicJwk(key)));
    }

    private Map<String,Object> publicJwk(SigningKeyMaterial key) {
        PublicKey publicKey = key.publicKey();
        if (publicKey instanceof RSAPublicKey rsa) {
            return Map.of(
                    "kty", "RSA",
                    "use", "sig",
                    "kid", key.keyId(),
                    "alg", joseAlgorithm(key.signingAlgorithm(), publicKey),
                    "n", unsigned(rsa.getModulus()),
                    "e", unsigned(rsa.getPublicExponent()));
        }
        if (publicKey instanceof ECPublicKey ec) {
            int fieldSize = ec.getParams().getCurve().getField().getFieldSize();
            String curve = switch (fieldSize) {
                case 256 -> "P-256";
                case 384 -> "P-384";
                case 521 -> "P-521";
                default -> throw new IllegalStateException("Unsupported OIDC EC curve size " + fieldSize);
            };
            int octets = (fieldSize + 7) / 8;
            return Map.of(
                    "kty", "EC",
                    "use", "sig",
                    "kid", key.keyId(),
                    "alg", joseAlgorithm(key.signingAlgorithm(), publicKey),
                    "crv", curve,
                    "x", fixed(ec.getW().getAffineX(), octets),
                    "y", fixed(ec.getW().getAffineY(), octets));
        }
        throw new IllegalStateException("Unsupported OIDC signing public key type " + publicKey.getAlgorithm());
    }

    private static String joseAlgorithm(String javaAlgorithm, PublicKey key) {
        String normalized = javaAlgorithm.replace("-", "").replace("_", "").toUpperCase();
        if (key instanceof RSAPublicKey) {
            return switch (normalized) {
                case "SHA256WITHRSA" -> "RS256";
                case "SHA384WITHRSA" -> "RS384";
                case "SHA512WITHRSA" -> "RS512";
                default -> throw new IllegalStateException("Unsupported RSA OIDC signing algorithm " + javaAlgorithm);
            };
        }
        if (key instanceof ECPublicKey) {
            return switch (normalized) {
                case "SHA256WITHECDSA" -> "ES256";
                case "SHA384WITHECDSA" -> "ES384";
                case "SHA512WITHECDSA" -> "ES512";
                default -> throw new IllegalStateException("Unsupported EC OIDC signing algorithm " + javaAlgorithm);
            };
        }
        throw new IllegalStateException("Unsupported OIDC signing public key type " + key.getAlgorithm());
    }

    private String encodeJson(Map<String,Object> value) {
        try {
            return B64.encodeToString(json.writeValueAsBytes(value));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to encode OIDC JWT", failure);
        }
    }

    private static Map<String,Object> normalizeClaims(Map<String,Object> claims) {
        LinkedHashMap<String,Object> normalized = new LinkedHashMap<>();
        claims.forEach((name, value) -> normalized.put(name, value instanceof Instant instant
                ? instant.getEpochSecond()
                : value));
        return Map.copyOf(normalized);
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return B64.encodeToString(bytes);
    }

    private static String fixed(BigInteger value, int octets) {
        byte[] raw = value.toByteArray();
        int start = raw.length > octets && raw[0] == 0 ? 1 : 0;
        int length = raw.length - start;
        if (length > octets) throw new IllegalStateException("EC coordinate exceeds curve size");
        byte[] fixed = new byte[octets];
        System.arraycopy(raw, start, fixed, octets - length, length);
        return B64.encodeToString(fixed);
    }
}