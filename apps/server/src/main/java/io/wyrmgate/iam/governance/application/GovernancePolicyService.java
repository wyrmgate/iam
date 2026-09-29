package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.*;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GovernancePolicyService {

    private static final int MAX_SOD_RULES = 1000;

    private final GovernancePolicyRepository repository;
    private final CatalogAccessReferenceQuery catalog;
    private final IdentityAccessReferenceQuery identities;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public GovernancePolicyService(
            GovernancePolicyRepository repository,
            CatalogAccessReferenceQuery catalog,
            IdentityAccessReferenceQuery identities,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public PolicyVersion createDraft(
            TenantContext tenant,
            PolicyKind kind,
            PolicyDecision defaultDecision,
            List<SoDRuleSpec> rules,
            PlanSpec approvalPlan,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(defaultDecision, "defaultDecision");
        rules = rules == null ? List.of() : List.copyOf(rules);
        Objects.requireNonNull(now, "now");
        if (rules.size() > MAX_SOD_RULES) {
            throw new IllegalArgumentException(
                    "a policy version supports at most " + MAX_SOD_RULES + " SoD rules");
        }

        List<NormalizedRule> normalizedRules = normalizeRules(rules);
        PlanSpec normalizedPlan = approvalPlan;
        boolean approvalPossible = defaultDecision == PolicyDecision.REQUIRE_APPROVAL
                || normalizedRules.stream()
                        .anyMatch(r -> r.action() == SoDAction.REQUIRE_APPROVAL);
        if (approvalPossible && normalizedPlan == null) {
            throw new IllegalArgumentException(
                    "approval plan is required when policy may require approval");
        }
        if (!approvalPossible && normalizedPlan != null) {
            throw new IllegalArgumentException(
                    "approval plan is only valid when policy may require approval");
        }

        return transactions.required(() -> {
            Policy policy = repository.findPolicy(tenant, kind)
                    .orElseGet(() -> {
                        Policy created = new Policy(
                                ids.nextId(),
                                kind,
                                1,
                                now,
                                now);
                        repository.insertPolicy(tenant, created);
                        return created;
                    });

            PolicyVersion version = new PolicyVersion(
                    ids.nextId(),
                    policy.id(),
                    repository.nextVersionNumber(tenant, policy.id()),
                    VersionState.DRAFT,
                    defaultDecision,
                    1,
                    now,
                    now,
                    null,
                    null);
            repository.insertVersion(tenant, version);

            for (NormalizedRule rule : normalizedRules) {
                repository.insertRule(
                        tenant,
                        new SoDRule(
                                ids.nextId(),
                                version.id(),
                                rule.code(),
                                rule.left(),
                                rule.right(),
                                rule.severity(),
                                rule.action(),
                                now));
            }

            if (normalizedPlan != null) {
                int ordinal = 0;
                for (StageSpec stageSpec : normalizedPlan.stages()) {
                    PolicyApprovalStage stage = new PolicyApprovalStage(
                            ids.nextId(),
                            version.id(),
                            ordinal++,
                            stageSpec.decisionMode(),
                            now);
                    repository.insertApprovalStage(tenant, stage);
                    for (UUID approverId : stageSpec.approverIdentityIds()) {
                        repository.insertApprovalApprover(
                                tenant,
                                new PolicyApprovalApprover(
                                        ids.nextId(),
                                        stage.id(),
                                        approverId,
                                        now));
                    }
                }
            }
            return version;
        });
    }

    public PolicyVersion markReady(
            TenantContext tenant,
            UUID versionId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            PolicyVersion version = requireVersion(tenant, versionId);
            requireRevision(version, expectedRevision);
            if (version.state() != VersionState.DRAFT) {
                throw new IllegalArgumentException(
                        "only DRAFT policy version can become READY");
            }
            validateSnapshot(tenant, snapshot(tenant, version));
            return repository.updateVersionState(
                    tenant,
                    version.id(),
                    VersionState.READY,
                    expectedRevision,
                    now,
                    null,
                    null);
        });
    }

    public PolicyVersion activate(
            TenantContext tenant,
            UUID versionId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            PolicyVersion target = requireVersion(tenant, versionId);
            requireRevision(target, expectedRevision);
            if (target.state() == VersionState.ACTIVE) {
                return target;
            }
            if (target.state() != VersionState.READY) {
                throw new IllegalArgumentException(
                        "only READY policy version can be activated");
            }
            validateSnapshot(tenant, snapshot(tenant, target));

            Policy currentPolicy = repository.findPolicy(
                            tenant, PolicyKind.ACCESS_REQUEST)
                    .orElseThrow();
            if (!currentPolicy.id().equals(target.policyId())) {
                throw new IllegalArgumentException(
                        "policy version does not belong to ACCESS_REQUEST policy");
            }

            repository.findActiveVersion(tenant, PolicyKind.ACCESS_REQUEST)
                    .filter(active -> !active.id().equals(target.id()))
                    .ifPresent(active -> repository.updateVersionState(
                            tenant,
                            active.id(),
                            VersionState.SUPERSEDED,
                            active.revision(),
                            now,
                            active.activatedAt(),
                            now));

            return repository.updateVersionState(
                    tenant,
                    target.id(),
                    VersionState.ACTIVE,
                    expectedRevision,
                    now,
                    now,
                    null);
        });
    }

    public PolicySnapshot activeSnapshot(
            TenantContext tenant,
            PolicyKind kind) {
        PolicyVersion version = repository.findActiveVersion(tenant, kind)
                .orElseThrow(() -> new IllegalStateException(
                        "active " + kind + " policy is unavailable"));
        return snapshot(tenant, version);
    }

    public java.util.Optional<PolicySnapshot> findActiveSnapshot(
            TenantContext tenant,
            PolicyKind kind) {
        return repository.findActiveVersion(tenant, kind)
                .map(version -> snapshot(tenant, version));
    }

    private PolicySnapshot snapshot(
            TenantContext tenant,
            PolicyVersion version) {
        List<ApprovalStageSnapshot> stages = new ArrayList<>();
        for (PolicyApprovalStage stage :
                repository.findApprovalStages(tenant, version.id())) {
            stages.add(new ApprovalStageSnapshot(
                    stage,
                    repository.findApprovalApprovers(
                            tenant, stage.id())));
        }
        return new PolicySnapshot(
                version,
                repository.findRules(tenant, version.id()),
                stages);
    }

    private void validateSnapshot(
            TenantContext tenant,
            PolicySnapshot snapshot) {
        boolean approvalPossible =
                snapshot.version().defaultDecision()
                        == PolicyDecision.REQUIRE_APPROVAL
                || snapshot.rules().stream()
                        .anyMatch(rule -> rule.action()
                                == SoDAction.REQUIRE_APPROVAL);
        if (approvalPossible && snapshot.approvalStages().isEmpty()) {
            throw new IllegalArgumentException(
                    "policy version requires an approval plan");
        }
        if (!approvalPossible && !snapshot.approvalStages().isEmpty()) {
            throw new IllegalArgumentException(
                    "approval plan is not allowed when policy can never require approval");
        }

        int expectedOrdinal = 0;
        for (ApprovalStageSnapshot stage : snapshot.approvalStages()) {
            if (stage.stage().ordinal() != expectedOrdinal++) {
                throw new IllegalArgumentException(
                        "approval stages must be contiguous from ordinal zero");
            }
            if (stage.approvers().isEmpty()) {
                throw new IllegalArgumentException(
                        "approval stage requires at least one approver");
            }
            for (PolicyApprovalApprover approver : stage.approvers()) {
                if (!identities.identityExists(
                        tenant, approver.approverIdentityId())) {
                    throw new IllegalArgumentException(
                            "approval approver Identity does not exist");
                }
            }
        }

        for (SoDRule rule : snapshot.rules()) {
            requireActiveEntitlement(tenant, rule.leftEntitlementId());
            requireActiveEntitlement(tenant, rule.rightEntitlementId());
        }
    }

    private void requireActiveEntitlement(
            TenantContext tenant,
            UUID entitlementId) {
        var reference = catalog.resolveActiveEntitlement(
                tenant, entitlementId);
        if (reference.status()
                != CatalogAccessReferenceQuery.Status.VALID) {
            throw new IllegalArgumentException(
                    "SoD rule entitlement is not an active target-scoped Entitlement");
        }
    }

    private PolicyVersion requireVersion(
            TenantContext tenant,
            UUID versionId) {
        return repository.findVersion(tenant, versionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "policy version does not exist"));
    }

    private static void requireRevision(
            PolicyVersion version,
            long expectedRevision) {
        if (version.revision() != expectedRevision) {
            throw new StaleWriteException(
                    "policy-version",
                    version.id(),
                    expectedRevision);
        }
    }

    private static List<NormalizedRule> normalizeRules(
            List<SoDRuleSpec> rules) {
        Set<String> codes = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        List<NormalizedRule> result = new ArrayList<>();
        for (SoDRuleSpec rule : rules) {
            Objects.requireNonNull(rule, "rule");
            String code = normalizeCode(rule.code());
            Objects.requireNonNull(rule.leftEntitlementId(), "leftEntitlementId");
            Objects.requireNonNull(rule.rightEntitlementId(), "rightEntitlementId");
            if (rule.leftEntitlementId().equals(rule.rightEntitlementId())) {
                throw new IllegalArgumentException(
                        "SoD rule requires two distinct Entitlements");
            }
            Objects.requireNonNull(rule.severity(), "severity");
            Objects.requireNonNull(rule.action(), "action");
            if (rule.severity() == RiskSeverity.NONE) {
                throw new IllegalArgumentException(
                        "SoD rule severity cannot be NONE");
            }
            UUID left = rule.leftEntitlementId();
            UUID right = rule.rightEntitlementId();
            if (left.compareTo(right) > 0) {
                UUID swap = left;
                left = right;
                right = swap;
            }
            if (!codes.add(code)) {
                throw new IllegalArgumentException(
                        "SoD rule code must be unique within policy version");
            }
            String pair = left + "|" + right;
            if (!pairs.add(pair)) {
                throw new IllegalArgumentException(
                        "SoD entitlement pair must be unique within policy version");
            }
            result.add(new NormalizedRule(
                    code,
                    left,
                    right,
                    rule.severity(),
                    rule.action()));
        }
        return List.copyOf(result);
    }

    private static String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SoD rule code is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "SoD rule code exceeds 128 characters");
        }
        return normalized;
    }

    private record NormalizedRule(
            String code,
            UUID left,
            UUID right,
            RiskSeverity severity,
            SoDAction action) {}
}
