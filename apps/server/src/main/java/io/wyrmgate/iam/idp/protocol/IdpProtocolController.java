package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public verification-key surface for the first-party Wyrmgate identity provider. */
@RestController
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public class IdpProtocolController {

    private final SigningKeyProvider signingKeys;

    public IdpProtocolController(IdpProtocolProperties properties, SigningKeyProvider signingKeys) {
        properties.requiredIssuer();
        this.signingKeys = signingKeys;
        // Fail startup rather than publish an unusable verification-key surface.
        IdpJwkEncoder.encode(signingKeys.currentSigningKey());
    }

    @GetMapping("/oauth2/jwks")
    ResponseEntity<Map<String, Object>> jwks() {
        SigningKeyMaterial current = signingKeys.currentSigningKey();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("keys", List.of(IdpJwkEncoder.encode(current))));
    }
}
