package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.EvidenceSnapshot;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceSnapshotRepository {

    boolean insertIfAbsent(TenantContext tenant, EvidenceSnapshot snapshot);

    Optional<EvidenceSnapshot> findById(TenantContext tenant, UUID id);

    List<EvidenceSnapshot> findPage(
            TenantContext tenant,
            String snapshotType,
            String subjectResourceType,
            UUID subjectResourceId,
            UUID correlationId,
            Instant afterOccurredAt,
            UUID afterId,
            int limit);
}
