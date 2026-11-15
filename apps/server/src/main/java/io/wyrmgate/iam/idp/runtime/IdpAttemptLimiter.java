package io.wyrmgate.iam.idp.runtime;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Shared technical admission budget; does not lock or mutate a business account. */
@FunctionalInterface
public interface IdpAttemptLimiter {
    enum Kind {
        TENANT_LOGIN(60, 120), PRINCIPAL_LOGIN(300, 10), INTERACTION(60, 120);
        private final int seconds;
        private final int limit;
        Kind(int seconds, int limit) { this.seconds = seconds; this.limit = limit; }
        public int seconds() { return seconds; }
        public int limit() { return limit; }
    }
    boolean acquire(TenantContext tenant, Kind kind, UUID subjectId);
}
