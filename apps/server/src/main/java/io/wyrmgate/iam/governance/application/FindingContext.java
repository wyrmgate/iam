package io.wyrmgate.iam.governance.application;
import java.util.UUID;

/** Typed normalized evidence references; never provider payload or a grant of authority. */
public record FindingContext(UUID identityId,UUID principalId,UUID entitlementId,UUID applicationTargetId,
                             UUID sodRuleId,UUID relatedBindingId,UUID relatedPrincipalId,
                             UUID relatedEntitlementId,UUID exceptionId) {
    public static final FindingContext EMPTY=new FindingContext(null,null,null,null,null,null,null,null,null);
    public enum Type { UNMAPPED_PROVIDER_ENTITLEMENT, UNMAPPED_PROVIDER_GRANT_ENTITLEMENT,
        UNRESOLVED_PROVIDER_GRANT_PRINCIPAL, EXCESS_OBSERVED_GRANT, OBSERVED_SOD_CONFLICT, EXPIRED_EXCEPTION_EXPOSURE }
    public enum Subject { OBSERVED_ENTITLEMENT, OBSERVED_GRANT, GOVERNANCE_EXCEPTION }
    public enum State { OPEN, RESOLVED }

    public static FindingContext excess(UUID identity,UUID principal,UUID entitlement,UUID target){
        return new FindingContext(identity,principal,entitlement,target,null,null,null,null,null);
    }
    public static FindingContext sod(UUID identity,UUID principal,UUID entitlement,UUID target,UUID rule,
                                     UUID relatedBinding,UUID relatedPrincipal,UUID relatedEntitlement){
        return new FindingContext(identity,principal,entitlement,target,rule,relatedBinding,relatedPrincipal,relatedEntitlement,null);
    }
    public static FindingContext exception(UUID identity,UUID rule,UUID exception){
        return new FindingContext(identity,null,null,null,rule,null,null,null,exception);
    }
    public void validate(Type type,Subject subject){
        boolean valid=switch(type){
            case UNMAPPED_PROVIDER_ENTITLEMENT -> subject==Subject.OBSERVED_ENTITLEMENT && equals(EMPTY);
            case UNMAPPED_PROVIDER_GRANT_ENTITLEMENT, UNRESOLVED_PROVIDER_GRANT_PRINCIPAL -> subject==Subject.OBSERVED_GRANT && equals(EMPTY);
            case EXCESS_OBSERVED_GRANT -> subject==Subject.OBSERVED_GRANT && principalId!=null && entitlementId!=null
                    && applicationTargetId!=null && sodRuleId==null && relatedBindingId==null && relatedPrincipalId==null
                    && relatedEntitlementId==null && exceptionId==null;
            case OBSERVED_SOD_CONFLICT -> subject==Subject.OBSERVED_GRANT && identityId!=null && principalId!=null
                    && entitlementId!=null && applicationTargetId!=null && sodRuleId!=null && relatedBindingId!=null
                    && relatedPrincipalId!=null && relatedEntitlementId!=null && !entitlementId.equals(relatedEntitlementId) && exceptionId==null;
            case EXPIRED_EXCEPTION_EXPOSURE -> subject==Subject.GOVERNANCE_EXCEPTION && identityId!=null && sodRuleId!=null
                    && exceptionId!=null && principalId==null && entitlementId==null && applicationTargetId==null
                    && relatedBindingId==null && relatedPrincipalId==null && relatedEntitlementId==null;
        };
        if(!valid) throw new IllegalArgumentException("invalid typed finding context");
    }
}
