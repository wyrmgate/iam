package io.wyrmgate.iam.idp.session;

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

    public IdpInteractiveLoginService(
            SsoClientProtocolQuery clients,
            IdentityAuthenticationQuery identities,
            CredentialAuthenticationService credentials,
            IdpBrowserSessionService sessions) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
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

            UUID targetId = clients.resolveAuthenticationTarget(client.get()).orElse(null);
            if (!expectedTarget.equals(targetId)) return Optional.empty();

            var subject = identities.resolveEligible(
                    client.get().tenant(), targetId, nativePrincipalKey.trim());
            if (subject.isEmpty()) return Optional.empty();

            var verified = credentials.verifyPassword(
                    client.get().tenant(), subject.get().principalId(), privatePassword, now);
            if (verified.isEmpty()) return Optional.empty();

            try {
                return Optional.of(sessions.establish(
                        client.get().tenant(), subject.get(), verified.get(), now));
            } catch (IdpBrowserSessionService.InvalidCredentialProofException stale) {
                return Optional.empty();
            }
        } finally {
            Arrays.fill(privatePassword, '\0');
        }
    }
}
