package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.net.URI;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public discovery and verification-key surface for the first-party Wyrmgate identity provider. */
@RestController
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public class IdpProtocolController {

    private final URI issuer;
    private final SigningKeyProvider signingKeys;

    public IdpProtocolController(IdpProtocolProperties properties, SigningKeyProvider signingKeys) {
        this.issuer = properties.requiredIssuer();
        this.signingKeys = signingKeys;
        // Fail startup rather than publish an unusable or incomplete verification-key surface.
        publishableVerificationKeys();
    }

    @GetMapping("/.well-known/openid-configuration")
    ResponseEntity<Map<String, Object>> discovery() {
        SigningKeyMaterial current = signingKeys.currentSigningKey();
        String base = issuer.toString();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.ofEntries(
                        Map.entry("issuer", base),
                        Map.entry("authorization_endpoint", base + "/oauth2/authorize"),
                        Map.entry("token_endpoint", base + "/oauth2/token"),
                        Map.entry("jwks_uri", base + "/oauth2/jwks"),
                        Map.entry("response_types_supported", List.of("code")),
                        Map.entry("grant_types_supported", List.of("authorization_code")),
                        Map.entry("subject_types_supported", List.of("public")),
                        Map.entry(
                                "id_token_signing_alg_values_supported",
                                List.of(IdpJoseAlgorithms.joseName(current.signingAlgorithm()))),
                        Map.entry("code_challenge_methods_supported", List.of("S256")),
                        Map.entry("scopes_supported", List.of("openid", "profile", "email")),
                        Map.entry("token_endpoint_auth_methods_supported", List.of("none"))));
    }

    @GetMapping("/oauth2/jwks")
    ResponseEntity<Map<String, Object>> jwks() {
        List<Map<String, Object>> keys = publishableVerificationKeys().stream()
                .map(IdpJwkEncoder::encode)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("keys", keys));
    }

    private List<SigningKeyMaterial> publishableVerificationKeys() {
        SigningKeyMaterial current = signingKeys.currentSigningKey();
        List<SigningKeyMaterial> keys = signingKeys.verificationKeys();
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("at least one signing verification key is required");
        }

        Set<String> keyIds = new HashSet<>();
        boolean currentPresent = false;
        for (SigningKeyMaterial key : keys) {
            if (key == null || !keyIds.add(key.keyId())) {
                throw new IllegalStateException("signing verification key IDs must be unique");
            }
            if (current.keyId().equals(key.keyId())) currentPresent = true;
            IdpJwkEncoder.encode(key);
        }
        if (!currentPresent) {
            throw new IllegalStateException("current signing key must be published for verification");
        }

        return keys.stream()
                .sorted(Comparator
                        .comparing((SigningKeyMaterial key) -> !current.keyId().equals(key.keyId()))
                        .thenComparing(SigningKeyMaterial::keyId))
                .toList();
    }
}
