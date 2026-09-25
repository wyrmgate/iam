package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Access-owned derivation of provider-neutral desired principal/grant state. */
public final class DesiredStateDerivationService {

    private final EffectiveAccessQuery effectiveAccess;
    private final DesiredStateProjectionRepository desired;
    private final CatalogAccessReferenceQuery catalog;
    private final IdentityAccessReferenceQuery identities;

    public DesiredStateDerivationService(
            EffectiveAccessQuery effectiveAccess,
            DesiredStateProjectionRepository desired,
            CatalogAccessReferenceQuery catalog,
            IdentityAccessReferenceQuery identities) {
        this.effectiveAccess = Objects.requireNonNull(effectiveAccess, "effectiveAccess");
        this.desired = Objects.requireNonNull(desired, "desired");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    public void reconcileGrant(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(entitlementId, "entitlementId");
        if (principalConstraintKey == null || principalConstraintKey.isBlank()) {
            throw new IllegalArgumentException("principalConstraintKey must not be blank");
        }
        Objects.requireNonNull(at, "at");

        var entitlement = catalog.resolveActiveEntitlement(tenant, entitlementId);
        if (entitlement.status() != CatalogAccessReferenceQuery.Status.VALID) {
            desired.findGrantTuple(
                            tenant, identityId, entitlementId, principalConstraintKey)
                    .ifPresent(existing -> {
                        desired.reconcileGrant(
                                tenant,
                                identityId,
                                existing.applicationTargetId(),
                                entitlementId,
                                principalConstraintKey,
                                null,
                                DesiredStateProjectionRepository.DesiredPresence.ABSENT,
                                at);
                        desired.reconcilePrincipal(
                                tenant,
                                identityId,
                                existing.applicationTargetId(),
                                desired.hasPresentGrant(
                                                tenant,
                                                identityId,
                                                existing.applicationTargetId())
                                        ? DesiredStateProjectionRepository.DesiredPresence.PRESENT
                                        : DesiredStateProjectionRepository.DesiredPresence.ABSENT,
                                at);
                    });
            return;
        }

        var effective = effectiveAccess.find(
                tenant, identityId, entitlementId, principalConstraintKey, at);
        boolean present = effective.isPresent();

        UUID principalId = null;
        if (present) {
            if (principalConstraintKey.startsWith("SPECIFIC:")) {
                principalId = parseSpecificPrincipal(principalConstraintKey);
            } else if ("ANY".equals(principalConstraintKey)) {
                var selection = identities.selectUniqueActivePrincipal(
                        tenant, identityId, entitlement.applicationTargetId());
                if (selection.status()
                        == IdentityAccessReferenceQuery.PrincipalSelectionStatus.RESOLVED) {
                    principalId = selection.principalId();
                }
            } else {
                throw new IllegalArgumentException(
                        "unsupported principal constraint key");
            }
        }

        desired.reconcileGrant(
                tenant,
                identityId,
                entitlement.applicationTargetId(),
                entitlementId,
                principalConstraintKey,
                principalId,
                present
                        ? DesiredStateProjectionRepository.DesiredPresence.PRESENT
                        : DesiredStateProjectionRepository.DesiredPresence.ABSENT,
                at);

        desired.reconcilePrincipal(
                tenant,
                identityId,
                entitlement.applicationTargetId(),
                desired.hasPresentGrant(
                        tenant, identityId, entitlement.applicationTargetId())
                        ? DesiredStateProjectionRepository.DesiredPresence.PRESENT
                        : DesiredStateProjectionRepository.DesiredPresence.ABSENT,
                at);
    }

    public void reconcileAnyForPrincipalChange(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId,
            Instant at) {
        for (var grant : desired.findPresentAnyGrants(
                tenant, identityId, applicationTargetId)) {
            reconcileGrant(
                    tenant,
                    grant.identityId(),
                    grant.entitlementId(),
                    grant.principalConstraintKey(),
                    at);
        }
    }

    private static UUID parseSpecificPrincipal(String key) {
        try {
            return UUID.fromString(key.substring("SPECIFIC:".length()));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "invalid SPECIFIC principal constraint key", invalid);
        }
    }
}
