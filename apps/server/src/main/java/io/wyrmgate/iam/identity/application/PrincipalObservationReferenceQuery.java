package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
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

}