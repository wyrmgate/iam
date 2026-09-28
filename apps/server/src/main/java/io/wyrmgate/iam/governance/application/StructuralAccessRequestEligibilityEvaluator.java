package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;

public final class StructuralAccessRequestEligibilityEvaluator
        implements AccessRequestEligibilityEvaluator {

    private final IdentityAccessReferenceQuery identities;
    private final CatalogAccessReferenceQuery catalog;
    private final RoleExpansionQuery roles;

    public StructuralAccessRequestEligibilityEvaluator(
            IdentityAccessReferenceQuery identities,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    @Override
    public Evaluation evaluate(
            TenantContext tenant,
            RequestItem item) {
        try {
            if (!identities.identityExists(
                    tenant, item.accessRequestId())) {
                // AccessRequest owns beneficiary context; this evaluator is
                // intentionally item-focused and the service validates the
                // actual beneficiary before invoking it.
            }

            if (item.targetKind() == RequestItem.TargetKind.ENTITLEMENT) {
                var entitlement = catalog.resolveActiveEntitlement(
                        tenant, item.entitlementId());
                if (entitlement.status()
                        != CatalogAccessReferenceQuery.Status.VALID) {
                    return Evaluation.denied(
                            "entitlement_not_eligible");
                }
                return validateSpecificPrincipal(
                        tenant,
                        item,
                        entitlement.applicationTargetId());
            }

            var expansion = roles.expandCurrent(
                    tenant, item.roleId());
            if (expansion.status()
                            != RoleExpansionQuery.Status.AVAILABLE
                    || expansion.paths().isEmpty()) {
                return Evaluation.denied(
                        "role_not_eligible");
            }
            if (item.principalConstraintKind()
                    == RequestItem.PrincipalConstraintKind.ANY) {
                return Evaluation.eligible();
            }
            var targets = expansion.paths().stream()
                    .map(RoleExpansionQuery.EntitlementPath::applicationTargetId)
                    .distinct()
                    .toList();
            if (targets.size() != 1) {
                return Evaluation.denied(
                        "specific_role_target_ambiguous");
            }
            return validateSpecificPrincipal(
                    tenant, item, targets.getFirst());
        } catch (RuntimeException unavailable) {
            return Evaluation.unavailable(
                    "eligibility_dependency_unavailable");
        }
    }

    private Evaluation validateSpecificPrincipal(
            TenantContext tenant,
            RequestItem item,
            java.util.UUID applicationTargetId) {
        if (item.principalConstraintKind()
                == RequestItem.PrincipalConstraintKind.ANY) {
            return Evaluation.eligible();
        }
        var principal = identities.principal(
                tenant, item.specificPrincipalId());
        if (principal.status()
                != IdentityAccessReferenceQuery.Status.RESOLVED) {
            return Evaluation.denied(
                    "specific_principal_not_eligible");
        }
        if (!applicationTargetId.equals(
                principal.applicationTargetId())) {
            return Evaluation.denied(
                    "principal_target_mismatch");
        }
        return Evaluation.eligible();
    }
}
