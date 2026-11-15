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

    /** Bounded stable-ID enumeration for Identity-scoped observation comparisons. */
    default java.util.List<UUID> principalIds(TenantContext tenant,UUID identityId,UUID after,int limit){
        throw new IllegalStateException("principal observation subject query unavailable");
    }

    enum Validation {
        VALID,
        NOT_FOUND,
        TARGET_MISMATCH
    }

}