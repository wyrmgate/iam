package io.wyrmgate.iam.credential.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialKind;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialState;
import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialAuthenticationServiceTest {

    private static final TenantContext TENANT = new TenantContext(UUID.randomUUID());
    private static final UUID PRINCIPAL_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-06T02:00:00Z");

    @Test
    void verifiesOnlyThroughTheOwningSecretAdapterAndDoesNotMutateCallerSecret() {
        CredentialRepository repository = mock(CredentialRepository.class);
        Credential credential = passwordCredential("vault", 3);
        when(repository.findEffectiveCredentials(
                        TENANT, PRINCIPAL_ID, CredentialKind.PASSWORD, NOW))
                .thenReturn(List.of(credential));
        CredentialSecretVerifier verifier = new CredentialSecretVerifier() {
            @Override
            public boolean supports(String providerType) {
                return "vault".equals(providerType);
            }

            @Override
            public boolean matches(SecretReference reference, char[] presentedSecret) {
                return Arrays.equals(presentedSecret, "correct horse".toCharArray());
            }
        };
        CredentialAuthenticationService service =
                new CredentialAuthenticationService(repository, List.of(verifier));
        char[] presented = "correct horse".toCharArray();

        assertThat(service.verifyPassword(TENANT, PRINCIPAL_ID, presented, NOW))
                .contains(new CredentialAuthenticationService.VerifiedCredential(
                        PRINCIPAL_ID, credential.id(), 3));
        assertThat(presented).containsExactly("correct horse".toCharArray());
    }

    @Test
    void missingOrAmbiguousVerifierFailsClosed() {
        CredentialRepository repository = mock(CredentialRepository.class);
        Credential credential = passwordCredential("vault", 1);
        when(repository.findEffectiveCredentials(
                        TENANT, PRINCIPAL_ID, CredentialKind.PASSWORD, NOW))
                .thenReturn(List.of(credential));
        CredentialSecretVerifier one = matchingVerifier("vault");
        CredentialSecretVerifier two = matchingVerifier("vault");

        assertThat(new CredentialAuthenticationService(repository, List.of())
                        .verifyPassword(TENANT, PRINCIPAL_ID, "secret".toCharArray(), NOW))
                .isEmpty();
        assertThat(new CredentialAuthenticationService(repository, List.of(one, two))
                        .verifyPassword(TENANT, PRINCIPAL_ID, "secret".toCharArray(), NOW))
                .isEmpty();
    }

    @Test
    void sessionRevalidationRejectsCredentialRevisionOrLifecycleChange() {
        CredentialRepository repository = mock(CredentialRepository.class);
        Credential active = passwordCredential("vault", 4);
        when(repository.findCredential(TENANT, active.id())).thenReturn(java.util.Optional.of(active));
        CredentialAuthenticationService service =
                new CredentialAuthenticationService(repository, List.of());

        assertThat(service.isStillValid(
                        TENANT, PRINCIPAL_ID, active.id(), 4, NOW))
                .isTrue();
        assertThat(service.isStillValid(
                        TENANT, PRINCIPAL_ID, active.id(), 3, NOW))
                .isFalse();
    }

    private static CredentialSecretVerifier matchingVerifier(String provider) {
        return new CredentialSecretVerifier() {
            @Override
            public boolean supports(String providerType) {
                return provider.equals(providerType);
            }

            @Override
            public boolean matches(SecretReference reference, char[] presentedSecret) {
                return true;
            }
        };
    }

    private static Credential passwordCredential(String provider, long revision) {
        return new Credential(
                UUID.randomUUID(),
                PRINCIPAL_ID,
                CredentialKind.PASSWORD,
                new SecretReference(provider, "secret/password/alice"),
                CredentialState.ACTIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                revision,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(60),
                null,
                null,
                null);
    }
}
