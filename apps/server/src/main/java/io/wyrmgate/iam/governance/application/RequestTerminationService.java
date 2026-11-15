package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.AccessAdmissionCoordinator;
import io.wyrmgate.iam.access.application.AccessIntentApplicationQuery;
import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.governance.application.AccessRequestModels.*;
import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Governance terminates pending intent, never revokes committed Access authority. */
public final class RequestTerminationService {
    private final AccessRequestRepository requests;
    private final ApprovalRepository approvals;
    private final AccessIntentApplicationQuery replay;
    private final AccessAdmissionCoordinator coordinator;

    public RequestTerminationService(AccessRequestRepository requests, ApprovalRepository approvals,
            AccessIntentApplicationQuery replay, AccessAdmissionCoordinator coordinator){
        this.requests=Objects.requireNonNull(requests);
        this.approvals=Objects.requireNonNull(approvals);
        this.replay=Objects.requireNonNull(replay);
        this.coordinator=Objects.requireNonNull(coordinator);
    }

    public RequestDetail cancel(TenantContext tenant, UUID requestId, long expectedRevision, Instant now){
        var initial=requests.findRequest(tenant,requestId).orElseThrow();
        return coordinator.execute(tenant,initial.beneficiaryIdentityId(),permit->{
            var request=requests.findRequest(tenant,requestId).orElseThrow();
            if(request.revision()!=expectedRevision) throw new StaleWriteException("access-request",requestId,expectedRevision);
            if(request.state()!=RequestState.DRAFT && request.state()!=RequestState.SUBMITTED && request.state()!=RequestState.IN_PROGRESS)
                throw new AccessRequestCommandException("access_request_not_cancellable","Request is not cancellable.");
            for(var item:requests.findItems(tenant,requestId))
                if(pending(item.state())) terminate(tenant,request,item,ItemState.CANCELLED,"request_cancelled",now);
            var cancelled=requests.updateRequestState(tenant,requestId,RequestState.CANCELLED,expectedRevision,now,request.submittedAt());
            return new RequestDetail(cancelled,requests.findItems(tenant,requestId));
        });
    }

    public boolean expireIfDue(TenantContext tenant, UUID itemId, Instant now){
        var initial=requests.findItem(tenant,itemId).orElse(null);
        if(initial==null) return false;
        var request=requests.findRequest(tenant,initial.accessRequestId()).orElseThrow();
        return coordinator.execute(tenant,request.beneficiaryIdentityId(),permit->{
            var item=requests.findItem(tenant,itemId).orElseThrow();
            if(item.state()==ItemState.DRAFT || !pending(item.state()) || item.validUntil()==null || item.validUntil().isAfter(now)) return false;
            terminate(tenant,request,item,ItemState.EXPIRED,"validity_already_ended",now);
            return true;
        });
    }

    private void terminate(TenantContext tenant, AccessRequest request, RequestItem item, ItemState terminal, String code, Instant now){
        var requested=new AccessIntentCommand.RequestedAccess(item.id(),request.beneficiaryIdentityId(),
                AccessIntentCommand.TargetKind.valueOf(item.targetKind().name()),item.roleId(),item.entitlementId(),
                AccessIntentCommand.PrincipalConstraintKind.valueOf(item.principalConstraintKind().name()),
                item.specificPrincipalId(),item.validFrom(),item.validUntil());
        var applied=replay.findApplied(tenant,requested).orElse(null);
        if(applied!=null){
            requests.updateItemState(tenant,item.id(),ItemState.APPLIED,item.approvalCaseId(),applied.accessAssignmentId(),
                    item.evaluationCode(),item.revision(),now);
            return;
        }
        if(item.approvalCaseId()!=null){
            var approval=approvals.findCase(tenant,item.approvalCaseId()).orElseThrow();
            if(approval.subjectKind()!=SubjectKind.ACCESS_REQUEST_ITEM || !approval.subjectId().equals(item.id()))
                throw new IllegalStateException("RequestItem approval subject mismatch");
            if(approval.state()==CaseState.PENDING)
                approvals.updateCase(tenant,approval.id(),CaseState.CANCELLED,approval.currentStageOrdinal(),approval.revision(),now,now);
        }
        requests.updateItemState(tenant,item.id(),terminal,item.approvalCaseId(),null,code,item.revision(),now);
    }

    private static boolean pending(ItemState state){
        return switch(state){
            case DRAFT,SUBMITTED,EVALUATING,PENDING_APPROVAL,AUTHORIZED -> true;
            default -> false;
        };
    }
}
