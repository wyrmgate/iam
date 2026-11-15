package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.idp.runtime.IdpAttemptLimiter;
import io.wyrmgate.iam.idp.runtime.IdpSecurityEvidence;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Composes Catalog tenant routing, Identity login eligibility and Credential verification for the
 * same-origin first-party sign-in interaction. It owns no canonical IAM state.
 */
public final class IdpInteractiveLoginService {

    private final SsoClientProtocolQuery clients;
    private final IdentityAuthenticationQuery identities;
    private final CredentialAuthenticationService credentials;
    private final IdpBrowserSessionService sessions;
    private final IdpAttemptLimiter limiter;
    private final IdpSecurityEvidence evidence;
    private final IdGenerator ids;

    public IdpInteractiveLoginService(
            SsoClientProtocolQuery clients,
            IdentityAuthenticationQuery identities,
            CredentialAuthenticationService credentials,
            IdpBrowserSessionService sessions, IdpAttemptLimiter limiter, IdpSecurityEvidence evidence, IdGenerator ids) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.limiter = Objects.requireNonNull(limiter);
        this.evidence = Objects.requireNonNull(evidence);
        this.ids = Objects.requireNonNull(ids);
    }

    public Optional<IdpBrowserSessionService.EstablishedSession> authenticate(
            String clientId,
            UUID expectedTarget,
            String nativePrincipalKey,
            char[] password,
            Instant now) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(now, "now");
        if (clientId == null || clientId.isBlank()
                || expectedTarget == null
                || nativePrincipalKey == null || nativePrincipalKey.isBlank()
                || clientId.length() > 200
                || nativePrincipalKey.length() > 512 || password.length == 0 || password.length > 1024) {
            return Optional.empty();
        }

        char[] privatePassword = Arrays.copyOf(password, password.length);
        try {
            var client = clients.resolveActive(clientId.trim());
            if (client.isEmpty()) return Optional.empty();

            var resolved = client.get();
            if (!limiter.acquire(resolved.tenant(), IdpAttemptLimiter.Kind.TENANT_LOGIN, resolved.tenant().tenantId())) {
                return Optional.empty();
            }
            UUID correlation = ids.nextId();
            UUID targetId = clients.resolveAuthenticationTarget(resolved).orElse(null);
            if (!expectedTarget.equals(targetId)) return denied(resolved, correlation, now);

            var subject = identities.resolveEligible(
                    client.get().tenant(), targetId, nativePrincipalKey.trim());
            if (subject.isEmpty()) return denied(resolved, correlation, now);
            if (!limiter.acquire(resolved.tenant(), IdpAttemptLimiter.Kind.PRINCIPAL_LOGIN, subject.get().principalId())) {
                return Optional.empty();
            }

            var verified = credentials.verifyPassword(
                    client.get().tenant(), subject.get().principalId(), privatePassword, now);
            if (verified.isEmpty()) return denied(resolved, correlation, now);

            try {
                return Optional.of(sessions.establish(
                        client.get().tenant(), subject.get(), verified.get(), now));
            } catch (IdpBrowserSessionService.InvalidCredentialProofException stale) {
                return denied(resolved, correlation, now);
            }
        } finally {
            Arrays.fill(privatePassword, '\0');
        }
    }

    private Optional<IdpBrowserSessionService.EstablishedSession> denied(SsoClientProtocolQuery.ResolvedClient client,
            UUID correlation, Instant now) {
        evidence.bestEffort(client.tenant(), null, "authentication:login", "sso-client", client.registration().id(),
                AuditOutcome.DENIED, correlation, null, now);
        return Optional.empty();
    }
}
