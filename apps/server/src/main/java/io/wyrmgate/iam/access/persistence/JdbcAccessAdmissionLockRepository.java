package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessAdmissionCoordinator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAccessAdmissionLockRepository implements AccessAdmissionCoordinator.LockRepository {
    private final JdbcTemplate jdbc;

    public JdbcAccessAdmissionLockRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public void lock(TenantContext tenant, UUID identityId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Access admission lock requires local transaction");
        jdbc.update("""
                INSERT INTO access.admission_lock (tenant_id, identity_id) VALUES (?, ?)
                ON CONFLICT (tenant_id, identity_id) DO NOTHING
                """, tenant.tenantId(), identityId);
        jdbc.queryForObject("""
                SELECT identity_id FROM access.admission_lock
                WHERE tenant_id = ? AND identity_id = ? FOR UPDATE
                """, UUID.class, tenant.tenantId(), identityId);
    }
}
