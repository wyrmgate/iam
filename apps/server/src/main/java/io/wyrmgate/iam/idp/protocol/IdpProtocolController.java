package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public protocol metadata for the first-party Wyrmgate identity provider. */
@RestController
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public class IdpProtocolController {

    private final URI issuer;
    private final SigningKeyProvider signingKeys;

    public IdpProtocolController(IdpProtocolProperties properties, SigningKeyProvider signingKeys) {
        this.issuer = properties.requiredIssuer();
        this.signingKeys = signingKeys;
        // Fail startup rather than publish an unusable IdP surface.
        IdpJwkEncoder.encode(signingKeys.currentSigningKey());
    }

    @GetMapping("/.well-known/openid-configuration")
    ResponseEntity<Map<String, Object>> discovery() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer.toString());
        metadata.put("jwks_uri", endpoint("/oauth2/jwks"));
        metadata.put("authorization_endpoint", endpoint("/oauth2/authorize"));
        metadata.put("token_endpoint", endpoint("/oauth2/token"));
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code"));
        metadata.put("subject_types_supported", List.of("public"));
        metadata.put("id_token_signing_alg_values_supported", List.of(signingKeys.currentSigningKey().signingAlgorithm()));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("scopes_supported", List.of("openid", "profile", "email"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.copyOf(metadata));
    }

    @GetMapping("/oauth2/jwks")
    ResponseEntity<Map<String, Object>> jwks() {
        SigningKeyMaterial current = signingKeys.currentSigningKey();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("keys", List.of(IdpJwkEncoder.encode(current))));
    }

    private String endpoint(String path) {
        return issuer + path;
    }
}
