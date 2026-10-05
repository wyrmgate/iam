package io.wyrmgate.iam.credential.persistence;

import io.wyrmgate.iam.credential.application.CredentialSecretVerifierProvider;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Standalone/development secret-provider adapter. The database stores only an environment-variable
 * reference; the environment contains a BCrypt verifier, never a plaintext password.
 */
public final class EnvironmentBcryptCredentialSecretVerifierProvider
        implements CredentialSecretVerifierProvider {

    public static final String PROVIDER_TYPE = "ENV_BCRYPT";
    private static final Pattern SAFE_REFERENCE = Pattern.compile("IAM_LOCAL_PASSWORD_[A-Z0-9_]{1,160}");

    private final Map<String,String> environment;
    private final PasswordEncoder encoder;

    public EnvironmentBcryptCredentialSecretVerifierProvider(Map<String,String> environment) {
        this(environment, new BCryptPasswordEncoder(12));
    }

    EnvironmentBcryptCredentialSecretVerifierProvider(
            Map<String,String> environment,
            PasswordEncoder encoder) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Verification verify(String referenceKey, char[] presentedSecret) {
        if (referenceKey == null || !SAFE_REFERENCE.matcher(referenceKey).matches()) {
            return Verification.UNAVAILABLE;
        }
        String encoded = environment.get(referenceKey);
        if (encoded == null || encoded.isBlank()) return Verification.UNAVAILABLE;
        try {
            return encoder.matches(new String(presentedSecret), encoded)
                    ? Verification.MATCH
                    : Verification.NO_MATCH;
        } catch (RuntimeException invalidVerifier) {
            return Verification.UNAVAILABLE;
        }
    }
}