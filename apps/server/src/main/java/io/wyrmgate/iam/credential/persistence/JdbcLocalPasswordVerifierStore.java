package io.wyrmgate.iam.credential.persistence;

import io.wyrmgate.iam.credential.application.CredentialSecretVerifier;
import io.wyrmgate.iam.credential.application.PasswordSecretEnrollment;
import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Credential-private verifier store for first-party PASSWORD authentication.
 * The table contains salted one-way verifier material only; plaintext never crosses this adapter.
 */
public final class JdbcLocalPasswordVerifierStore
        implements CredentialSecretVerifier, PasswordSecretEnrollment {

    static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int ITERATIONS = 310_000;
    static final int KEY_BITS = 256;
    static final int SALT_BYTES = 32;

    private final JdbcTemplate jdbc;
    private final SecureRandom random;

    public JdbcLocalPasswordVerifierStore(JdbcTemplate jdbc) {
        this(jdbc, new SecureRandom());
    }

    JdbcLocalPasswordVerifierStore(JdbcTemplate jdbc, SecureRandom random) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public boolean supports(String providerType) {
        return PROVIDER_TYPE.equals(providerType);
    }

    @Override
    public SecretReference ensure(
            TenantContext tenant,
            UUID credentialId,
            char[] password,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(now, "now");
        if (password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        String referenceKey = credentialId.toString();
        StoredVerifier existing = find(referenceKey);
        if (existing != null) {
            requireOwnership(existing, tenant, credentialId);
            if (!matchesStored(existing, password)) {
                throw new IllegalStateException(
                        "local password verifier already exists for the bootstrap Credential");
            }
            return new SecretReference(PROVIDER_TYPE, referenceKey);
        }

        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS, KEY_BITS);
        try {
            jdbc.update(
                    """
                    INSERT INTO credential.local_password_verifier (
                        reference_key, tenant_id, credential_id,
                        algorithm, iterations, salt, verifier, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_key) DO NOTHING
                    """,
                    referenceKey,
                    tenant.tenantId(),
                    credentialId,
                    ALGORITHM,
                    ITERATIONS,
                    salt,
                    derived,
                    Timestamp.from(now));
        } finally {
            Arrays.fill(derived, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }

        StoredVerifier persisted = find(referenceKey);
        if (persisted == null) {
            throw new IllegalStateException("local password verifier could not be reloaded");
        }
        requireOwnership(persisted, tenant, credentialId);
        if (!matchesStored(persisted, password)) {
            throw new IllegalStateException(
                    "local password verifier conflict for the bootstrap Credential");
        }
        return new SecretReference(PROVIDER_TYPE, referenceKey);
    }

    @Override
    public boolean matches(SecretReference reference, char[] presentedSecret) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(presentedSecret, "presentedSecret");
        if (!supports(reference.providerType())) return false;
        StoredVerifier stored = find(reference.referenceKey());
        return stored != null && matchesStored(stored, presentedSecret);
    }

    private StoredVerifier find(String referenceKey) {
        List<StoredVerifier> rows = jdbc.query(
                """
                SELECT reference_key, tenant_id, credential_id,
                       algorithm, iterations, salt, verifier
                FROM credential.local_password_verifier
                WHERE reference_key = ?
                """,
                (rs, rowNum) -> new StoredVerifier(
                        rs.getString("reference_key"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("credential_id", UUID.class),
                        rs.getString("algorithm"),
                        rs.getInt("iterations"),
                        rs.getBytes("salt"),
                        rs.getBytes("verifier")),
                referenceKey);
        return rows.stream().findFirst().orElse(null);
    }

    private static void requireOwnership(
            StoredVerifier stored,
            TenantContext tenant,
            UUID credentialId) {
        if (!stored.tenantId().equals(tenant.tenantId())
                || !stored.credentialId().equals(credentialId)) {
            throw new IllegalStateException("local password verifier reference ownership conflict");
        }
    }

    private static boolean matchesStored(StoredVerifier stored, char[] password) {
        if (!ALGORITHM.equals(stored.algorithm()) || stored.iterations() < 1) {
            return false;
        }
        byte[] candidate = derive(password, stored.salt(), stored.iterations(), stored.verifier().length * 8);
        try {
            return MessageDigest.isEqual(stored.verifier(), candidate);
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private static byte[] derive(
            char[] password,
            byte[] salt,
            int iterations,
            int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("local password verifier algorithm is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private record StoredVerifier(
            String referenceKey,
            UUID tenantId,
            UUID credentialId,
            String algorithm,
            int iterations,
            byte[] salt,
            byte[] verifier) {
    }
}
