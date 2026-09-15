package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.administration.domain.ControlPlaneActorBinding;
import io.wyrmgate.iam.administration.domain.ControlPlaneActorBindingState;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Burn-once per-tenant provisioning of the first governed Administrative Authorization grant. */
public final class InitialAdminBootstrapService {

    public static final String INITIAL_ROLE_CODE = "tenant-initial-administrator";
    public static final String INITIAL_ROLE_NAME = "Tenant Initial Administrator";

    private final InitialAdminBootstrapRepository bootstrapRepository;
    private final ControlPlaneActorBindingRepository actorBindingRepository;
    private final GovernedActorStatusQuery governedActorStatusQuery;
    private final InitialAdminBootstrapFactSink factSink;
    private final IdGenerator idGenerator;
    private final TransactionExecutor transactionExecutor;

    public InitialAdminBootstrapService(
            InitialAdminBootstrapRepository bootstrapRepository,
            ControlPlaneActorBindingRepository actorBindingRepository,
            GovernedActorStatusQuery governedActorStatusQuery,
            InitialAdminBootstrapFactSink factSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        this.bootstrapRepository = Objects.requireNonNull(bootstrapRepository, "bootstrapRepository");
        this.actorBindingRepository = Objects.requireNonNull(actorBindingRepository, "actorBindingRepository");
        this.governedActorStatusQuery = Objects.requireNonNull(governedActorStatusQuery, "governedActorStatusQuery");
        this.factSink = Objects.requireNonNull(factSink, "factSink");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    public InitialAdminBootstrapResult bootstrap(
            TenantContext tenant,
            UUID actorIdentityId,
            ExternalAuthenticationSubject externalSubject,
            Instant now,
            UUID correlationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(externalSubject, "externalSubject");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");

        return transactionExecutor.required(() -> bootstrapInTransaction(
                tenant, actorIdentityId, externalSubject, now, correlationId));
    }

    private InitialAdminBootstrapResult bootstrapInTransaction(
            TenantContext tenant,
            UUID actorIdentityId,
            ExternalAuthenticationSubject externalSubject,
            Instant now,
            UUID correlationId) {
        if (!governedActorStatusQuery.isAdministrativelyEligible(tenant, actorIdentityId)) {
            throw new InitialAdminBootstrapNotAllowedException("actor_identity_not_active_in_tenant");
        }
        if (bootstrapRepository.hasAnyAdministrativeGrant(tenant)) {
            throw new InitialAdminBootstrapNotAllowedException("administrative_grant_already_exists");
        }

        UUID bootstrapId = idGenerator.nextId();
        UUID bindingId = idGenerator.nextId();
        UUID roleId = idGenerator.nextId();
        UUID grantId = idGenerator.nextId();
        InitialAdminBootstrap marker = new InitialAdminBootstrap(
                bootstrapId, actorIdentityId, bindingId, roleId, grantId, now, correlationId);
        if (!bootstrapRepository.claimBootstrap(tenant, marker)) {
            throw new InitialAdminAlreadyBootstrappedException(tenant.tenantId());
        }

        ControlPlaneActorBinding binding = new ControlPlaneActorBinding(
                bindingId,
                tenant.tenantId(),
                externalSubject,
                actorIdentityId,
                ControlPlaneActorBindingState.ACTIVE,
                1,
                now,
                now);
        if (!actorBindingRepository.insertIfExternalSubjectUnbound(tenant, binding)) {
            throw new InitialAdminBootstrapNotAllowedException("external_subject_already_bound");
        }

        Map<AdministrativePermission, java.util.UUID> permissionIds = new LinkedHashMap<>();
        for (AdministrativePermission permission : AdministrativePermissions.INITIAL_TENANT_ADMIN) {
            permissionIds.put(
                    permission,
                    bootstrapRepository.ensurePermission(
                            tenant, idGenerator.nextId(), permission, now));
        }

        AdministrativeRole role = new AdministrativeRole(
                roleId,
                INITIAL_ROLE_CODE,
                INITIAL_ROLE_NAME,
                AdministrativePermissions.INITIAL_TENANT_ADMIN,
                1,
                now,
                now);
        bootstrapRepository.insertRole(tenant, role);
        bootstrapRepository.attachPermissions(tenant, roleId, permissionIds.values(), now);

        AdministrativeGrant grant = new AdministrativeGrant(
                grantId,
                actorIdentityId,
                roleId,
                new AdministrativeScope(AdministrativeScopeType.GLOBAL, null, null),
                AdministrativeGrantState.ACTIVE,
                now,
                null,
                1,
                now,
                now);
        bootstrapRepository.insertGrant(tenant, grant);
        factSink.bootstrapped(tenant, marker);

        return new InitialAdminBootstrapResult(
                bootstrapId, bindingId, roleId, grantId, correlationId);
    }
}
