package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class IdpJwkEncoder {

    private IdpJwkEncoder() {}

    static Map<String, Object> encode(SigningKeyMaterial material) {
        if (!(material.publicKey() instanceof RSAPublicKey rsa)) {
            throw new IllegalStateException(
                    "first-party IdP JWKS currently supports RSA signing keys only; configured key algorithm is unsupported");
        }
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("kid", material.keyId());
        jwk.put("alg", IdpJoseAlgorithms.joseName(material.signingAlgorithm()));
        jwk.put("n", unsigned(rsa.getModulus()));
        jwk.put("e", unsigned(rsa.getPublicExponent()));
        return Map.copyOf(jwk);
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] stripped = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, stripped, 0, stripped.length);
            bytes = stripped;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
