package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.idp.runtime.IdpSecurityEvidence;
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
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
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
                activeTenants(),
                credentials,
                new IdpSessionTokenCodec(),
                ids,
                mock(IdpSecurityEvidence.class),
                Duration.ofMinutes(30),
                Duration.ofHours(8));
        VerifiedCredential verified = new VerifiedCredential(TENANT.tenantId(), PRINCIPAL_ID, CREDENTIAL_ID, 6);

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
                activeTenants(),
                mock(CredentialAuthenticationService.class),
                new IdpSessionTokenCodec(),
                UUID::randomUUID,
                mock(IdpSecurityEvidence.class),
                Duration.ofMinutes(30),
                Duration.ofHours(8));
        AuthenticationSubject subject = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 1, 1);
        VerifiedCredential wrongPrincipal =
                new VerifiedCredential(TENANT.tenantId(), UUID.randomUUID(), CREDENTIAL_ID, 1);

        assertThatThrownBy(() -> service.establish(TENANT, subject, wrongPrincipal, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential proof does not belong to authentication subject");
        assertThat(repository.byHash).isEmpty();
    }

    @Test
    void establishmentRejectsProofForAnotherTenantOrStaleCredential() {
        InMemorySessions repository = new InMemorySessions();
        IdentityAuthenticationQuery identities = mock(IdentityAuthenticationQuery.class);
        CredentialAuthenticationService credentials = mock(CredentialAuthenticationService.class);
        AuthenticationSubject subject = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 1, 1);
        when(identities.eligibleSubject(TENANT, PRINCIPAL_ID)).thenReturn(Optional.of(subject));
        IdpBrowserSessionService service = new IdpBrowserSessionService(repository, identities, activeTenants(), credentials,
                new IdpSessionTokenCodec(), UUID::randomUUID, mock(IdpSecurityEvidence.class));
        assertThatThrownBy(() -> service.establish(TENANT, subject,
                new VerifiedCredential(UUID.randomUUID(), PRINCIPAL_ID, CREDENTIAL_ID, 1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.establish(TENANT, subject,
                new VerifiedCredential(TENANT.tenantId(), PRINCIPAL_ID, CREDENTIAL_ID, 1), NOW))
                .isInstanceOf(IdpBrowserSessionService.InvalidCredentialProofException.class);
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
                .thenReturn(true, false);
        IdpBrowserSessionService service = new IdpBrowserSessionService(
                repository,
                identities,
                activeTenants(),
                credentials,
                new IdpSessionTokenCodec(),
                UUID::randomUUID,
                mock(IdpSecurityEvidence.class),
                Duration.ofMinutes(5),
                Duration.ofMinutes(10));
        IdpBrowserSessionService.EstablishedSession established = service.establish(
                TENANT, subject, new VerifiedCredential(TENANT.tenantId(), PRINCIPAL_ID, CREDENTIAL_ID, 1), NOW);

        assertThat(service.resolve(TENANT, established.token(), NOW.plusSeconds(1))).isEmpty();
        assertThat(repository.single().revokedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void codeSessionCheckDoesNotRefreshIdleAndRejectsLogoutOrForeignTenant() {
        var repository = new InMemorySessions();
        var identities = mock(IdentityAuthenticationQuery.class);
        var credentials = mock(CredentialAuthenticationService.class);
        var subject = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 1, 1);
        when(identities.eligibleSubject(TENANT, PRINCIPAL_ID)).thenReturn(Optional.of(subject));
        when(credentials.isStillValid(eq(TENANT), eq(PRINCIPAL_ID), eq(CREDENTIAL_ID), eq(1L), any(Instant.class))).thenReturn(true);
        var service = new IdpBrowserSessionService(repository, identities, activeTenants(), credentials, new IdpSessionTokenCodec(), UUID::randomUUID, mock(IdpSecurityEvidence.class));
        var established = service.establish(TENANT, subject, new VerifiedCredential(TENANT.tenantId(), PRINCIPAL_ID, CREDENTIAL_ID, 1), NOW);
        UUID id = established.context().sessionId();
        assertThat(service.revalidateAuthorizationCodeSession(TENANT, id, NOW.plusSeconds(60))).isPresent();
        assertThat(repository.single().lastSeenAt()).isEqualTo(NOW);
        assertThat(service.revalidateAuthorizationCodeSession(new TenantContext(UUID.randomUUID()), id, NOW.plusSeconds(60))).isEmpty();
        service.logout(TENANT, established.token(), NOW.plusSeconds(61));
        assertThat(service.revalidateAuthorizationCodeSession(TENANT, id, NOW.plusSeconds(62))).isEmpty();
    }

    @Test
    void subjectOrTenantRevisionChangeCannotResurrectSession() {
        var repository = new InMemorySessions();
        var identities = mock(IdentityAuthenticationQuery.class);
        var credentials = mock(CredentialAuthenticationService.class);
        var tenants = activeTenants();
        var subject = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 1, 1);
        when(identities.eligibleSubject(TENANT, PRINCIPAL_ID)).thenReturn(Optional.of(subject));
        when(credentials.isStillValid(eq(TENANT), eq(PRINCIPAL_ID), eq(CREDENTIAL_ID), eq(1L), any(Instant.class))).thenReturn(true);
        var service = new IdpBrowserSessionService(repository, identities, tenants, credentials, new IdpSessionTokenCodec(), UUID::randomUUID, mock(IdpSecurityEvidence.class));
        var proof = new VerifiedCredential(TENANT.tenantId(), PRINCIPAL_ID, CREDENTIAL_ID, 1);
        var first = service.establish(TENANT, subject, proof, NOW);
        // Even if reactivated before the next request, the old revision cannot authenticate.
        var changed = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 1, 3);
        when(identities.eligibleSubject(TENANT, PRINCIPAL_ID)).thenReturn(Optional.of(changed));
        assertThat(service.revalidateAuthorizationCodeSession(TENANT, first.context().sessionId(), NOW.plusSeconds(1))).isEmpty();
        var second = service.establish(TENANT, changed, proof, NOW.plusSeconds(2));
        when(tenants.find(TENANT.tenantId())).thenReturn(Optional.of(tenant(TenantLifecycleState.ACTIVE, 3)));
        assertThat(service.resolve(TENANT, second.token(), NOW.plusSeconds(3))).isEmpty();
        when(tenants.find(TENANT.tenantId())).thenReturn(Optional.of(tenant(TenantLifecycleState.SUSPENDED, 4)));
        assertThatThrownBy(() -> service.establish(TENANT, changed, proof, NOW.plusSeconds(4)))
                .isInstanceOf(IdpBrowserSessionService.InvalidCredentialProofException.class);
    }

    @Test
    void unavailableMandatoryEvidencePreventsSessionReleaseButNotRevocation() {
        var repository = new InMemorySessions();
        var identities = mock(IdentityAuthenticationQuery.class);
        var credentials = mock(CredentialAuthenticationService.class);
        var audit = mock(io.wyrmgate.iam.audit.application.SecurityAuditPort.class);
        var evidence = new IdpSecurityEvidence(audit, UUID::randomUUID);
        var subject = new AuthenticationSubject(PRINCIPAL_ID, IDENTITY_ID, 1, 1);
        when(identities.eligibleSubject(TENANT, PRINCIPAL_ID)).thenReturn(Optional.of(subject));
        when(credentials.isStillValid(eq(TENANT), eq(PRINCIPAL_ID), eq(CREDENTIAL_ID), eq(1L), any(Instant.class))).thenReturn(true);
        var service = new IdpBrowserSessionService(repository, identities, activeTenants(), credentials,
                new IdpSessionTokenCodec(), UUID::randomUUID, evidence);
        var proof = new VerifiedCredential(TENANT.tenantId(), PRINCIPAL_ID, CREDENTIAL_ID, 1);
        when(audit.append(eq(TENANT), any(io.wyrmgate.iam.audit.application.AuditRecordDraft.class)))
                .thenThrow(new IllegalStateException("Audit unavailable"));
        assertThatThrownBy(() -> service.establish(TENANT, subject, proof, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.single().revokedAt()).isEqualTo(NOW);
        org.mockito.Mockito.reset(audit);
        var established = service.establish(TENANT, subject, proof, NOW.plusSeconds(1));
        when(audit.append(eq(TENANT), any(io.wyrmgate.iam.audit.application.AuditRecordDraft.class)))
                .thenThrow(new IllegalStateException("Audit unavailable"));
        service.logout(TENANT, established.token(), NOW.plusSeconds(2));
        assertThat(service.resolve(TENANT, established.token(), NOW.plusSeconds(3))).isEmpty();
    }

    private static TenantOverviewQuery activeTenants() {
        var tenants = mock(TenantOverviewQuery.class);
        when(tenants.find(TENANT.tenantId())).thenReturn(Optional.of(tenant(TenantLifecycleState.ACTIVE, 1)));
        return tenants;
    }

    private static TenantOverviewQuery.TenantSummary tenant(TenantLifecycleState state, long revision) {
        return new TenantOverviewQuery.TenantSummary(TENANT.tenantId(), "test tenant", state, revision, NOW, NOW);
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
        public Optional<IdpBrowserSession> findById(TenantContext tenant, UUID sessionId) {
            return TENANT.equals(tenant) ? byHash.values().stream().filter(x -> x.id().equals(sessionId)).findFirst()
                    : Optional.empty();
        }

        @Override
        public boolean touch(
                TenantContext tenant,
                UUID sessionId,
                Instant lastSeenAt,
                Instant idleExpiresAt) {
            for (Map.Entry<String, IdpBrowserSession> entry : byHash.entrySet()) {
                IdpBrowserSession current = entry.getValue();
                if (!current.id().equals(sessionId) || !current.usableAt(lastSeenAt)) continue;
                entry.setValue(copy(current, current.lastSeenAt().isAfter(lastSeenAt) ? current.lastSeenAt() : lastSeenAt,
                        current.idleExpiresAt().isAfter(idleExpiresAt) ? current.idleExpiresAt() : idleExpiresAt, current.revokedAt()));
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
                    current.credentialId(), current.credentialRevision(), current.tenantRevision(),
                    current.principalRevision(), current.identityRevision(), current.tokenHash(),
                    current.createdAt(), lastSeenAt, idleExpiresAt,
                    current.absoluteExpiresAt(), revokedAt);
        }
    }
}
