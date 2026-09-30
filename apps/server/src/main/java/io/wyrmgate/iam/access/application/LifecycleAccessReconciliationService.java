package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.LifecycleAccessPolicyVersion;
import io.wyrmgate.iam.identity.application.IdentityLifecycleAccessQuery;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Access-owned current-state reconciliation for Joiner baseline and Mover access deltas. */
public final class LifecycleAccessReconciliationService {

    public static final String INPUT_CHANGED = "identity.lifecycle-access-input-changed";
    public static final String APPROVAL_APPROVED = "governance.lifecycle-access-approval-approved";

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 50;

    private final JdbcOutboxRepository outbox;
    private final LifecycleAccessPolicyRepository policies;
    private final AccessAssignmentRepository assignments;
    private final AccessAssignmentCommandService commands;
    private final IdentityLifecycleAccessQuery identities;
    private final LifecycleAccessPrivilegeGuard guard;
    private final LifecycleAccessApprovalCommand approvals;
    private final Clock clock;

    public LifecycleAccessReconciliationService(
            JdbcOutboxRepository outbox,
            LifecycleAccessPolicyRepository policies,
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            IdentityLifecycleAccessQuery identities,
            LifecycleAccessPrivilegeGuard guard,
            LifecycleAccessApprovalCommand approvals) {
        this(outbox,policies,assignments,commands,identities,guard,approvals,Clock.systemUTC());
    }

    LifecycleAccessReconciliationService(
            JdbcOutboxRepository outbox,
            LifecycleAccessPolicyRepository policies,
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            IdentityLifecycleAccessQuery identities,
            LifecycleAccessPrivilegeGuard guard,
            LifecycleAccessApprovalCommand approvals,
            Clock clock) {
        this.outbox=Objects.requireNonNull(outbox);
        this.policies=Objects.requireNonNull(policies);
        this.assignments=Objects.requireNonNull(assignments);
        this.commands=Objects.requireNonNull(commands);
        this.identities=Objects.requireNonNull(identities);
        this.guard=Objects.requireNonNull(guard);
        this.approvals=Objects.requireNonNull(approvals);
        this.clock=Objects.requireNonNull(clock);
    }

    public BatchResult processAvailable() {
        List<ClaimedOutboxEvent> claimed=outbox.claimPending(
                Set.of(INPUT_CHANGED,APPROVAL_APPROVED),clock.instant(),CLAIM_LEASE,CLAIM_BATCH);
        int processed=0;
        int failed=0;
        for(var item:claimed){
            try{
                UUID correlationId = item.event().correlationId() == null
                        ? item.event().eventId()
                        : item.event().correlationId();
                reconcileEvent(
                        item.tenant(),
                        item.event().aggregateId(),
                        clock.instant(),
                        correlationId,
                        item.event().eventId());
                outbox.markPublished(item.tenant(),item.event().eventId(),clock.instant());
                processed++;
            }catch(IllegalArgumentException invalid){
                outbox.markTerminalFailure(
                        item.tenant(),item.event().eventId(),"lifecycle_access_input_invalid");
                failed++;
            }catch(RuntimeException retryable){
                outbox.markFailed(
                        item.tenant(),item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "lifecycle_access_reconciliation_failed");
                failed++;
            }
        }
        return new BatchResult(claimed.size(),processed,failed);
    }

    void reconcileEvent(TenantContext tenant, UUID identityId, Instant now) {
        reconcileEvent(tenant, identityId, now, identityId, identityId);
    }

