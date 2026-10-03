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
import io.wyrmgate.iam.governance.application.GovernanceExceptionService;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.GovernanceException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GovernanceExceptionApiMutationService {
    private static final Logger LOG=LoggerFactory.getLogger(GovernanceExceptionApiMutationService.class);
    private static final String RESOURCE_TYPE="governance-exception";

    private final AdministrativeAuthorizationService authorization;
    private final GovernanceExceptionService exceptions;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    GovernanceExceptionApiMutationService(
            AdministrativeAuthorizationService authorization,
            GovernanceExceptionService exceptions,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids){
        this.authorization=Objects.requireNonNull(authorization);
        this.exceptions=Objects.requireNonNull(exceptions);
        this.idempotency=Objects.requireNonNull(idempotency);
        this.transactions=Objects.requireNonNull(transactions);
        this.audit=Objects.requireNonNull(audit);
        this.ids=Objects.requireNonNull(ids);
    }

    GovernanceException request(
            AuthenticatedAdministrativeActor actor,
            UUID subjectIdentityId,
            UUID sodRuleId,
            String businessReason,
            Instant validFrom,
            Instant validUntil,
            UUID predecessorExceptionId,
            PlanSpec approvalPlan,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId){
        return execute(
                actor,
                AdministrativePermissions.GOVERNANCE_EXCEPTION_CREATE,
                AdministrativeResource.collection("governance-exception"),
                "api.governance.exception.create.v1",
                "governance-exception:create",
                key,
                fingerprint,
                now,
                correlationId,
                () -> exceptions.request(
                        actor.tenant(),subjectIdentityId,sodRuleId,actor.identityId(),
                        businessReason,validFrom,validUntil,predecessorExceptionId,approvalPlan,now));
    }

    GovernanceException revoke(
            AuthenticatedAdministrativeActor actor,
            UUID exceptionId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId){
        return execute(
                actor,
                AdministrativePermissions.GOVERNANCE_EXCEPTION_REVOKE,
                new AdministrativeResource("governance-exception",exceptionId),
                "api.governance.exception.revoke.v1",
                "governance-exception:revoke",
                key,
                fingerprint,
                now,
                correlationId,
                () -> exceptions.revoke(actor.tenant(),exceptionId,expectedRevision,now));
    }

    private GovernanceException execute(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            String namespace,
            String action,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            java.util.function.Supplier<GovernanceException> command){
        try{
            GovernanceException result=transactions.required(() -> {
                require(actor,permission,resource,now,correlationId);
                Registration registration=idempotency.register(
                        actor.tenant(),namespace,key,fingerprint,now,null);
                if(registration.kind()==RegistrationKind.REPLAY){
                    requireReplay(registration,correlationId);
                    return exceptions.find(actor.tenant(),registration.resourceId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "idempotent GovernanceException result no longer exists"));
                }
                GovernanceException value;
                try{
                    value=command.get();
                }catch(StaleWriteException stale){
                    throw GovernanceApiException.conflict(
                            correlationId,"stale_revision","The supplied GovernanceException revision is stale.");
                }catch(IllegalArgumentException | IllegalStateException invalid){
                    throw GovernanceApiException.validation(
                            correlationId,"exception","invalid_exception",
                            "GovernanceException does not satisfy current typed scope/lifecycle requirements.");
                }
                idempotency.complete(
                        actor.tenant(),namespace,key,fingerprint,RESOURCE_TYPE,value.id(),now);
                return value;
            });
            record(actor,result.id(),action,AuditOutcome.SUCCESS,now,correlationId);
            return result;
        }catch(RuntimeException failure){
            record(actor,null,action,auditOutcome(failure),now,correlationId);
            throw failure;
        }
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId){
        if(!authorization.authorize(actor,permission,resource,now).allowed()){
            throw GovernanceApiException.forbidden(correlationId);
        }
    }

    private static void requireReplay(Registration registration,UUID correlationId){
        if(!"COMPLETED".equals(registration.operationState())){
            throw GovernanceApiException.conflict(
                    correlationId,"idempotency_in_progress","The same idempotency key is already being processed.");
        }
        if(!RESOURCE_TYPE.equals(registration.resourceType()) || registration.resourceId()==null){
            throw new IllegalStateException("completed GovernanceException idempotency result is invalid");
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure){
        return failure instanceof GovernanceApiException api
                        && api.status()==org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED : AuditOutcome.FAILURE;
    }

    private void record(
            AuthenticatedAdministrativeActor actor,
            UUID exceptionId,
            String action,
            AuditOutcome outcome,
            Instant now,
            UUID correlationId){
        try{
            audit.append(actor.tenant(),new AuditRecordDraft(
                    ids.nextId(),now,actor.identityId(),action,RESOURCE_TYPE,exceptionId,outcome,correlationId,null));
        }catch(RuntimeException auditFailure){
            LOG.warn("GovernanceException AuditRecord append failed; correlationId={} action={} outcome={}",
                    correlationId,action,outcome);
        }
    }
}
