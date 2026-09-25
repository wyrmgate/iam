package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class EffectiveAccessProcessingService {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 100;
    private static final String INVALID_FACT = "effective_access_fact_invalid";
    private static final String PROCESSING_FAILED = "effective_access_projection_failed";
    private static final String LEASE_OWNER = "access-effective-access-projector";

    private final JdbcOutboxRepository outbox;
    private final JdbcScheduledWorkRepository scheduledWork;
    private final AccessAssignmentRepository assignments;
    private final EffectiveAccessRepository effectiveAccess;
    private final DesiredStateDerivationService desiredState;
    private final Clock clock;

    public EffectiveAccessProcessingService(
            JdbcOutboxRepository outbox,
            JdbcScheduledWorkRepository scheduledWork,
            AccessAssignmentRepository assignments,
            EffectiveAccessRepository effectiveAccess,
            DesiredStateDerivationService desiredState) {
        this(outbox, scheduledWork, assignments, effectiveAccess, desiredState, Clock.systemUTC());
    }

    EffectiveAccessProcessingService(
            JdbcOutboxRepository outbox,
            JdbcScheduledWorkRepository scheduledWork,
            AccessAssignmentRepository assignments,
            EffectiveAccessRepository effectiveAccess,
            DesiredStateDerivationService desiredState,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.scheduledWork = Objects.requireNonNull(scheduledWork, "scheduledWork");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.effectiveAccess = Objects.requireNonNull(effectiveAccess, "effectiveAccess");
        this.desiredState = Objects.requireNonNull(desiredState, "desiredState");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProcessingResult processAvailable() {
        int factProcessed = processFacts();
        int boundaryProcessed = processBoundaries();
        return new ProcessingResult(factProcessed, boundaryProcessed);
    }

    private int processFacts() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(AccessAssignmentFactSink.PROJECTION_INPUT_CHANGED),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);
        int processed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                var event = item.event();
                if (event.eventVersion() != 1
                        || !"access-assignment".equals(event.aggregateType())
                        || event.aggregateId() == null) {
                    throw new IllegalArgumentException(
                            "unsupported AccessAssignment projection fact");
                }
                reconcile(item.tenant(), event.aggregateId(), clock.instant());
                outbox.markPublished(
                        item.tenant(), event.eventId(), clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(), item.event().eventId(), INVALID_FACT);
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        PROCESSING_FAILED);
            }
        }
        return processed;
    }

    private int processBoundaries() {
        Instant now = clock.instant();
        var claimed = scheduledWork.claimDueByHandler(
                AccessAssignmentBoundaryScheduler.HANDLER_TYPE,
                LEASE_OWNER,
                now,
                CLAIM_LEASE,
                BATCH_SIZE);
        int processed = 0;
        for (var item : claimed) {
            var work = item.work();
            var subject = work.subject();
            if (subject == null
                    || !"access-assignment".equals(subject.subjectType())) {
                scheduledWork.markCompleted(
                        item.tenant(), work.id(), LEASE_OWNER, clock.instant());
                continue;
            }
            try {
                reconcile(
                        item.tenant(), subject.subjectId(), clock.instant());
                scheduledWork.markCompleted(
                        item.tenant(), work.id(), LEASE_OWNER, clock.instant());
                processed++;
            } catch (RuntimeException retryable) {
                // Leave READY with the active lease; expiry makes it retryable.
            }
        }
        return processed;
    }

    public void reconcile(
            TenantContext tenant,
            UUID assignmentId,
            Instant at) {
        AccessAssignment assignment = assignments.findById(
                        tenant, assignmentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AccessAssignment projection input does not exist"));

        if (assignment.targetKind() != AccessAssignment.TargetKind.ENTITLEMENT) {
            effectiveAccess.removeAssignmentSupport(tenant, assignmentId, at);
            return;
        }

        String constraintKey = principalConstraintKey(assignment);
        if (!assignment.isSemanticallyEffectiveAt(at)) {
            effectiveAccess.removeAssignmentSupport(
                    tenant, assignmentId, at);
        } else {
            effectiveAccess.applyDirectAssignment(
                    tenant,
                    assignment,
                    constraintKey,
                    directPathHash(assignment, constraintKey),
                    at);
        }

        desiredState.reconcileGrant(
                tenant,
                assignment.identityId(),
                assignment.entitlementId(),
                constraintKey,
                at);
    }

    static String principalConstraintKey(AccessAssignment assignment) {
        return switch (assignment.principalConstraintKind()) {
            case ANY -> "ANY";
            case SPECIFIC -> "SPECIFIC:" + assignment.specificPrincipalId();
        };
    }

    static String directPathHash(
            AccessAssignment assignment,
            String principalConstraintKey) {
        String canonical = "DIRECT|"
                + assignment.id()
                + "|" + assignment.identityId()
                + "|" + assignment.entitlementId()
                + "|" + principalConstraintKey;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record ProcessingResult(int factsProcessed, int boundariesProcessed) {
        public ProcessingResult {
            if (factsProcessed < 0 || boundariesProcessed < 0) {
                throw new IllegalArgumentException("processed counts must be non-negative");
            }
        }
    }
}