    void reconcileEvent(
            TenantContext tenant,
            UUID identityId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(identityId,"identityId");
        Objects.requireNonNull(correlationId,"correlationId");
        LifecycleAccessPolicyVersion policy=policies.findActive(tenant).orElse(null);
        if(policy==null){
            removeAllPolicyAssignments(
                    tenant,identityId,now,correlationId,causationId);
            return;
        }

        Set<String> keys=new LinkedHashSet<>();
        for(var rule:policy.rules()){
            if(rule.predicateKind()==LifecycleAccessPolicyVersion.PredicateKind.CANONICAL_STRING_EQUALS){
                keys.add(rule.canonicalKey());
            }
        }
        var context=identities.currentContext(tenant,identityId,keys);
        if(context.status()==IdentityLifecycleAccessQuery.Status.NOT_FOUND){
            return;
        }

        Map<UUID,LifecycleAccessPolicyVersion.Rule> desired=new LinkedHashMap<>();
        if("ACTIVE".equals(context.lifecycleState())){
            for(var rule:policy.rules()){
                if(matches(rule,context)){
                    desired.put(rule.ruleId(),rule);
                }
            }
        }

        // Authoritative reductions happen before any evaluator-dependent privilege increase.
        for(AccessAssignment existing:assignments.findCurrentLifecyclePolicyAssignments(tenant,identityId)){
            var rule=desired.get(existing.provenanceRefId());
            if(rule==null || !sameTarget(existing,rule)){
                commands.terminateLifecyclePolicyAssignment(
                        tenant,
                        existing.id(),
                        existing.revision(),
                        now,
                        correlationId,
                        causationId);
            }
        }

        for(var rule:desired.values()){
            AccessAssignment existing=assignments.findCurrentLifecyclePolicyAssignment(
                    tenant,identityId,rule.ruleId()).orElse(null);
            if(existing!=null && sameTarget(existing,rule)){
                continue;
            }

            var decision=guard.evaluate(
                    tenant,
                    identityId,
                    rule.ruleId(),
                    rule.targetKind(),
                    rule.targetId(),
                    now,
                    correlationId,
                    causationId);
            if(decision.decision()==LifecycleAccessPrivilegeGuard.Decision.UNAVAILABLE){
                throw new IllegalStateException(decision.code());
            }
            if(decision.decision()==LifecycleAccessPrivilegeGuard.Decision.REQUIRE_APPROVAL){
                if(!approvals.currentApprovalSatisfied(
                        tenant,identityId,rule.ruleId(),rule.targetKind(),rule.targetId())){
                    approvals.requestApproval(
                            tenant,identityId,rule.ruleId(),rule.targetKind(),rule.targetId(),
                            now,correlationId,causationId);
                    continue;
                }
            }else if(decision.decision()!=LifecycleAccessPrivilegeGuard.Decision.AUTHORIZE){
                continue;
            }

            try{
                if(rule.targetKind()==AccessAssignment.TargetKind.ENTITLEMENT){
                    commands.createLifecyclePolicyEntitlementAssignment(
                            tenant,
                            rule.ruleId(),
                            identityId,
                            rule.targetId(),
                            now,
                            correlationId,
                            causationId);
                }else{
                    commands.createLifecyclePolicyRoleAssignment(
                            tenant,
                            rule.ruleId(),
                            identityId,
                            rule.targetId(),
                            now,
                            correlationId,
                            causationId);
                }
            }catch(AccessAssignmentProvenanceConflictException race){
                AccessAssignment replay=assignments.findCurrentLifecyclePolicyAssignment(
                        tenant,identityId,rule.ruleId()).orElseThrow(()->race);
                if(!sameTarget(replay,rule)) throw race;
            }
        }
    }

    private void removeAllPolicyAssignments(
            TenantContext tenant,
            UUID identityId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        for(AccessAssignment existing:assignments.findCurrentLifecyclePolicyAssignments(tenant,identityId)){
            commands.terminateLifecyclePolicyAssignment(
                    tenant,
                    existing.id(),
                    existing.revision(),
                    now,
                    correlationId,
                    causationId);
        }
    }

    private static boolean matches(
            LifecycleAccessPolicyVersion.Rule rule,
            IdentityLifecycleAccessQuery.Context context) {
        if(rule.predicateKind()==LifecycleAccessPolicyVersion.PredicateKind.ALWAYS){
            return true;
        }
        var value=context.canonicalStrings().get(rule.canonicalKey());
        return value!=null && value.trusted()
                && Objects.equals(value.value(),rule.expectedString());
    }

    private static boolean sameTarget(
            AccessAssignment assignment,
            LifecycleAccessPolicyVersion.Rule rule) {
        if(assignment.targetKind()!=rule.targetKind()) return false;
        return rule.targetKind()==AccessAssignment.TargetKind.ENTITLEMENT
                ? Objects.equals(assignment.entitlementId(),rule.targetId())
                : Objects.equals(assignment.roleId(),rule.targetId());
    }

    public record BatchResult(int claimed,int processed,int failed){
        public BatchResult{
            if(claimed<0 || processed<0 || failed<0 || processed+failed!=claimed){
                throw new IllegalArgumentException("invalid lifecycle access batch counts");
            }
        }
    }
}
