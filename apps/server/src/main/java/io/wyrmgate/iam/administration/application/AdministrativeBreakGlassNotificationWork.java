package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Fenced technical delivery work for one SECURITY_NOTIFICATION obligation. */
public record AdministrativeBreakGlassNotificationWork(
        TenantContext tenant,
        UUID obligationId,
        int attemptCount,
        AdministrativeBreakGlassOperation operation) {

    public AdministrativeBreakGlassNotificationWork {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(obligationId, "obligationId");
        Objects.requireNonNull(operation, "operation");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive after claim");
        }
    }
}
