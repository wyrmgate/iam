package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;

public final class AccessIntentCommandService
        implements AccessIntentCommand {

    private final AccessAssignmentRepository assignments;
    private final AccessAssignmentCommandService commands;

    public AccessIntentCommandService(
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands) {
        this.assignments = Objects.requireNonNull(
                assignments, "assignments");
        this.commands = Objects.requireNonNull(
                commands, "commands");
    }

    @Override
    public Result applyRequestedAccess(
            TenantContext tenant,
            RequestedAccess request,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");

        var existing = assignments.findByProvenance(
                tenant,
                AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                request.requestItemId());
        if (existing.isPresent()) {
            requireSameIntent(existing.get(), request);
            return new Result(existing.get().id());
        }

        try {
            AccessAssignment created = switch (request.targetKind()) {
                case ENTITLEMENT ->
                        commands.createRequestedEntitlementAssignment(
                                tenant,
                                request.requestItemId(),
                                request.identityId(),
                                request.entitlementId(),
                                principalConstraint(
                                        request.principalConstraintKind()),
                                request.specificPrincipalId(),
                                request.validFrom(),
                                request.validUntil(),
                                now);
                case ROLE ->
                        commands.createRequestedRoleAssignment(
                                tenant,
                                request.requestItemId(),
                                request.identityId(),
                                request.roleId(),
                                principalConstraint(
                                        request.principalConstraintKind()),
                                request.specificPrincipalId(),
                                request.validFrom(),
                                request.validUntil(),
                                now);
            };
            return new Result(created.id());
        } catch (AccessAssignmentProvenanceConflictException race) {
            AccessAssignment replay = assignments.findByProvenance(
                            tenant,
                            AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                            request.requestItemId())
                    .orElseThrow(() -> race);
            requireSameIntent(replay, request);
            return new Result(replay.id());
        }
    }

    private static AccessAssignment.PrincipalConstraintKind
            principalConstraint(
                    PrincipalConstraintKind constraint) {
        return constraint == PrincipalConstraintKind.ANY
                ? AccessAssignment.PrincipalConstraintKind.ANY
                : AccessAssignment.PrincipalConstraintKind.SPECIFIC;
    }

    private static void requireSameIntent(
            AccessAssignment existing,
            RequestedAccess request) {
        boolean sameTarget =
                existing.targetKind().name()
                                .equals(request.targetKind().name())
                        && Objects.equals(
                                existing.roleId(),
                                request.roleId())
                        && Objects.equals(
                                existing.entitlementId(),
                                request.entitlementId());
        if (!existing.identityId().equals(request.identityId())
                || !sameTarget
                || !existing.principalConstraintKind().name()
                        .equals(request.principalConstraintKind().name())
                || !Objects.equals(
                        existing.specificPrincipalId(),
                        request.specificPrincipalId())
                || !Objects.equals(
                        existing.validFrom(),
                        request.validFrom())
                || !Objects.equals(
                        existing.validUntil(),
                        request.validUntil())) {
            throw new AccessAssignmentCommandException(
                    "request_item_access_intent_conflict",
                    "The RequestItem already produced a different AccessAssignment.");
        }
    }
}
