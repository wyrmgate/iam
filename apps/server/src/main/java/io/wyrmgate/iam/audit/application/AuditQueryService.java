package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditPage;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditPagePosition;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AuditQueryService {
    public static final int MAX_PAGE_SIZE = 200;

    private final AuditRecordRepository repository;

    public AuditQueryService(AuditRecordRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<AuditRecord> findById(TenantContext tenant, UUID recordId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(recordId, "recordId");
        Optional<AuditRecord> record = repository.findById(tenant, recordId);
        record.ifPresent(value -> requireIntegrity(tenant, value));
        return record;
    }

    private static void requireIntegrity(TenantContext tenant, AuditRecord record) {
        if (!AuditRecordIntegrity.verifies(tenant, record)) {
            throw new AuditIntegrityException(record.id());
        }
    }

    public AuditPage list(
            TenantContext tenant,
            AuditFilter filter,
            AuditPagePosition after,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(filter, "filter");
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        List<AuditRecord> fetched = repository.findPage(tenant, filter, after, limit + 1);
        fetched.forEach(record -> requireIntegrity(tenant, record));
        boolean hasMore = fetched.size() > limit;
        List<AuditRecord> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        AuditPagePosition next = hasMore
                ? new AuditPagePosition(
                        items.get(items.size() - 1).occurredAt(),
                        items.get(items.size() - 1).id())
                : null;
        return new AuditPage(items, next);
    }
}
