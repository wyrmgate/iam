package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.SourceAbsenceInference;
import io.wyrmgate.iam.identity.domain.SourceAbsencePolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceAbsenceTrust;
import io.wyrmgate.iam.identity.domain.SourceCorrelationPolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
import io.wyrmgate.iam.identity.domain.SourceLifecyclePolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.identity.domain.SourceSystem;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned persistence port for source definition, import observation and correlation history. */
public interface SourceCorrelationRepository {

    void insertSourceSystem(TenantContext tenant, SourceSystem sourceSystem);

    Optional<SourceSystem> findSourceSystem(TenantContext tenant, UUID sourceSystemId);

    void insertImportRun(TenantContext tenant, SourceImportRun run);

    SourceImportRun completeImportRun(
            TenantContext tenant,
            UUID runId,
            SourceImportCompleteness completeness,
            SourceAbsenceTrust absenceTrust,
            String absenceTrustReason,
            String checkpointToken,
            String partialReason,
            Instant completedAt);

    SourceAbsenceTrust findImportAbsenceTrust(TenantContext tenant, UUID runId);

    boolean hasImportStartedAfter(
            TenantContext tenant, UUID sourceSystemId, Instant startedAt);

    Optional<SourceImportRun> findImportRun(TenantContext tenant, UUID runId);

    SourceRecord upsertPositiveObservation(
            TenantContext tenant,
            UUID sourceSystemId,
            UUID importRunId,
            String nativeKey,
            String observedAttributesJson,
            Instant sourceUpdatedAt,
            Instant observedAt);

    Optional<SourceRecord> findSourceRecord(TenantContext tenant, UUID sourceRecordId);

    Optional<SourceRecord> findSourceRecordForUpdate(TenantContext tenant, UUID sourceRecordId);

    void lockTenantForCorrelation(TenantContext tenant);

    Optional<SourceRecord> findSourceRecordByNativeKey(
            TenantContext tenant,
            UUID sourceSystemId,
            String nativeKey);

    LinkReplacement replaceAcceptedLink(
            TenantContext tenant,
            UUID sourceRecordId,
            UUID identityId,
            String correlationReason,
            Instant linkedAt,
            UUID correlationId,
            UUID causationId,
            UUID newLinkId);

    Optional<IdentityLink> findActiveAcceptedLink(TenantContext tenant, UUID sourceRecordId);

    SourceCorrelationPolicyVersion replaceActiveCorrelationPolicy(
            TenantContext tenant,
            UUID sourceSystemId,
            UUID matchAttributeDefinitionVersionId,
            UUID matchMappingVersionId,
            boolean createIdentityOnNoMatch,
            IdentityType createdIdentityType,
            String displayNameSourcePath,
            Instant activatedAt,
            UUID newPolicyId);

    Optional<SourceCorrelationPolicyVersion> findActiveCorrelationPolicy(
            TenantContext tenant, UUID sourceSystemId);

    SourceLifecyclePolicyVersion replaceActiveLifecyclePolicy(
            TenantContext tenant,
            UUID sourceSystemId,
            String sourcePath,
            java.util.List<SourceLifecyclePolicyVersion.Rule> rules,
            Instant activatedAt,
            UUID newPolicyId);

    Optional<SourceLifecyclePolicyVersion> findActiveLifecyclePolicy(
            TenantContext tenant, UUID sourceSystemId);

    SourceAbsencePolicyVersion replaceActiveAbsencePolicy(
            TenantContext tenant,
            UUID sourceSystemId,
            int maxInferredTransitions,
            Instant activatedAt,
            UUID newPolicyId);

    Optional<SourceAbsencePolicyVersion> findActiveAbsencePolicy(
            TenantContext tenant, UUID sourceSystemId);

    SourceAbsenceInference startAbsenceInferenceIfAbsent(
            TenantContext tenant, SourceAbsenceInference candidate);

    Optional<SourceAbsenceInference> findAbsenceInferenceById(
            TenantContext tenant, UUID inferenceId);

    java.util.List<SourceRecord> findAbsentSourceRecordPage(
            TenantContext tenant,
            UUID sourceSystemId,
            UUID importRunId,
            Instant runStartedAt,
            Instant afterFirstObservedAt,
            UUID afterSourceRecordId,
            int limit);

    SourceAbsenceInference recordAbsenceInferenceProgress(
            TenantContext tenant,
            UUID inferenceId,
            Instant afterFirstObservedAt,
            UUID afterSourceRecordId,
            long processedDelta,
            long transitionDelta,
            SourceAbsenceInference.State state,
            long expectedRevision,
            Instant now);

    void recordAbsenceTransitionEvidence(
            TenantContext tenant,
            UUID evidenceId,
            UUID sourceSystemId,
            UUID sourceRecordId,
            UUID identityLinkId,
            UUID identityId,
            UUID importRunId,
            UUID inferenceId,
            long preIdentityRevision,
            long postIdentityRevision,
            Instant transitionedAt);

    boolean hasCurrentAbsenceTransitionEvidence(
            TenantContext tenant,
            UUID sourceRecordId,
            UUID identityLinkId,
            UUID identityId,
            long currentIdentityRevision);

    record LinkReplacement(IdentityLink link, boolean changed, UUID previousIdentityId) {
    }
}
