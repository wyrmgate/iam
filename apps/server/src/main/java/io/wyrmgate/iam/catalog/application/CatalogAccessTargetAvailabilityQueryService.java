package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetAvailability;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Current-state ADR-0052 availability decisions over Catalog authority. */
public final class CatalogAccessTargetAvailabilityQueryService
        implements CatalogAccessTargetAvailabilityQuery {

    private final CatalogRepository catalog;
    private final RoleRepository roles;
    private final RoleExpansionQuery expansion;

    public CatalogAccessTargetAvailabilityQueryService(
            CatalogRepository catalog,
            RoleRepository roles,
            RoleExpansionQuery expansion) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.expansion = Objects.requireNonNull(expansion, "expansion");
    }

    @Override
    public Optional<CatalogAccessTargetAvailability> currentAvailability(
            TenantContext tenant,
            CatalogAccessTargetKind targetKind,
            UUID targetId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(targetId, "targetId");
        return switch (targetKind) {
            case ENTITLEMENT -> entitlementAvailability(tenant, targetId);
            case ROLE -> roleAvailability(tenant, targetId);
        };
    }

    private Optional<CatalogAccessTargetAvailability> entitlementAvailability(
            TenantContext tenant,
            UUID entitlementId) {
        var entitlement = catalog.findEntitlement(tenant, entitlementId).orElse(null);
        if (entitlement == null) {
            return Optional.empty();
        }
        if (entitlement.lifecycleState() == CatalogLifecycleState.RETIRED) {
            return Optional.of(decision(
                    CatalogAccessTargetKind.ENTITLEMENT,
                    entitlement.id(),
                    CatalogAccessTargetAvailability.Status.TERMINALLY_UNAVAILABLE,
                    CatalogAvailabilityCauseKind.ENTITLEMENT,
                    entitlement.id(),
                    entitlement.revision()));
        }

        var application = catalog.findApplication(
                tenant, entitlement.applicationId()).orElse(null);
        if (application == null) {
            return Optional.of(decision(
                    CatalogAccessTargetKind.ENTITLEMENT,
                    entitlement.id(),
                    CatalogAccessTargetAvailability.Status.TEMPORARILY_UNAVAILABLE,
                    CatalogAvailabilityCauseKind.ENTITLEMENT,
                    entitlement.id(),
                    entitlement.revision()));
        }
        if (application.lifecycleState() == CatalogLifecycleState.RETIRED) {
            return Optional.of(decision(
                    CatalogAccessTargetKind.ENTITLEMENT,
                    entitlement.id(),
                    CatalogAccessTargetAvailability.Status.TERMINALLY_UNAVAILABLE,
                    CatalogAvailabilityCauseKind.APPLICATION,
                    application.id(),
                    application.revision()));
        }

        if (entitlement.applicationTargetId() == null) {
            return Optional.of(decision(
                    CatalogAccessTargetKind.ENTITLEMENT,
                    entitlement.id(),
                    CatalogAccessTargetAvailability.Status.TEMPORARILY_UNAVAILABLE,
                    CatalogAvailabilityCauseKind.ENTITLEMENT,
                    entitlement.id(),
                    entitlement.revision()));
        }

        var target = catalog.findTarget(
                tenant, entitlement.applicationTargetId()).orElse(null);
        if (target == null) {
            return Optional.of(decision(
                    CatalogAccessTargetKind.ENTITLEMENT,
                    entitlement.id(),
                    CatalogAccessTargetAvailability.Status.TEMPORARILY_UNAVAILABLE,
                    CatalogAvailabilityCauseKind.ENTITLEMENT,
                    entitlement.id(),
                    entitlement.revision()));
        }
        if (target.lifecycleState() == CatalogLifecycleState.RETIRED) {
            return Optional.of(decision(
                    CatalogAccessTargetKind.ENTITLEMENT,
                    entitlement.id(),
                    CatalogAccessTargetAvailability.Status.TERMINALLY_UNAVAILABLE,
                    CatalogAvailabilityCauseKind.APPLICATION_TARGET,
                    target.id(),
                    target.revision()));
        }

        return Optional.of(decision(
                CatalogAccessTargetKind.ENTITLEMENT,
                entitlement.id(),
                CatalogAccessTargetAvailability.Status.ASSIGNABLE,
                CatalogAvailabilityCauseKind.ENTITLEMENT,
                entitlement.id(),
                entitlement.revision()));
    }

    private Optional<CatalogAccessTargetAvailability> roleAvailability(
            TenantContext tenant,
            UUID roleId) {
        Role role = roles.findRole(tenant, roleId).orElse(null);
        if (role == null) {
            return Optional.empty();
        }
        if (role.lifecycleState() == CatalogLifecycleState.RETIRED) {
            return Optional.of(decision(
                    CatalogAccessTargetKind.ROLE,
                    role.id(),
                    CatalogAccessTargetAvailability.Status.TERMINALLY_UNAVAILABLE,
                    CatalogAvailabilityCauseKind.ROLE,
                    role.id(),
                    role.revision()));
        }

        if (role.type() == Role.RoleType.APPLICATION) {
            var application = catalog.findApplication(
                    tenant, role.applicationId()).orElse(null);
            if (application == null) {
                return Optional.of(decision(
                        CatalogAccessTargetKind.ROLE,
                        role.id(),
                        CatalogAccessTargetAvailability.Status.TEMPORARILY_UNAVAILABLE,
                        CatalogAvailabilityCauseKind.ROLE,
                        role.id(),
                        role.revision()));
            }
            if (application.lifecycleState() == CatalogLifecycleState.RETIRED) {
                return Optional.of(decision(
                        CatalogAccessTargetKind.ROLE,
                        role.id(),
                        CatalogAccessTargetAvailability.Status.TERMINALLY_UNAVAILABLE,
                        CatalogAvailabilityCauseKind.APPLICATION,
                        application.id(),
                        application.revision()));
            }
        }

        var result = expansion.expandCurrent(tenant, role.id());
        CatalogAccessTargetAvailability.Status status =
                result.status() == RoleExpansionQuery.Status.AVAILABLE
                        && !result.paths().isEmpty()
                        ? CatalogAccessTargetAvailability.Status.ASSIGNABLE
                        : CatalogAccessTargetAvailability.Status.TEMPORARILY_UNAVAILABLE;
        return Optional.of(decision(
                CatalogAccessTargetKind.ROLE,
                role.id(),
                status,
                CatalogAvailabilityCauseKind.ROLE,
                role.id(),
                role.revision()));
    }

    private static CatalogAccessTargetAvailability decision(
            CatalogAccessTargetKind targetKind,
            UUID targetId,
            CatalogAccessTargetAvailability.Status status,
            CatalogAvailabilityCauseKind causeKind,
            UUID causeId,
            long causeRevision) {
        return new CatalogAccessTargetAvailability(
                targetKind, targetId, status, causeKind, causeId, causeRevision);
    }
}
