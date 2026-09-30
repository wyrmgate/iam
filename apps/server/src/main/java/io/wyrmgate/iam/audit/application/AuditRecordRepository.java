package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditPagePosition;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditRecordRepository {
    boolean insertIfAbsent(TenantContext tenant, AuditRecord record);
    Optional<AuditRecord> findById(TenantContext tenant, UUID recordId);
    List<AuditRecord> findPage(
            TenantContext tenant,
            AuditFilter filter,
            AuditPagePosition after,
            int limit);
}
