package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.IdentityMergeOperation;
import io.wyrmgate.iam.identity.domain.IdentitySplitOperation;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned persistence contract for explicit merge/split operations and relationship reassignment. */
public interface IdentityMergeSplitRepository {

    List<Identity> lockIdentities(TenantContext tenant, List<UUID> identityIds);

    List<IdentityLink> findActiveLinksByIdentity(TenantContext tenant, UUID identityId);

    List<Principal> findPrincipalsByIdentity(TenantContext tenant, UUID identityId);

    Principal reassignPrincipal(
            TenantContext tenant,
            UUID principalId,
            UUID fromIdentityId,
            UUID toIdentityId,
            long expectedRevision,
            Instant now);

    void insertMergeOperation(TenantContext tenant, IdentityMergeOperation operation);

    void insertSplitOperation(TenantContext tenant, IdentitySplitOperation operation);

    Optional<IdentityMergeOperation> findMergeOperation(
            TenantContext tenant, UUID operationId);

    Optional<IdentitySplitOperation> findSplitOperation(
            TenantContext tenant, UUID operationId);

    boolean hasCompletedMergeForAbsorbedIdentity(TenantContext tenant, UUID absorbedIdentityId);
}
