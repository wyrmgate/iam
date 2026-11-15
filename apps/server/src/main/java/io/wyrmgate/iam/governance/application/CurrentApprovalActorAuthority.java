package io.wyrmgate.iam.governance.application;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class CurrentApprovalActorAuthority implements ApprovalActorAuthority {
    private final IdentityAccessReferenceQuery identities;
    private final AccessRequestRepository requests;
    private final GovernanceExceptionRepository exceptions;
    private final LifecycleAccessApprovalCandidateRepository candidates;
    public CurrentApprovalActorAuthority(IdentityAccessReferenceQuery identities, AccessRequestRepository requests,
            GovernanceExceptionRepository exceptions, LifecycleAccessApprovalCandidateRepository candidates) {
        this.identities=Objects.requireNonNull(identities);
        this.requests=Objects.requireNonNull(requests);
        this.exceptions=Objects.requireNonNull(exceptions);
        this.candidates=Objects.requireNonNull(candidates);
    }
    @Override
    public void requireCurrent(TenantContext tenant, ApprovalCase approvalCase, UUID actor) {
        if(identities.accessStatus(tenant,actor).status()!=IdentityAccessReferenceQuery.AccessStatus.ACCESS_ELIGIBLE)
            throw new ApprovalCommandException("approval_actor_ineligible","The resolved approver is not currently eligible.");
        UUID beneficiary=switch(approvalCase.subjectKind()){
            case ACCESS_REQUEST_ITEM -> {
                var item=requests.findItem(tenant,approvalCase.subjectId()).orElseThrow(CurrentApprovalActorAuthority::missing);
                yield requests.findRequest(tenant,item.accessRequestId()).orElseThrow(CurrentApprovalActorAuthority::missing).beneficiaryIdentityId();
            }
            case GOVERNANCE_EXCEPTION -> exceptions.findById(tenant,approvalCase.subjectId()).orElseThrow(CurrentApprovalActorAuthority::missing).subjectIdentityId();
            case LIFECYCLE_ACCESS_CANDIDATE -> candidates.findById(tenant,approvalCase.subjectId()).orElseThrow(CurrentApprovalActorAuthority::missing).identityId();
            // Elevation owner already excludes both initiator and beneficiary during plan resolution.
            case ADMINISTRATIVE_ELEVATION, ROLE_VERSION_ACTIVATION -> approvalCase.initiatorIdentityId();
        };
        if(actor.equals(beneficiary)) throw new ApprovalCommandException("approval_self_decision_forbidden","Self-approval by the beneficiary is denied.");
    }
    private static ApprovalCommandException missing(){
        return new ApprovalCommandException("approval_subject_unavailable","The governed approval subject is unavailable.");
    }
}
