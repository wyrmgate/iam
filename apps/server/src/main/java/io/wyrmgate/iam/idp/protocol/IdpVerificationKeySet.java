package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One validation policy shared by signing, discovery and JWKS. No private material. */
final class IdpVerificationKeySet {
    private IdpVerificationKeySet() {}

    static List<SigningKeyMaterial> validated(SigningKeyProvider provider, SigningKeyMaterial current) {
        List<SigningKeyMaterial> keys = provider.verificationKeys();
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("at least one signing verification key is required");
        }
        Set<String> keyIds = new HashSet<>();
        boolean currentPresent = false;
        for (SigningKeyMaterial key : keys) {
            if (key == null || !keyIds.add(key.keyId())) {
                throw new IllegalStateException("signing verification key IDs must be unique");
            }
            if (current.keyId().equals(key.keyId())) {
                if (!current.signingAlgorithm().equals(key.signingAlgorithm())
                        || !Arrays.equals(current.publicKey().getEncoded(), key.publicKey().getEncoded())) {
                    throw new IllegalStateException("current signing key material must match published verification key");
                }
                currentPresent = true;
            }
            IdpJwkEncoder.encode(key);
        }
        if (!currentPresent) {
            throw new IllegalStateException("current signing key must be published for verification");
        }
        return keys.stream()
                .sorted(Comparator.comparing((SigningKeyMaterial key) -> !current.keyId().equals(key.keyId()))
                        .thenComparing(SigningKeyMaterial::keyId))
                .toList();
    }
}
