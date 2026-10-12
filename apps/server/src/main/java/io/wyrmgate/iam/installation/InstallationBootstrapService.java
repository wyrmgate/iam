package io.wyrmgate.iam.installation;

import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.catalog.application.SsoClientRegistrationService;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.credential.application.PasswordCredentialEnrollmentService;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.platform.tenant.TenantProvisioningService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** ADR-0044 deployment-scoped, forward-recovery fresh-install orchestrator. */
public final class InstallationBootstrapService {

    public static final int BOOTSTRAP_VERSION = 1;
    public static final String CONSOLE_APPLICATION_CODE = "wyrmgate-console";
    public static final String CONSOLE_APPLICATION_NAME = "Wyrmgate Console";
    public static final String CONSOLE_TARGET_CODE = "local";

    private final InstallationBootstrapRepository repository;
    private final TenantProvisioningService tenants;
    private final IdentityCommandService identities;
    private final CatalogCommandService catalog;
    private final SsoClientRegistrationService ssoClients;
    private final PrincipalCommandService principals;
    private final PasswordCredentialEnrollmentService passwords;
    private final InitialAdminBootstrapService administration;
    private final IdentityAuthenticationQuery authenticationSubjects;
    private final CredentialAuthenticationService credentialAuthentication;
    private final SsoClientProtocolQuery protocolClients;
    private final IdGenerator ids;

    public InstallationBootstrapService(
            InstallationBootstrapRepository repository,
            TenantProvisioningService tenants,
            IdentityCommandService identities,
            CatalogCommandService catalog,
            SsoClientRegistrationService ssoClients,
            PrincipalCommandService principals,
            PasswordCredentialEnrollmentService passwords,
            InitialAdminBootstrapService administration,
            IdentityAuthenticationQuery authenticationSubjects,
            CredentialAuthenticationService credentialAuthentication,
            SsoClientProtocolQuery protocolClients,
            IdGenerator ids) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.ssoClients = Objects.requireNonNull(ssoClients, "ssoClients");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.authenticationSubjects = Objects.requireNonNull(authenticationSubjects, "authenticationSubjects");
        this.credentialAuthentication = Objects.requireNonNull(credentialAuthentication, "credentialAuthentication");
        this.protocolClients = Objects.requireNonNull(protocolClients, "protocolClients");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public boolean isSealed() {
        return repository.find()
                .map(plan -> plan.state() == InstallationBootstrapState.SEALED)
                .orElse(false);
    }

    /**
     * Runs or resumes the one deployment bootstrap. The supplied password is never persisted by
     * this process and is copied only for the duration of the invocation.
     */
    public Result run(
            Input input,
            char[] password,
            Instant now) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(now, "now");

        InstallationBootstrapPlan plan = repository.find().orElse(null);
        if (plan != null && plan.state() == InstallationBootstrapState.SEALED) {
            requireSameInput(plan, input);
            return result(plan);
        }
        Objects.requireNonNull(password, "password");
        if (password.length == 0) {
            throw new IllegalArgumentException("bootstrap password must not be empty");
        }

