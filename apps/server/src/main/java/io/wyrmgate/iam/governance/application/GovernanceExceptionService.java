package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.*;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.VersionState;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GovernanceExceptionService
        implements GovernanceExceptionQuery {

    private final GovernanceExceptionRepository exceptions;
    private final GovernancePolicyRepository policies;
    private final IdentityAccessReferenceQuery identities;
    private final ApprovalCaseStartService approvals;
    private final GovernanceExceptionChangeSink changes;
    private final GovernanceExceptionBoundaryScheduler boundaries;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public GovernanceExceptionService(
            GovernanceExceptionRepository exceptions,
            GovernancePolicyRepository policies,
            IdentityAccessReferenceQuery identities,
            ApprovalCaseStartService approvals,
            GovernanceExceptionChangeSink changes,
            GovernanceExceptionBoundaryScheduler boundaries,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.exceptions = Objects.requireNonNull(
                exceptions, "exceptions");
        this.policies = Objects.requireNonNull(
                policies, "policies");
        this.identities = Objects.requireNonNull(
                identities, "identities");
        this.approvals = Objects.requireNonNull(
                approvals, "approvals");
        this.changes = Objects.requireNonNull(
                changes, "changes");
        this.boundaries = Objects.requireNonNull(
                boundaries, "boundaries");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    public GovernanceException request(
            TenantContext tenant,
            UUID subjectIdentityId,
            UUID sodRuleId,
            UUID requesterIdentityId,
            String businessReason,
            Instant validFrom,
            Instant validUntil,
            UUID predecessorExceptionId,
            PlanSpec approvalPlan,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subjectIdentityId, "subjectIdentityId");
        Objects.requireNonNull(sodRuleId, "sodRuleId");
        Objects.requireNonNull(requesterIdentityId, "requesterIdentityId");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(approvalPlan, "approvalPlan");
        Objects.requireNonNull(now, "now");
        String reason = normalizeReason(businessReason);
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException(
                    "validUntil must be after validFrom");
        }
        if (!validUntil.isAfter(now)) {
            throw new IllegalArgumentException(
                    "GovernanceException must retain future validity when requested");
        }

        return transactions.required(() -> {
            if (!identities.identityExists(
                    tenant, subjectIdentityId)) {
                throw new IllegalArgumentException(
                        "subject Identity does not exist");
            }
            if (!identities.identityExists(
                    tenant, requesterIdentityId)) {
                throw new IllegalArgumentException(
                        "requester Identity does not exist");
            }
            for (StageSpec stage : approvalPlan.stages()) {
                for (UUID approverIdentityId :
                        stage.approverIdentityIds()) {
                    if (!identities.identityExists(
                            tenant,
                            approverIdentityId)) {
                        throw new IllegalArgumentException(
                                "approval approver Identity does not exist");
                    }
                }
            }

            var rule = policies.findRule(
                            tenant, sodRuleId)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "SoD rule does not exist"));
            var version = policies.findVersion(
                            tenant,
                            rule.policyVersionId())
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "SoD rule PolicyVersion does not exist"));
            if (version.state() != VersionState.ACTIVE) {
                throw new IllegalArgumentException(
                        "GovernanceException may only target an active SoD rule");
            }

            if (predecessorExceptionId != null) {
                GovernanceException predecessor =
                        exceptions.findById(
                                        tenant,
                                        predecessorExceptionId)
                                .orElseThrow(() ->
                                        new IllegalArgumentException(
                                                "predecessor GovernanceException does not exist"));
                if (!predecessor.subjectIdentityId()
                                .equals(subjectIdentityId)
                        || !predecessor.sodRuleId()
                                .equals(sodRuleId)) {
                    throw new IllegalArgumentException(
                            "renewal predecessor must have the same Identity and SoD rule scope");
                }
                if (predecessor.lifecycleState()
                                != LifecycleState.APPROVED
                        && predecessor.lifecycleState()
                                != LifecycleState.EXPIRED) {
                    throw new IllegalArgumentException(
                            "only approved or expired GovernanceException may be renewed");
                }
                if (validFrom.isBefore(
                        predecessor.validUntil())) {
                    throw new IllegalArgumentException(
                            "renewal validity must not overlap predecessor validity");
                }
            }

            UUID exceptionId = ids.nextId();
            ApprovalCase approvalCase =
                    approvals.start(
                            tenant,
                            SubjectKind.GOVERNANCE_EXCEPTION,
                            exceptionId,
                            requesterIdentityId,
                            approvalPlan,
                            now);
            GovernanceException exception =
                    new GovernanceException(
                            exceptionId,
                            ScopeKind.IDENTITY_SOD_RULE,
                            subjectIdentityId,
                            sodRuleId,
                            requesterIdentityId,
                            reason,
                            validFrom,
                            validUntil,
                            LifecycleState.PENDING_APPROVAL,
                            approvalCase.id(),
                            predecessorExceptionId,
                            1,
                            now,
                            now,
                            null,
                            null,
                            null,
                            null);
            exceptions.insert(
                    tenant, exception);
            return exception;
        });
    }

    public GovernanceException revoke(
            TenantContext tenant,
            UUID exceptionId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            GovernanceException current =
                    requireException(
                            tenant, exceptionId);
            requireRevision(
                    current, expectedRevision);
            if (current.lifecycleState()
                    != LifecycleState.APPROVED) {
                throw new IllegalArgumentException(
                        "only APPROVED GovernanceException may be revoked");
            }
            GovernanceException updated;
            if (!now.isBefore(
                    current.validUntil())) {
                updated = expireCurrent(
                        tenant,
                        current,
                        now);
            } else {
                updated = exceptions.updateState(
                        tenant,
                        current.id(),
                        LifecycleState.REVOKED,
                        expectedRevision,
                        now,
                        current.approvedAt(),
                        null,
                        now,
                        null);
                changes.changed(
                        tenant, updated);
            }
            return updated;
        });
    }

    public void approvalResolved(
            TenantContext tenant,
            ApprovalCase approvalCase) {
        if (approvalCase.subjectKind()
                != SubjectKind.GOVERNANCE_EXCEPTION) {
            return;
        }
        if (approvalCase.state()
                != CaseState.APPROVED
                && approvalCase.state()
                != CaseState.REJECTED) {
            return;
        }
        transactions.required(() -> {
            GovernanceException current =
                    requireException(
                            tenant,
                            approvalCase.subjectId());
            if (current.lifecycleState()
                    != LifecycleState.PENDING_APPROVAL
                    || !current.approvalCaseId()
                            .equals(approvalCase.id())) {
                throw new IllegalStateException(
                        "ApprovalCase no longer matches pending GovernanceException");
            }

            if (approvalCase.state()
                    == CaseState.REJECTED) {
                GovernanceException rejected =
                        exceptions.updateState(
                                tenant,
                                current.id(),
                                LifecycleState.REJECTED,
                                current.revision(),
                                approvalCase.updatedAt(),
                                null,
                                approvalCase.updatedAt(),
                                null,
                                null);
                changes.changed(
                        tenant, rejected);
                return null;
            }

            GovernanceException approved =
                    exceptions.updateState(
                            tenant,
                            current.id(),
                            LifecycleState.APPROVED,
                            current.revision(),
                            approvalCase.updatedAt(),
                            approvalCase.updatedAt(),
                            null,
                            null,
                            null);
            changes.changed(
                    tenant, approved);

            if (!approvalCase.updatedAt()
                    .isBefore(approved.validUntil())) {
                expireCurrent(
                        tenant,
                        approved,
                        approvalCase.updatedAt());
            } else {
                boundaries.scheduleExpiry(
                        tenant,
                        approved,
                        approvalCase.updatedAt());
            }
            return null;
        });
    }

    public GovernanceException expireIfDue(
            TenantContext tenant,
            UUID exceptionId,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            GovernanceException current =
                    requireException(
                            tenant, exceptionId);
            if (current.lifecycleState()
                    != LifecycleState.APPROVED) {
                return current;
            }
            if (now.isBefore(
                    current.validUntil())) {
                return current;
            }
            return expireCurrent(
                    tenant, current, now);
        });
    }

    @Override
    public Map<UUID,UUID> effectiveExceptionIds(
            TenantContext tenant,
            UUID subjectIdentityId,
            Set<UUID> sodRuleIds,
            Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subjectIdentityId, "subjectIdentityId");
        Objects.requireNonNull(sodRuleIds, "sodRuleIds");
        Objects.requireNonNull(at, "at");
        Map<UUID,UUID> result =
                new LinkedHashMap<>();
        for (GovernanceException exception :
                exceptions.findEffective(
                        tenant,
                        subjectIdentityId,
                        sodRuleIds,
                        at)) {
            if (exception.effectiveAt(at)) {
                result.putIfAbsent(
                        exception.sodRuleId(),
                        exception.id());
            }
        }
        return Map.copyOf(result);
    }

    public java.util.Optional<GovernanceException> find(
            TenantContext tenant,
            UUID exceptionId) {
        return exceptions.findById(
                tenant, exceptionId);
    }

    private GovernanceException expireCurrent(
            TenantContext tenant,
            GovernanceException current,
            Instant now) {
        GovernanceException expired =
                exceptions.updateState(
                        tenant,
                        current.id(),
                        LifecycleState.EXPIRED,
                        current.revision(),
                        now,
                        current.approvedAt(),
                        null,
                        null,
                        now);
        changes.changed(
                tenant, expired);
        return expired;
    }

    private GovernanceException requireException(
            TenantContext tenant,
            UUID exceptionId) {
        return exceptions.findById(
                        tenant, exceptionId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "GovernanceException does not exist"));
    }

    private static void requireRevision(
            GovernanceException current,
            long expectedRevision) {
        if (current.revision()
                != expectedRevision) {
            throw new StaleWriteException(
                    "governance-exception",
                    current.id(),
                    expectedRevision);
        }
    }

    private static String normalizeReason(
            String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "businessReason is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException(
                    "businessReason exceeds 1000 characters");
        }
        return normalized;
    }
}
