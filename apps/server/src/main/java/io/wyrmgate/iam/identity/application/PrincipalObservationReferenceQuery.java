package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity-owned semantic validation used by Integration Principal observation mapping.
 *
 * <p>The provider identifier never crosses this boundary. Integration supplies an exact governed
 * Principal ID and ApplicationTarget ID; Identity validates only its own authoritative state.</p>
 */
public interface PrincipalObservationReferenceQuery {

    Validation validate(
            TenantContext tenant,
            UUID principalId,
            UUID applicationTargetId);

    enum Validation {
        VALID,
        NOT_FOUND,
        TARGET_MISMATCH
    }

    record Reference(
            UUID principalId,
            UUID applicationTargetId,
            long revision) {
        public Reference {
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(applicationTargetId, "applicationTargetId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }
}