        char[] privatePassword = Arrays.copyOf(password, password.length);
        try {
            if (plan == null) {
                InstallationBootstrapPlan proposed = newPlan(input, now);
                if (!repository.createPlan(proposed)) {
                    plan = repository.find().orElseThrow();
                } else {
                    plan = proposed;
                }
            }
            requireSameInput(plan, input);

            while (plan.state() != InstallationBootstrapState.SEALED) {
                plan = executeCurrentStep(plan, privatePassword, now);
            }
            return result(plan);
        } finally {
            Arrays.fill(privatePassword, '\0');
        }
    }

    private InstallationBootstrapPlan executeCurrentStep(
            InstallationBootstrapPlan plan,
            char[] password,
            Instant now) {
        TenantContext tenant = new TenantContext(plan.tenantId());
        switch (plan.state()) {
            case PLANNED -> {
                tenants.ensureFirstTenant(plan.tenantId(), plan.tenantDisplayName(), now);
                return advance(plan, InstallationBootstrapState.TENANT_CREATED, now);
            }
            case TENANT_CREATED -> {
                identities.ensureInstallationPerson(
                        tenant,
                        plan.identityId(),
                        plan.identityDisplayName(),
                        now,
                        plan.correlationId(),
                        plan.correlationId());
                return advance(plan, InstallationBootstrapState.IDENTITY_CREATED, now);
            }
            case IDENTITY_CREATED -> {
                catalog.ensureInstallationApplication(
                        tenant,
                        plan.consoleApplicationId(),
                        CONSOLE_APPLICATION_CODE,
                        CONSOLE_APPLICATION_NAME,
                        now);
                catalog.ensureInstallationTarget(
                        tenant,
                        plan.consoleTargetId(),
                        plan.consoleApplicationId(),
                        CONSOLE_TARGET_CODE,
                        now);
                ssoClients.ensureInstallationClient(
                        tenant,
                        plan.consoleSsoRegistrationId(),
                        plan.consoleClientId(),
                        plan.consoleApplicationId(),
                        Set.of(plan.consoleRedirectUri()),
                        Set.of(SsoClientScope.OPENID, SsoClientScope.PROFILE, SsoClientScope.EMAIL),
                        false,
                        now);
                return advance(plan, InstallationBootstrapState.CONSOLE_REGISTERED, now);
            }
            case CONSOLE_REGISTERED -> {
                principals.ensureInstallationPrincipal(
                        tenant,
                        plan.principalId(),
                        plan.consoleTargetId(),
                        plan.principalKey(),
                        plan.identityId(),
                        now,
                        plan.correlationId(),
                        plan.correlationId());
                return advance(plan, InstallationBootstrapState.PRINCIPAL_CREATED, now);
            }
            case PRINCIPAL_CREATED -> {
                passwords.ensureInstallationPassword(
                        tenant,
                        plan.credentialId(),
                        plan.principalId(),
                        password,
                        now);
                return advance(plan, InstallationBootstrapState.CREDENTIAL_ENROLLED, now);
            }
            case CREDENTIAL_ENROLLED -> {
                administration.bootstrapFirstParty(
                        tenant,
                        plan.identityId(),
                        plan.principalId(),
                        now,
                        plan.correlationId());
                return advance(plan, InstallationBootstrapState.ADMIN_BOOTSTRAPPED, now);
            }
            case ADMIN_BOOTSTRAPPED -> {
                verify(plan, tenant, password, now);
                return advance(plan, InstallationBootstrapState.VERIFIED, now);
            }
            case VERIFIED -> {
                return advance(plan, InstallationBootstrapState.SEALED, now);
            }
            case SEALED -> {
                return plan;
            }
        }
        throw new IllegalStateException("unsupported installation bootstrap state");
    }

    private void verify(
            InstallationBootstrapPlan plan,
            TenantContext tenant,
            char[] password,
            Instant now) {
        if (!tenants.matches(plan.tenantId(), plan.tenantDisplayName())) {
            throw new IllegalStateException("installation verification failed: tenant");
        }
        var subject = authenticationSubjects.resolveEligible(
                        tenant,
                        plan.consoleTargetId(),
                        plan.principalKey())
                .orElseThrow(() -> new IllegalStateException(
                        "installation verification failed: principal_identity"));
        if (!subject.principalId().equals(plan.principalId())
                || !subject.identityId().equals(plan.identityId())) {
            throw new IllegalStateException(
                    "installation verification failed: principal_identity_mismatch");
        }
        var verifiedCredential = credentialAuthentication.verifyPassword(
                        tenant,
                        plan.principalId(),
                        password,
                        now)
                .orElseThrow(() -> new IllegalStateException(
                        "installation verification failed: password_credential"));
        if (!verifiedCredential.credentialId().equals(plan.credentialId())) {
            throw new IllegalStateException(
                    "installation verification failed: credential_mismatch");
        }
        var resolvedClient = protocolClients.resolveActive(plan.consoleClientId())
                .orElseThrow(() -> new IllegalStateException(
                        "installation verification failed: console_sso_client"));
        if (!resolvedClient.tenant().equals(tenant)
                || !resolvedClient.registration().id().equals(plan.consoleSsoRegistrationId())
                || !resolvedClient.registration().applicationId().equals(plan.consoleApplicationId())) {
            throw new IllegalStateException(
                    "installation verification failed: console_sso_client_mismatch");
        }
        if (!administration.isEffectiveFirstPartyBootstrap(
                tenant,
                plan.identityId(),
                plan.principalId(),
                now)) {
            throw new IllegalStateException(
                    "installation verification failed: administration");
        }
    }

    private InstallationBootstrapPlan advance(
            InstallationBootstrapPlan current,
            InstallationBootstrapState next,
            Instant now) {
        if (repository.advance(current.state(), next, now)) {
            return current.withState(next, now);
        }
        InstallationBootstrapPlan persisted = repository.find().orElseThrow();
        if (persisted.state().ordinal() >= next.ordinal()) {
            return persisted;
        }
        throw new IllegalStateException("installation bootstrap checkpoint conflict");
    }

    private InstallationBootstrapPlan newPlan(Input input, Instant now) {
        UUID registrationId = ids.nextId();
        return new InstallationBootstrapPlan(
                InstallationBootstrapState.PLANNED,
                ids.nextId(),
                input.tenantDisplayName(),
                ids.nextId(),
                input.identityDisplayName(),
                ids.nextId(),
                ids.nextId(),
                registrationId,
                "wc_" + registrationId.toString().replace("-", ""),
                input.consoleRedirectUri(),
                ids.nextId(),
                input.principalKey(),
                ids.nextId(),
                ids.nextId(),
                BOOTSTRAP_VERSION,
                now,
                now,
                null);
    }

    private static void requireSameInput(
            InstallationBootstrapPlan plan,
            Input input) {
        if (!plan.tenantDisplayName().equals(input.tenantDisplayName().trim())
                || !plan.identityDisplayName().equals(input.identityDisplayName().trim())
                || !plan.principalKey().equals(input.principalKey().trim())
                || !plan.consoleRedirectUri().equals(input.consoleRedirectUri().trim())) {
            throw new IllegalStateException(
                    "installation bootstrap input differs from the durable bootstrap plan");
        }
    }

    private static Result result(InstallationBootstrapPlan plan) {
        return new Result(
                plan.state(),
                plan.tenantId(),
                plan.identityId(),
                plan.principalId(),
                plan.credentialId(),
                plan.consoleApplicationId(),
                plan.consoleTargetId(),
                plan.consoleSsoRegistrationId(),
                plan.consoleClientId(),
                plan.sealedAt());
    }

    public record Input(
            String tenantDisplayName,
            String identityDisplayName,
            String principalKey,
            String consoleRedirectUri) {
        public Input {
            tenantDisplayName = requireText(tenantDisplayName, "tenantDisplayName");
            identityDisplayName = requireText(identityDisplayName, "identityDisplayName");
            principalKey = requireText(principalKey, "principalKey");
            consoleRedirectUri = requireText(consoleRedirectUri, "consoleRedirectUri");
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }
    }

    public record Result(
            InstallationBootstrapState state,
            UUID tenantId,
            UUID identityId,
            UUID principalId,
            UUID credentialId,
            UUID consoleApplicationId,
            UUID consoleTargetId,
            UUID consoleSsoRegistrationId,
            String consoleClientId,
            Instant sealedAt) {
    }
}
