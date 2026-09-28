package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;

public final class AccessIntentCommandService
        implements AccessIntentCommand {

    private final AccessAssignmentRepository assignments;
    private final AccessAssignmentCommandService commands;

    public AccessIntentCommandService(
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public AccessAssignment applyRequestedAccess(
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
            return existing.get();
        }

        try {
            return switch (request.targetKind()) {
                case ENTITLEMENT ->
                        commands.createRequestedEntitlementAssignment(
                                tenant,
                                request.requestItemId(),
                                request.identityId(),
                                request.entitlementId(),
                                request.principalConstraintKind(),
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
                                request.principalConstraintKind(),
                                request.specificPrincipalId(),
                                request.validFrom(),
                                request.validUntil(),
                                now);
            };
        } catch (DataIntegrityViolationException race) {
            AccessAssignment replay = assignments.findByProvenance(
                            tenant,
                            AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                            request.requestItemId())
                    .orElseThrow(() -> race);
            requireSameIntent(replay, request);
            return replay;
        }
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
                || existing.principalConstraintKind()
                        != request.principalConstraintKind()
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
