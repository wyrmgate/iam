package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.application.AccessRequestModels.AccessRequest;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.*;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Governance-owned concrete AccessRequest eligibility evaluator.
 *
 * <p>All foreign state is consumed through semantic Catalog and Access queries.
 * Completed evaluations persist immutable policy/risk/SoD evidence.</p>
 */
public final class GovernancePolicyEligibilityEvaluator
        implements AccessRequestEligibilityEvaluator {

    private static final int MAX_REQUEST_ITEMS_FOR_SOD = 1000;

    private final GovernancePolicyService policyService;
    private final GovernancePolicyRepository policyRepository;
    private final AccessRequestRepository requests;
    private final CatalogAccessReferenceQuery catalog;
    private final RoleExpansionQuery roles;
    private final EffectiveAccessQuery effectiveAccess;
    private final GovernanceExceptionQuery exceptions;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public GovernancePolicyEligibilityEvaluator(
            GovernancePolicyService policyService,
            GovernancePolicyRepository policyRepository,
            AccessRequestRepository requests,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles,
            EffectiveAccessQuery effectiveAccess,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this(
                policyService,
                policyRepository,
                requests,
                catalog,
                roles,
                effectiveAccess,
                (tenant, identityId, ruleIds, at) -> Map.of(),
                ids,
                transactions,
                Clock.systemUTC());
    }

    public GovernancePolicyEligibilityEvaluator(
            GovernancePolicyService policyService,
            GovernancePolicyRepository policyRepository,
            AccessRequestRepository requests,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles,
            EffectiveAccessQuery effectiveAccess,
            GovernanceExceptionQuery exceptions,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this(
                policyService,
                policyRepository,
                requests,
                catalog,
                roles,
                effectiveAccess,
                exceptions,
                ids,
                transactions,
                Clock.systemUTC());
    }

    GovernancePolicyEligibilityEvaluator(
            GovernancePolicyService policyService,
            GovernancePolicyRepository policyRepository,
            AccessRequestRepository requests,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles,
            EffectiveAccessQuery effectiveAccess,
            IdGenerator ids,
            TransactionExecutor transactions,
            Clock clock) {
        this(
                policyService,
                policyRepository,
                requests,
                catalog,
                roles,
                effectiveAccess,
                (tenant, identityId, ruleIds, at) -> Map.of(),
                ids,
                transactions,
                clock);
    }

    GovernancePolicyEligibilityEvaluator(
            GovernancePolicyService policyService,
            GovernancePolicyRepository policyRepository,
            AccessRequestRepository requests,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles,
            EffectiveAccessQuery effectiveAccess,
            GovernanceExceptionQuery exceptions,
            IdGenerator ids,
            TransactionExecutor transactions,
            Clock clock) {
        this.policyService = Objects.requireNonNull(
                policyService, "policyService");
        this.policyRepository = Objects.requireNonNull(
                policyRepository, "policyRepository");
        this.requests = Objects.requireNonNull(
                requests, "requests");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.effectiveAccess = Objects.requireNonNull(
                effectiveAccess, "effectiveAccess");
        this.exceptions = Objects.requireNonNull(
                exceptions, "exceptions");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EligibilityResult evaluate(
            TenantContext tenant,
            AccessRequest request,
            RequestItem item) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(item, "item");

        PolicySnapshot policy = policyService.findActiveSnapshot(
                        tenant, PolicyKind.ACCESS_REQUEST)
                .orElse(null);
        if (policy == null) {
            return EligibilityResult.unavailable(
                    "active_access_request_policy_unavailable");
        }

        Instant evaluatedAt = clock.instant();

        Expansion current = expandRequested(
                tenant, item);
        if (!current.available()) {
            return EligibilityResult.denied(
                    current.failureCode());
        }

        List<RequestItem> requestItems =
                requests.findItems(tenant, request.id());
        if (requestItems.size()
                > MAX_REQUEST_ITEMS_FOR_SOD) {
            return EligibilityResult.unavailable(
                    "request_too_large_for_policy_evaluation");
        }

        Set<UUID> otherRequested =
                new LinkedHashSet<>();
        for (RequestItem other : requestItems) {
            if (other.id().equals(item.id())) {
                continue;
            }
            Expansion expansion = expandRequested(
                    tenant, other);
            if (expansion.available()) {
                otherRequested.addAll(
                        expansion.entitlementIds());
            }
        }

        Set<UUID> relevantCounterparts =
                counterpartIds(
                        policy.rules(),
                        current.entitlementIds());
        Set<UUID> currentlyEffective =
                relevantCounterparts.isEmpty()
                        ? Set.of()
                        : effectiveAccess.currentEntitlementIds(
                                tenant,
                                request.beneficiaryIdentityId(),
                                relevantCounterparts,
                                evaluatedAt);

        List<ConflictCandidate> conflicts =
                detectConflicts(
                        policy.rules(),
                        current.entitlementIds(),
                        otherRequested,
                        currentlyEffective);

        Set<UUID> conflictRuleIds =
                conflicts.stream()
                        .map(ConflictCandidate::ruleId)
                        .collect(java.util.stream.Collectors.toSet());
        Map<UUID,UUID> exceptionIds =
                conflictRuleIds.isEmpty()
                        ? Map.of()
                        : exceptions.effectiveExceptionIds(
                                tenant,
                                request.beneficiaryIdentityId(),
                                conflictRuleIds,
                                evaluatedAt);

        RiskSeverity severity = conflicts.stream()
                .map(ConflictCandidate::severity)
                .max(Comparator.comparingInt(
                        GovernancePolicyEligibilityEvaluator
                                ::severityRank))
                .orElse(RiskSeverity.NONE);

        PolicyDecision decision =
                policy.version().defaultDecision();
        boolean hasUncoveredConflict = false;
        for (ConflictCandidate conflict : conflicts) {
            if (exceptionIds.containsKey(
                    conflict.ruleId())) {
                continue;
            }
            hasUncoveredConflict = true;
            PolicyDecision candidate =
                    conflict.action()
                            == SoDAction.DENY
                            ? PolicyDecision.DENY
                            : PolicyDecision.REQUIRE_APPROVAL;
            if (decisionRank(candidate)
                    > decisionRank(decision)) {
                decision = candidate;
            }
        }

        String code = decisionCode(
                decision,
                conflicts.isEmpty(),
                hasUncoveredConflict);
        PolicyDecision finalDecision = decision;

        transactions.required(() -> {
            UUID riskId = ids.nextId();
            policyRepository.insertRiskAssessment(
                    tenant,
                    new RiskAssessment(
                            riskId,
                            item.id(),
                            policy.version().id(),
                            severity,
                            conflicts.size(),
                            evaluatedAt));
            for (ConflictCandidate conflict :
                    conflicts) {
                policyRepository.insertSoDConflict(
                        tenant,
                        new SoDConflict(
                                ids.nextId(),
                                riskId,
                                conflict.ruleId(),
                                conflict.requestedEntitlementId(),
                                conflict.conflictingEntitlementId(),
                                conflict.source(),
                                conflict.severity(),
                                conflict.action(),
                                exceptionIds.get(
                                        conflict.ruleId()),
                                evaluatedAt));
            }
            policyRepository.insertPolicyEvaluation(
                    tenant,
                    new PolicyEvaluation(
                            ids.nextId(),
                            request.id(),
                            item.id(),
                            item.revision(),
                            policy.version().id(),
                            riskId,
                            finalDecision,
                            code,
                            evaluatedAt));
            return null;
        });

        return switch (decision) {
            case AUTHORIZE ->
                    new EligibilityResult(
                            AccessRequestModels
                                    .EligibilityOutcome.AUTHORIZED,
                            code,
                            null);
            case DENY ->
                    EligibilityResult.denied(code);
            case REQUIRE_APPROVAL ->
                    EligibilityResult.approvalRequired(
                            approvalPlan(policy));
        };
    }

    private Expansion expandRequested(
            TenantContext tenant,
            RequestItem item) {
        if (item.targetKind()
                == TargetKind.ENTITLEMENT) {
            var reference =
                    catalog.resolveActiveEntitlement(
                            tenant,
                            item.entitlementId());
            if (reference.status()
                    != CatalogAccessReferenceQuery.Status.VALID) {
                return Expansion.unavailable(
                        "requested_entitlement_unavailable");
            }
            return Expansion.available(
                    Set.of(item.entitlementId()));
        }

        var expansion =
                roles.expandCurrent(
                        tenant, item.roleId());
        if (expansion.status()
                != RoleExpansionQuery.Status.AVAILABLE) {
            return Expansion.unavailable(
                    "requested_role_unavailable");
        }
        Set<UUID> entitlementIds =
                new LinkedHashSet<>();
        for (var path : expansion.paths()) {
            entitlementIds.add(
                    path.entitlementId());
        }
        return Expansion.available(
                Set.copyOf(entitlementIds));
    }

    private static Set<UUID> counterpartIds(
            List<SoDRule> rules,
            Set<UUID> requested) {
        Set<UUID> result =
                new LinkedHashSet<>();
        for (SoDRule rule : rules) {
            if (requested.contains(
                    rule.leftEntitlementId())) {
                result.add(
                        rule.rightEntitlementId());
            }
            if (requested.contains(
                    rule.rightEntitlementId())) {
                result.add(
                        rule.leftEntitlementId());
            }
        }
        result.removeAll(requested);
        return Set.copyOf(result);
    }

    private static List<ConflictCandidate>
            detectConflicts(
                    List<SoDRule> rules,
                    Set<UUID> currentRequested,
                    Set<UUID> otherRequested,
                    Set<UUID> currentlyEffective) {
        Map<String,ConflictCandidate> result =
                new LinkedHashMap<>();

        for (SoDRule rule : rules) {
            UUID left = rule.leftEntitlementId();
            UUID right = rule.rightEntitlementId();

            if (currentRequested.contains(left)
                    && currentRequested.contains(right)) {
                addConflict(
                        result,
                        new ConflictCandidate(
                                rule.id(),
                                left,
                                right,
                                ConflictSource.REQUEST_ITEM,
                                rule.severity(),
                                rule.action()));
            }

            detectDirectional(
                    result,
                    rule,
                    left,
                    right,
                    currentRequested,
                    otherRequested,
                    currentlyEffective);
            detectDirectional(
                    result,
                    rule,
                    right,
                    left,
                    currentRequested,
                    otherRequested,
                    currentlyEffective);
        }

        return List.copyOf(result.values());
    }

    private static void detectDirectional(
            Map<String,ConflictCandidate> result,
            SoDRule rule,
            UUID requestedSide,
            UUID counterpart,
            Set<UUID> currentRequested,
            Set<UUID> otherRequested,
            Set<UUID> currentlyEffective) {
        if (!currentRequested.contains(
                requestedSide)) {
            return;
        }
        if (currentlyEffective.contains(
                counterpart)) {
            addConflict(
                    result,
                    new ConflictCandidate(
                            rule.id(),
                            requestedSide,
                            counterpart,
                            ConflictSource.CURRENT_ACCESS,
                            rule.severity(),
                            rule.action()));
        }
        if (otherRequested.contains(
                counterpart)) {
            addConflict(
                    result,
                    new ConflictCandidate(
                            rule.id(),
                            requestedSide,
                            counterpart,
                            ConflictSource.REQUEST,
                            rule.severity(),
                            rule.action()));
        }
    }

    private static void addConflict(
            Map<String,ConflictCandidate> result,
            ConflictCandidate conflict) {
        String key = conflict.ruleId()
                + "|" + conflict.requestedEntitlementId()
                + "|" + conflict.conflictingEntitlementId()
                + "|" + conflict.source();
        result.putIfAbsent(key, conflict);
    }

    private static PlanSpec approvalPlan(
            PolicySnapshot policy) {
        List<StageSpec> stages =
                new ArrayList<>();
        for (ApprovalStageSnapshot stage :
                policy.approvalStages()) {
            stages.add(new StageSpec(
                    stage.stage().decisionMode(),
                    stage.approvers().stream()
                            .map(PolicyApprovalApprover
                                    ::approverIdentityId)
                            .toList()));
        }
        return new PlanSpec(stages);
    }

    private static int severityRank(
            RiskSeverity value) {
        return switch (value) {
            case NONE -> 0;
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
            case CRITICAL -> 4;
        };
    }

    private static int decisionRank(
            PolicyDecision value) {
        return switch (value) {
            case AUTHORIZE -> 0;
            case REQUIRE_APPROVAL -> 1;
            case DENY -> 2;
        };
    }

    private static String decisionCode(
            PolicyDecision decision,
            boolean noConflicts,
            boolean hasUncoveredConflict) {
        return switch (decision) {
            case AUTHORIZE ->
                    noConflicts
                            ? "policy_authorized"
                            : "sod_exception_authorized";
            case REQUIRE_APPROVAL ->
                    hasUncoveredConflict
                            ? "sod_approval_required"
                            : "policy_approval_required";
            case DENY ->
                    hasUncoveredConflict
                            ? "sod_denied"
                            : "policy_denied";
        };
    }

    private record Expansion(
            boolean available,
            Set<UUID> entitlementIds,
            String failureCode) {
        static Expansion available(
                Set<UUID> entitlementIds) {
            return new Expansion(
                    true,
                    Set.copyOf(entitlementIds),
                    null);
        }

        static Expansion unavailable(
                String code) {
            return new Expansion(
                    false,
                    Set.of(),
                    code);
        }
    }

    private record ConflictCandidate(
            UUID ruleId,
            UUID requestedEntitlementId,
            UUID conflictingEntitlementId,
            ConflictSource source,
            RiskSeverity severity,
            SoDAction action) {}
}
