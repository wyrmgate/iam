package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Framework-neutral Access-owned semantic command used by Governance consumers. */
public interface AccessIntentCommand {

    Result applyRequestedAccess(
            TenantContext tenant,
            RequestedAccess request,
            Instant now);

    record RequestedAccess(
            UUID requestItemId,
            UUID identityId,
            TargetKind targetKind,
            UUID roleId,
            UUID entitlementId,
            PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil) {

        public RequestedAccess {
            if (requestItemId == null) {
                throw new IllegalArgumentException(
                        "requestItemId is required");
            }
            if (identityId == null) {
                throw new IllegalArgumentException(
                        "identityId is required");
            }
            if (targetKind == null) {
                throw new IllegalArgumentException(
                        "targetKind is required");
            }
            if (principalConstraintKind == null) {
                throw new IllegalArgumentException(
                        "principalConstraintKind is required");
            }
            if (targetKind == TargetKind.ROLE) {
                if (roleId == null || entitlementId != null) {
                    throw new IllegalArgumentException(
                            "ROLE requires roleId only");
                }
            } else if (entitlementId == null || roleId != null) {
                throw new IllegalArgumentException(
                        "ENTITLEMENT requires entitlementId only");
            }
            if (principalConstraintKind
                    == PrincipalConstraintKind.SPECIFIC) {
                if (specificPrincipalId == null) {
                    throw new IllegalArgumentException(
                            "SPECIFIC requires specificPrincipalId");
                }
            } else if (specificPrincipalId != null) {
                throw new IllegalArgumentException(
                        "ANY must not carry specificPrincipalId");
            }
            if (validFrom != null && validUntil != null
                    && !validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException(
                        "validUntil must be after validFrom");
            }
        }
    }

    record Result(UUID accessAssignmentId) {
        public Result {
            if (accessAssignmentId == null) {
                throw new IllegalArgumentException(
                        "accessAssignmentId is required");
            }
        }
    }

    enum TargetKind {
        ROLE,
        ENTITLEMENT
    }

    enum PrincipalConstraintKind {
        ANY,
        SPECIFIC
    }
}
