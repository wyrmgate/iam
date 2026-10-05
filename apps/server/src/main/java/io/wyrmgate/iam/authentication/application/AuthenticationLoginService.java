package io.wyrmgate.iam.authentication.application;

import io.wyrmgate.iam.authentication.domain.AuthenticationAssurance;
import io.wyrmgate.iam.authentication.domain.AuthenticationLoginBinding;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import io.wyrmgate.iam.credential.application.CredentialAuthenticatorVerifier;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** First-party login orchestration. Raw password/session authority remains transient request/result material. */
public final class AuthenticationLoginService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthenticationService authentication;
    private final AuthenticationProtocolQuery protocolQuery;
    private final CredentialAuthenticatorVerifier credentials;

    public AuthenticationLoginService(
            AuthenticationService authentication,
            AuthenticationProtocolQuery protocolQuery,
            CredentialAuthenticatorVerifier credentials) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.protocolQuery = Objects.requireNonNull(protocolQuery, "protocolQuery");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    public LoginResult login(
            TenantContext tenant,
            String loginIdentifier,
            char[] presentedPassword,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(presentedPassword, "presentedPassword");
        Objects.requireNonNull(now, "now");
        try {
            AuthenticationLoginBinding binding;
            try {
                binding = authentication.resolveActiveLogin(tenant, loginIdentifier);
            } catch (RuntimeException unknownOrIneligible) {
                // Keep caller-visible failure intentionally enumeration-neutral.
                throw new AuthenticationFailedException();
            }

            CredentialAuthenticatorVerifier.Result verification = credentials.verifyPassword(
                    tenant, binding.principalId(), presentedPassword, now);
            if (verification.status() == CredentialAuthenticatorVerifier.Status.UNAVAILABLE) {
                throw new AuthenticationDependencyUnavailableException();
            }
            if (verification.status() != CredentialAuthenticatorVerifier.Status.VERIFIED) {
                throw new AuthenticationFailedException();
            }

            String secret = randomSecret();
            String digest = digest(secret);
            AuthenticationAssurance assurance = verification.strength()
                            == CredentialAuthenticatorVerifier.Strength.STRONG
                    ? AuthenticationAssurance.STRONG
                    : AuthenticationAssurance.BASELINE;
            AuthenticationSession session = authentication.createSession(
                    tenant, binding.principalId(), assurance, digest, now);
            return new LoginResult(session, secret);
        } finally {
            // Credential verification also clears its copy path, but this outer guard covers
            // unknown-login and pre-verification failures as well.
            Arrays.fill(presentedPassword, '\0');
        }
    }

    public AuthenticationProtocolQuery.ResolvedSession resolveSession(String rawSessionSecret) {
        if (rawSessionSecret == null || rawSessionSecret.isBlank()) throw new AuthenticationFailedException();
        return protocolQuery.findActiveSessionBySecretDigest(digest(rawSessionSecret))
                .orElseThrow(AuthenticationFailedException::new);
    }

    public record LoginResult(AuthenticationSession session, String sessionSecret) {
        public LoginResult {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(sessionSecret, "sessionSecret");
        }
    }

    public static final class AuthenticationFailedException extends RuntimeException {
        public AuthenticationFailedException() {
            super("authentication failed");
        }
    }

    public static final class AuthenticationDependencyUnavailableException extends RuntimeException {
        public AuthenticationDependencyUnavailableException() {
            super("authentication dependency unavailable");
        }
    }

    private static String randomSecret() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}