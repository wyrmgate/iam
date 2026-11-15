package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.GovernanceFindingRepository.GovernanceFinding;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Positive conditions and exact resolutions; page omission is not resolution evidence. */
public interface GovernanceObservationReporter {
    void observe(TenantContext tenant, UUID connectorBindingId,
                 ObservationCondition condition, Instant observedAt);
    void resolve(TenantContext tenant, GovernanceFinding finding, Instant observedAt);

    record ObservationCondition(String findingType, String subjectKind,
                                String providerStableId, String relatedProviderStableId, FindingContext context) {
        public ObservationCondition(String type,String subject,String provider,String related){
            this(type,subject,provider,related,FindingContext.EMPTY);
        }
        public ObservationCondition {
            java.util.Objects.requireNonNull(context).validate(FindingContext.Type.valueOf(findingType),FindingContext.Subject.valueOf(subjectKind));
            if("GOVERNANCE_EXCEPTION".equals(subjectKind)){
                if(providerStableId!=null || relatedProviderStableId!=null) throw new IllegalArgumentException("exception finding has no provider subject");
            }else if(providerStableId==null || providerStableId.isBlank()) throw new IllegalArgumentException("observation finding requires exact provider witness");
            if("OBSERVED_SOD_CONFLICT".equals(findingType) && (relatedProviderStableId==null || relatedProviderStableId.isBlank()))
                throw new IllegalArgumentException("observed SoD requires exact counterpart witness");
        }
    }
}
