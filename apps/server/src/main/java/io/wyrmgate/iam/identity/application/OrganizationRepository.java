package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Organization;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned persistence port for tenant-local Organization structure. */
public interface OrganizationRepository {

    void insert(TenantContext tenant, Organization organization);

    Optional<Organization> findById(TenantContext tenant, UUID organizationId);

    /**
     * Serializes Organization hierarchy mutations within one Tenant without locking foreign
     * capability state. Implementations may use transaction-scoped technical locking.
     */
    void lockHierarchyMutation(TenantContext tenant);

    Organization updateMetadata(
            TenantContext tenant,
            UUID organizationId,
            String name,
            String organizationType,
            long expectedRevision,
            Instant now);

    Organization updateParent(
            TenantContext tenant,
            UUID organizationId,
            UUID parentId,
            long expectedRevision,
            Instant now);

    boolean wouldCreateCycle(
            TenantContext tenant,
            UUID organizationId,
            UUID proposedParentId);
}
