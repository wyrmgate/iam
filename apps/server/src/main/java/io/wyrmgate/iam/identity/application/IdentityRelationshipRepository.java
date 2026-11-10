package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.ManagerRelationship;
import io.wyrmgate.iam.identity.domain.OrganizationMembership;
import io.wyrmgate.iam.identity.domain.OwnershipRelationship;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Typed persistence port for first-class Identity relationships. */
public interface IdentityRelationshipRepository {

    boolean sourceRecordAcceptedForIdentity(
            TenantContext tenant, UUID sourceRecordId, UUID identityId);

    void insertManagerRelationship(TenantContext tenant, ManagerRelationship relationship);

    Optional<ManagerRelationship> findManagerRelationship(
            TenantContext tenant, UUID relationshipId);

    ManagerRelationship endManagerRelationship(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now);

    void insertOrganizationMembership(TenantContext tenant, OrganizationMembership membership);

    Optional<OrganizationMembership> findOrganizationMembership(
            TenantContext tenant, UUID relationshipId);

    OrganizationMembership endOrganizationMembership(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now);

    void insertOwnershipRelationship(TenantContext tenant, OwnershipRelationship relationship);

    Optional<OwnershipRelationship> findOwnershipRelationship(
            TenantContext tenant, UUID relationshipId);

    OwnershipRelationship endOwnershipRelationship(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now);
}
