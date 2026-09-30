package io.wyrmgate.iam.credential.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.credential.domain.CredentialModels.*;
import io.wyrmgate.iam.credential.persistence.JdbcCredentialBoundaryScheduler;
import io.wyrmgate.iam.credential.persistence.JdbcCredentialRepository;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CredentialPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-29T08:45:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcScheduledWorkRepository scheduledWork;
    private static TransactionExecutor transactions;

    private TenantContext tenant;
    private JdbcCredentialRepository repository;
    private CredentialService credentials;
    private CredentialRotationService rotations;
    private final Set<UUID> knownPrincipals =
            new HashSet<>();
    private final Set<UUID> knownIdentities =
            new HashSet<>();

    private final IdentityAccessReferenceQuery identities =
            new IdentityAccessReferenceQuery() {
                @Override
                public boolean identityExists(
                        TenantContext requestedTenant,
                        UUID identityId) {
                    return knownIdentities.contains(
                            identityId);
                }

                @Override
                public PrincipalReference principal(
                        TenantContext requestedTenant,
                        UUID principalId) {
                    return knownPrincipals.contains(
                            principalId)
                            ? PrincipalReference.uncorrelated(
                                    UUID.nameUUIDFromBytes(
                                            ("target-" + principalId)
                                                    .getBytes()))
                            : PrincipalReference.notFound();
                }

                @Override
                public PrincipalSelection
                        selectUniqueActivePrincipal(
                                TenantContext requestedTenant,
                                UUID identityId,
                                UUID applicationTargetId) {
                    return PrincipalSelection.none();
                }
            };

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current()
                .getVersion().getVersion())
                .isEqualTo("43");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        scheduledWork = new JdbcScheduledWorkRepository(
                jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    credential.credential_rotation,
                    credential.credential,
                    platform.scheduled_work,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create(
                        "Credential test", NOW).id());
        knownPrincipals.clear();
        knownIdentities.clear();
        repository = new JdbcCredentialRepository(jdbc);
        CredentialBoundaryScheduler boundaries =
                new JdbcCredentialBoundaryScheduler(
                        scheduledWork);
        credentials = new CredentialService(
                repository,
                identities,
                boundaries,
                ids,
                transactions);
        rotations = new CredentialRotationService(
                repository,
                identities,
                ids,
                transactions);
    }

    @Test
    void credentialStoresOpaqueReferenceOnlyAndRequiresPrincipal() {
        UUID missing = ids.nextId();
        assertThatThrownBy(() -> credentials.create(
                tenant,
                missing,
                CredentialKind.API_KEY,
                new SecretReference(
                        "vault", "iam/service/api-key"),
                null,
                null,
                NOW))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "Principal does not exist");

        UUID principalId = principal();
        Credential created = credentials.create(
                tenant,
                principalId,
                CredentialKind.API_KEY,
                new SecretReference(
                        "vault", "iam/service/api-key"),
                null,
                null,
                NOW);

        assertThat(created.state())
                .isEqualTo(CredentialState.ACTIVE);
        assertThat(created.principalId())
                .isEqualTo(principalId);
        assertThat(created.secretReference())
                .isEqualTo(
                        new SecretReference(
                                "vault",
                                "iam/service/api-key"));

        List<String> columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'credential'
                  AND table_name = 'credential'
                ORDER BY ordinal_position
                """,
                String.class);
        assertThat(columns)
                .contains(
                        "secret_provider_type",
                        "secret_reference_key")
                .doesNotContain(
                        "secret_value",
                        "private_key",
                        "private_value",
                        "password_value",
                        "token_value",
                        "client_secret");

        Set<String> recordComponents =
                java.util.Arrays.stream(
                        Credential.class
                                .getRecordComponents())
                        .map(
                                java.lang.reflect
                                        .RecordComponent
                                        ::getName)
                        .collect(
                                java.util.stream.Collectors
                                        .toSet());
        assertThat(recordComponents)
                .doesNotContain(
                        "secretValue",
                        "privateKey",
                        "password",
                        "token",
                        "clientSecret");
    }

    @Test
    void scheduledCredentialActivatesAndExpiresBySemanticBoundary() {
        UUID principalId = principal();
        Instant validFrom =
                NOW.plusSeconds(60);
        Instant validUntil =
                NOW.plusSeconds(120);

        Credential scheduled = credentials.create(
                tenant,
                principalId,
                CredentialKind.CERTIFICATE,
                new SecretReference(
                        "kms",
                        "certificates/workload-1"),
                validFrom,
                validUntil,
                NOW);

        assertThat(scheduled.state())
                .isEqualTo(CredentialState.SCHEDULED);
        assertThat(scheduled.effectiveAt(NOW))
                .isFalse();

        CredentialBoundaryProcessingService activation =
                new CredentialBoundaryProcessingService(
                        scheduledWork,
                        credentials,
                        Clock.fixed(
                                validFrom,
                                ZoneOffset.UTC));
        assertThat(activation.processDue())
                .isEqualTo(1);

        Credential active = repository.findCredential(
                        tenant, scheduled.id())
                .orElseThrow();
        assertThat(active.state())
                .isEqualTo(CredentialState.ACTIVE);
        assertThat(active.effectiveAt(validFrom))
                .isTrue();

        CredentialBoundaryProcessingService expiry =
                new CredentialBoundaryProcessingService(
                        scheduledWork,
                        credentials,
                        Clock.fixed(
                                validUntil,
                                ZoneOffset.UTC));
        assertThat(expiry.processDue())
                .isEqualTo(1);

        Credential expired = repository.findCredential(
                        tenant, scheduled.id())
                .orElseThrow();
        assertThat(expired.state())
                .isEqualTo(CredentialState.EXPIRED);
        assertThat(expired.effectiveAt(validUntil))
                .isFalse();
    }

    @Test
    void compromiseIsImmediatelyUnsafeAndCanBeAuthoritativelyRevoked() {
        Credential active = activeCredential(
                principal(),
                CredentialKind.PASSWORD,
                "passwords/service-a");

        Credential compromised =
                credentials.compromise(
                        tenant,
                        active.id(),
                        active.revision(),
                        NOW.plusSeconds(1));

        assertThat(compromised.state())
                .isEqualTo(
                        CredentialState.COMPROMISED);
        assertThat(compromised.effectiveAt(
                NOW.plusSeconds(1)))
                .isFalse();
        assertThat(compromised.compromisedAt())
                .isEqualTo(NOW.plusSeconds(1));

        Credential revoked =
                credentials.revoke(
                        tenant,
                        compromised.id(),
                        compromised.revision(),
                        NOW.plusSeconds(2));
        assertThat(revoked.state())
                .isEqualTo(CredentialState.REVOKED);
        assertThat(revoked.compromisedAt())
                .isEqualTo(
                        compromised.compromisedAt());
        assertThat(revoked.revokedAt())
                .isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void credentialMutationRejectsStaleRevision() {
        Credential active = activeCredential(
                principal(),
                CredentialKind.SSH_KEY,
                "ssh/service-b");

        Credential compromised =
                credentials.compromise(
                        tenant,
                        active.id(),
                        active.revision(),
                        NOW.plusSeconds(1));

        assertThatThrownBy(() ->
                credentials.revoke(
                        tenant,
                        active.id(),
                        active.revision(),
                        NOW.plusSeconds(2)))
                .isInstanceOf(
                        StaleWriteException.class);

        assertThat(compromised.revision())
                .isEqualTo(2);
    }

    @Test
    void rotationRequiresSamePrincipalAndStrictCutoverOrdering() {
        UUID principalId = principal();
        UUID otherPrincipal = principal();
        UUID initiator = identity();

        Credential old = activeCredential(
                principalId,
                CredentialKind.OAUTH_CLIENT_SECRET,
                "oauth/client-old");

        CredentialRotation planned =
                rotations.plan(
                        tenant,
                        old.id(),
                        initiator,
                        NOW);
        CredentialRotation creating =
                rotations.beginReplacement(
                        tenant,
                        planned.id(),
                        planned.revision(),
                        NOW.plusSeconds(1));

        Credential wrongPrincipal =
                activeCredential(
                        otherPrincipal,
                        CredentialKind.OAUTH_CLIENT_SECRET,
                        "oauth/client-wrong");
        assertThatThrownBy(() ->
                rotations.attachReplacement(
                        tenant,
                        creating.id(),
                        wrongPrincipal.id(),
                        creating.revision(),
                        NOW.plusSeconds(2)))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "same Principal");

        Credential replacement =
                activeCredential(
                        principalId,
                        CredentialKind.OAUTH_CLIENT_SECRET,
                        "oauth/client-new");
        CredentialRotation distributing =
                rotations.attachReplacement(
                        tenant,
                        creating.id(),
                        replacement.id(),
                        creating.revision(),
                        NOW.plusSeconds(3));
        CredentialRotation verifying =
                rotations.markDistributed(
                        tenant,
                        distributing.id(),
                        distributing.revision(),
                        NOW.plusSeconds(4));
        CredentialRotation cutover =
                rotations.markVerified(
                        tenant,
                        verifying.id(),
                        verifying.revision(),
                        NOW.plusSeconds(5));
        CredentialRotation revoking =
                rotations.beginOldRevocation(
                        tenant,
                        cutover.id(),
                        cutover.revision(),
                        NOW.plusSeconds(6));

        assertThatThrownBy(() ->
                rotations.complete(
                        tenant,
                        revoking.id(),
                        revoking.revision(),
                        NOW.plusSeconds(7)))
                .isInstanceOf(
                        IllegalStateException.class)
                .hasMessageContaining(
                        "old Credential must no longer be effective");

        Credential revokedOld =
                credentials.revoke(
                        tenant,
                        old.id(),
                        old.revision(),
                        NOW.plusSeconds(8));
        assertThat(revokedOld.state())
                .isEqualTo(CredentialState.REVOKED);

        CredentialRotation completed =
                rotations.complete(
                        tenant,
                        revoking.id(),
                        revoking.revision(),
                        NOW.plusSeconds(9));

        assertThat(completed.state())
                .isEqualTo(RotationState.COMPLETED);
        assertThat(completed.completedAt())
                .isEqualTo(NOW.plusSeconds(9));

        assertThatThrownBy(() ->
                rotations.fail(
                        tenant,
                        completed.id(),
                        completed.revision(),
                        RotationState.FAILED,
                        "late_failure",
                        NOW.plusSeconds(10)))
                .isInstanceOf(
                        IllegalStateException.class)
                .hasMessageContaining(
                        "terminal CredentialRotation");
    }

    @Test
    void onlyOneOpenRoutineRotationMayExistForOldCredential() {
        UUID principalId = principal();
        UUID initiator = identity();
        Credential old = activeCredential(
                principalId,
                CredentialKind.API_KEY,
                "api/concurrent-old");

        CredentialRotation first = rotations.plan(
                tenant,
                old.id(),
                initiator,
                NOW);
        assertThat(first.state())
                .isEqualTo(RotationState.PLANNED);

        assertThatThrownBy(() ->
                rotations.plan(
                        tenant,
                        old.id(),
                        initiator,
                        NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "routine rotation already exists");
    }

    @Test
    void replacementMustBeEffectiveBeforeCutover() {
        UUID principalId = principal();
        UUID initiator = identity();
        Credential old = activeCredential(
                principalId,
                CredentialKind.API_KEY,
                "api/old");

        CredentialRotation planned =
                rotations.plan(
                        tenant,
                        old.id(),
                        initiator,
                        NOW);
        CredentialRotation creating =
                rotations.beginReplacement(
                        tenant,
                        planned.id(),
                        planned.revision(),
                        NOW.plusSeconds(1));

        Credential future = credentials.create(
                tenant,
                principalId,
                CredentialKind.API_KEY,
                new SecretReference(
                        "vault", "api/future"),
                NOW.plusSeconds(120),
                null,
                NOW.plusSeconds(2));

        CredentialRotation distributing =
                rotations.attachReplacement(
                        tenant,
                        creating.id(),
                        future.id(),
                        creating.revision(),
                        NOW.plusSeconds(3));
        CredentialRotation verifying =
                rotations.markDistributed(
                        tenant,
                        distributing.id(),
                        distributing.revision(),
                        NOW.plusSeconds(4));

        assertThatThrownBy(() ->
                rotations.markVerified(
                        tenant,
                        verifying.id(),
                        verifying.revision(),
                        NOW.plusSeconds(5)))
                .isInstanceOf(
                        IllegalStateException.class)
                .hasMessageContaining(
                        "must be effective before cutover");
    }

    private Credential activeCredential(
            UUID principalId,
            CredentialKind kind,
            String referenceKey) {
        return credentials.create(
                tenant,
                principalId,
                kind,
                new SecretReference(
                        "vault", referenceKey),
                null,
                null,
                NOW);
    }

    private UUID principal() {
        UUID id = ids.nextId();
        knownPrincipals.add(id);
        return id;
    }

    private UUID identity() {
        UUID id = ids.nextId();
        knownIdentities.add(id);
        return id;
    }
}
