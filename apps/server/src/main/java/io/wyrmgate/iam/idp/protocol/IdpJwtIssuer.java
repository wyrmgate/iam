package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.idp.protocol.IdpTokenIssuer.TokenPair;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

public final class IdpJwtIssuer implements IdpTokenIssuer {

    public static final Duration DEFAULT_TOKEN_LIFETIME = Duration.ofMinutes(5);

    private final String issuer;
    private final SigningKeyProvider signingKeys;
    private final JsonMapper json;
    private final Duration tokenLifetime;

    public IdpJwtIssuer(
            IdpProtocolProperties properties,
            SigningKeyProvider signingKeys,
            JsonMapper json) {
        this(properties, signingKeys, json, DEFAULT_TOKEN_LIFETIME);
    }

    IdpJwtIssuer(
            IdpProtocolProperties properties,
            SigningKeyProvider signingKeys,
            JsonMapper json,
            Duration tokenLifetime) {
        this.issuer = properties.requiredIssuer().toString();
        this.signingKeys = Objects.requireNonNull(signingKeys, "signingKeys");
        this.json = Objects.requireNonNull(json, "json");
        this.tokenLifetime = requirePositive(tokenLifetime);
        IdpJwkEncoder.encode(signingKeys.currentSigningKey());
    }

    @Override
    public TokenPair issue(
            TenantContext tenant,
            IdpAuthorizationCode code,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(now, "now");

        String subject = subjectFor(tenant, code.identityId().toString());
        Instant expiresAt = now.plus(tokenLifetime);
        long issuedAt = now.getEpochSecond();
        long expires = expiresAt.getEpochSecond();

        Map<String, Object> idClaims = new LinkedHashMap<>();
        idClaims.put("iss", issuer);
        idClaims.put("sub", subject);
        idClaims.put("aud", code.clientId());
        idClaims.put("iat", issuedAt);
        idClaims.put("exp", expires);
        idClaims.put("auth_time", code.authTime().getEpochSecond());
        if (code.nonce() != null) {
            idClaims.put("nonce", code.nonce());
        }

        Map<String, Object> accessClaims = new LinkedHashMap<>();
        accessClaims.put("iss", issuer);
        accessClaims.put("sub", subject);
        accessClaims.put("aud", code.clientId());
        accessClaims.put("iat", issuedAt);
        accessClaims.put("exp", expires);
        accessClaims.put("client_id", code.clientId());
        accessClaims.put(
                "scope",
                code.scopes().stream()
                        .map(SsoClientScope::protocolValue)
                        .sorted()
                        .collect(Collectors.joining(" ")));

        return new TokenPair(
                sign("JWT", idClaims),
                sign("at+jwt", accessClaims),
                tokenLifetime.toSeconds());
    }

    private String sign(String type, Map<String, Object> claims) {
        SigningKeyMaterial material = signingKeys.currentSigningKey();
        String algorithm = IdpJoseAlgorithms.joseName(material.signingAlgorithm());

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", algorithm);
        header.put("kid", material.keyId());
        header.put("typ", type);

        try {
            String encodedHeader = base64Url(json.writeValueAsBytes(header));
            String encodedClaims = base64Url(json.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            byte[] signature = signingKeys.sign(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode IdP token claims", exception);
        }
    }

    private static String subjectFor(TenantContext tenant, String identityId) {
        String material = "wyrmgate-sub-v1|" + tenant.tenantId() + "|" + identityId;
        return base64Url(sha256(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "tokenLifetime");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("tokenLifetime must be positive");
        }
        return value;
    }

}
