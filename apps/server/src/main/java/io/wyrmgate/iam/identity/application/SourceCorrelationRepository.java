package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
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
            String checkpointToken,
            String partialReason,
            Instant completedAt);

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

    record LinkReplacement(IdentityLink link, boolean changed) {
    }
}
