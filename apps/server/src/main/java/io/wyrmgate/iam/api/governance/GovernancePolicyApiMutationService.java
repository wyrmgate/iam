package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.GovernancePolicyService;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyDecision;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicySnapshot;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDRuleSpec;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GovernancePolicyApiMutationService {
    private static final Logger LOG=LoggerFactory.getLogger(GovernancePolicyApiMutationService.class);
    private static final String RESOURCE_TYPE="governance-policy-version";

    private final AdministrativeAuthorizationService authorization;
    private final GovernancePolicyService policies;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    GovernancePolicyApiMutationService(
            AdministrativeAuthorizationService authorization,
            GovernancePolicyService policies,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization=Objects.requireNonNull(authorization);
        this.policies=Objects.requireNonNull(policies);
        this.idempotency=Objects.requireNonNull(idempotency);
        this.transactions=Objects.requireNonNull(transactions);
        this.audit=Objects.requireNonNull(audit);
        this.ids=Objects.requireNonNull(ids);
    }

    PolicySnapshot createDraft(
            AuthenticatedAdministrativeActor actor,
            PolicyDecision defaultDecision,
            List<SoDRuleSpec> rules,
            PlanSpec approvalPlan,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return execute(
                actor,
                AdministrativePermissions.GOVERNANCE_POLICY_CREATE,
                "api.governance.policy.create.v1",
                "governance-policy:create",
                key,
                fingerprint,
                now,
                correlationId,
                () -> {
                    var version=policies.createDraft(
                            actor.tenant(),PolicyKind.ACCESS_REQUEST,defaultDecision,rules,approvalPlan,now);
                    return policies.findSnapshot(actor.tenant(),version.id()).orElseThrow();
                });
    }

    PolicySnapshot markReady(
            AuthenticatedAdministrativeActor actor,
            UUID versionId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transition(actor,versionId,expectedRevision,
                AdministrativePermissions.GOVERNANCE_POLICY_READY,
                "api.governance.policy.ready.v1","governance-policy:ready",
                key,fingerprint,now,correlationId,
                () -> policies.markReady(actor.tenant(),versionId,expectedRevision,now));
    }

    PolicySnapshot activate(
            AuthenticatedAdministrativeActor actor,
            UUID versionId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transition(actor,versionId,expectedRevision,
                AdministrativePermissions.GOVERNANCE_POLICY_ACTIVATE,
                "api.governance.policy.activate.v1","governance-policy:activate",
                key,fingerprint,now,correlationId,
                () -> policies.activate(actor.tenant(),versionId,expectedRevision,now));
    }

    PolicySnapshot cancel(
            AuthenticatedAdministrativeActor actor,
            UUID versionId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transition(actor,versionId,expectedRevision,
                AdministrativePermissions.GOVERNANCE_POLICY_CANCEL,
                "api.governance.policy.cancel.v1","governance-policy:cancel",
                key,fingerprint,now,correlationId,
                () -> policies.cancelDraft(actor.tenant(),versionId,expectedRevision,now));
    }

    private PolicySnapshot transition(
            AuthenticatedAdministrativeActor actor,
            UUID versionId,
            long expectedRevision,
            AdministrativePermission permission,
            String namespace,
            String action,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            Supplier<?> transition) {
        return execute(actor,permission,namespace,action,key,fingerprint,now,correlationId,() -> {
            transition.get();
            return policies.findSnapshot(actor.tenant(),versionId).orElseThrow();
        });
    }

    private PolicySnapshot execute(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            String namespace,
            String action,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            Supplier<PolicySnapshot> command) {
        try {
            PolicySnapshot result=transactions.required(() -> {
                require(actor,permission,correlationId,now);
                Registration registration=idempotency.register(
                        actor.tenant(),namespace,key,fingerprint,now,null);
                if(registration.kind()==RegistrationKind.REPLAY){
                    requireReplay(registration,correlationId);
                    return policies.findSnapshot(actor.tenant(),registration.resourceId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "idempotent governance policy result no longer exists"));
                }
                PolicySnapshot snapshot;
                try {
                    snapshot=command.get();
                } catch (StaleWriteException stale) {
                    throw GovernanceApiException.conflict(
                            correlationId,"stale_revision","The supplied policy revision is stale.");
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    throw GovernanceApiException.validation(
                            correlationId,"policy","invalid_policy",
                            "Governance policy does not satisfy its current typed lifecycle/invariants.");
                }
                idempotency.complete(
                        actor.tenant(),namespace,key,fingerprint,
                        RESOURCE_TYPE,snapshot.version().id(),now);
                return snapshot;
            });
            audit(actor,result.version().id(),action,AuditOutcome.SUCCESS,now,correlationId);
            return result;
        } catch(RuntimeException failure){
            audit(actor,null,action,auditOutcome(failure),now,correlationId);
            throw failure;
        }
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            UUID correlationId,
            Instant now) {
        if(!authorization.authorize(
                actor,permission,AdministrativeResource.collection("governance-policy"),now).allowed()){
            throw GovernanceApiException.forbidden(correlationId);
        }
    }

    private static void requireReplay(Registration registration,UUID correlationId){
        if(!"COMPLETED".equals(registration.operationState())){
            throw GovernanceApiException.conflict(
                    correlationId,"idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if(!RESOURCE_TYPE.equals(registration.resourceType()) || registration.resourceId()==null){
            throw new IllegalStateException("completed governance policy idempotency result is invalid");
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure){
        return failure instanceof GovernanceApiException api
                        && api.status()==org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED : AuditOutcome.FAILURE;
    }

    private void audit(
            AuthenticatedAdministrativeActor actor,
            UUID versionId,
            String action,
            AuditOutcome outcome,
            Instant now,
            UUID correlationId){
        try{
            audit.append(actor.tenant(),new AuditRecordDraft(
                    ids.nextId(),now,actor.identityId(),action,RESOURCE_TYPE,versionId,outcome,correlationId,null));
        }catch(RuntimeException auditFailure){
            LOG.warn("Governance policy AuditRecord append failed; correlationId={} action={} outcome={}",
                    correlationId,action,outcome);
        }
    }
}
