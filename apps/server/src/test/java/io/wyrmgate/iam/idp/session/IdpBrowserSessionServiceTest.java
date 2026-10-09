package io.wyrmgate.iam.idp.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService.VerifiedCredential;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery.AuthenticationSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdpBrowserSessionServiceTest {

    private static final TenantContext TENANT = new TenantContext(UUID.randomUUID());
    private static final UUID PRINCIPAL_ID = UUID.randomUUID();
    private static final UUID IDENTITY_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-06T02:00:00Z");

    @Test
    void establishmentAlwaysRotatesOpaqueTokenAndResolutionRevalidatesCurrentState() {
        InMemorySessions repository = new InMemorySessions();
        IdentityAuthenticationQuery identities = mock(IdentityAuthenticationQuery.class);
        CredentialAuthenticationService credentials = mock(CredentialAuthenticationService.class);
        AuthenticationSubject subject = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 2, 4);
        when(identities.eligibleSubject(TENANT, PRINCIPAL_ID)).thenReturn(Optional.of(subject));
        when(credentials.isStillValid(
                        eq(TENANT), eq(PRINCIPAL_ID), eq(CREDENTIAL_ID), eq(6L), any(Instant.class)))
                .thenReturn(true);
        IdGenerator ids = UUID::randomUUID;
        IdpBrowserSessionService service = new IdpBrowserSessionService(
                repository,
                identities,
                credentials,
                new IdpSessionTokenCodec(),
                ids,
                Duration.ofMinutes(30),
                Duration.ofHours(8));
        VerifiedCredential verified = new VerifiedCredential(PRINCIPAL_ID, CREDENTIAL_ID, 6);

        IdpBrowserSessionService.EstablishedSession first =
                service.establish(TENANT, subject, verified, NOW);
        IdpBrowserSessionService.EstablishedSession second =
                service.establish(TENANT, subject, verified, NOW);

        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(repository.byHash.values())
                .allSatisfy(session -> assertThat(session.tokenHash())
                        .doesNotContain(first.token())
                        .doesNotContain(second.token()));
        assertThat(service.resolve(TENANT, first.token(), NOW.plusSeconds(60))).isPresent();
    }

    @Test
    void establishmentRejectsCredentialProofForAnotherPrincipal() {
        InMemorySessions repository = new InMemorySessions();
        IdpBrowserSessionService service = new IdpBrowserSessionService(
                repository,
                mock(IdentityAuthenticationQuery.class),
                mock(CredentialAuthenticationService.class),
                new IdpSessionTokenCodec(),
                UUID::randomUUID,
                Duration.ofMinutes(30),
                Duration.ofHours(8));
        AuthenticationSubject subject = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 1, 1);
        VerifiedCredential wrongPrincipal =
                new VerifiedCredential(UUID.randomUUID(), CREDENTIAL_ID, 1);

        assertThatThrownBy(() -> service.establish(TENANT, subject, wrongPrincipal, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential proof does not belong to authentication subject");
        assertThat(repository.byHash).isEmpty();
    }

    @Test
    void expiredOrCredentialInvalidSessionFailsClosedAndIsRevoked() {
        InMemorySessions repository = new InMemorySessions();
        IdentityAuthenticationQuery identities = mock(IdentityAuthenticationQuery.class);
        CredentialAuthenticationService credentials = mock(CredentialAuthenticationService.class);
        AuthenticationSubject subject = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 1, 1);
        when(identities.eligibleSubject(TENANT, PRINCIPAL_ID)).thenReturn(Optional.of(subject));
        when(credentials.isStillValid(
                        eq(TENANT), eq(PRINCIPAL_ID), eq(CREDENTIAL_ID), eq(1L), any(Instant.class)))
                .thenReturn(false);
        IdpBrowserSessionService service = new IdpBrowserSessionService(
                repository,
                identities,
                credentials,
                new IdpSessionTokenCodec(),
                UUID::randomUUID,
                Duration.ofMinutes(5),
                Duration.ofMinutes(10));
        IdpBrowserSessionService.EstablishedSession established = service.establish(
                TENANT, subject, new VerifiedCredential(PRINCIPAL_ID, CREDENTIAL_ID, 1), NOW);

        assertThat(service.resolve(TENANT, established.token(), NOW.plusSeconds(1))).isEmpty();
        assertThat(repository.single().revokedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    private static final class InMemorySessions implements IdpBrowserSessionRepository {
        private final Map<String, IdpBrowserSession> byHash = new HashMap<>();

        @Override
        public void insert(TenantContext tenant, IdpBrowserSession session) {
            byHash.put(session.tokenHash(), session);
        }

        @Override
        public Optional<IdpBrowserSession> findByTokenHash(TenantContext tenant, String tokenHash) {
            return Optional.ofNullable(byHash.get(tokenHash));
        }

        @Override
        public boolean touch(
                TenantContext tenant,
                UUID sessionId,
                Instant lastSeenAt,
                Instant idleExpiresAt) {
            for (Map.Entry<String, IdpBrowserSession> entry : byHash.entrySet()) {
                IdpBrowserSession current = entry.getValue();
                if (!current.id().equals(sessionId) || current.revokedAt() != null) continue;
                entry.setValue(copy(current, lastSeenAt, idleExpiresAt, current.revokedAt()));
                return true;
            }
            return false;
        }

        @Override
        public boolean revoke(TenantContext tenant, UUID sessionId, Instant revokedAt) {
            for (Map.Entry<String, IdpBrowserSession> entry : byHash.entrySet()) {
                IdpBrowserSession current = entry.getValue();
                if (!current.id().equals(sessionId) || current.revokedAt() != null) continue;
                entry.setValue(copy(current, current.lastSeenAt(), current.idleExpiresAt(), revokedAt));
                return true;
            }
            return false;
        }

        private IdpBrowserSession single() {
            return byHash.values().iterator().next();
        }

        private static IdpBrowserSession copy(
                IdpBrowserSession current,
                Instant lastSeenAt,
                Instant idleExpiresAt,
                Instant revokedAt) {
            return new IdpBrowserSession(
                    current.id(), current.principalId(), current.identityId(),
                    current.credentialId(), current.credentialRevision(), current.tokenHash(),
                    current.createdAt(), lastSeenAt, idleExpiresAt,
                    current.absoluteExpiresAt(), revokedAt);
        }
    }
}
