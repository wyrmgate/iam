package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.ManagerRelationship;
import io.wyrmgate.iam.identity.domain.OrganizationMembership;
import io.wyrmgate.iam.identity.domain.OwnershipRelationship;
import io.wyrmgate.iam.identity.domain.RelationshipProvenance;
import io.wyrmgate.iam.identity.domain.RelationshipProvenanceKind;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned commands for manager, Organization membership and accountable ownership. */
public final class IdentityRelationshipCommandService {

    private final IdentityRepository identities;
    private final OrganizationRepository organizations;
    private final IdentityRelationshipRepository relationships;
    private final IdGenerator idGenerator;
    private final TransactionExecutor transactions;

    public IdentityRelationshipCommandService(
            IdentityRepository identities,
            OrganizationRepository organizations,
            IdentityRelationshipRepository relationships,
            IdGenerator idGenerator,
            TransactionExecutor transactions) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.relationships = Objects.requireNonNull(relationships, "relationships");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public ManagerRelationship assignManager(
            TenantContext tenant,
            UUID subjectIdentityId,
            UUID managerIdentityId,
            Instant validFrom,
            Instant validUntil,
            RelationshipProvenance provenance,
            Instant now) {
        ManagerRelationship desired = new ManagerRelationship(
                idGenerator.nextId(),
                subjectIdentityId,
                managerIdentityId,
                validFrom,
                validUntil,
                provenance,
                1,
                now,
                now);
        return transactions.required(() -> {
            requireIdentity(tenant, subjectIdentityId);
            requireIdentity(tenant, managerIdentityId);
            requireProvenance(tenant, subjectIdentityId, provenance);
            relationships.insertManagerRelationship(tenant, desired);
            return relationships.findManagerRelationship(tenant, desired.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created manager relationship could not be reloaded"));
        });
    }

    public OrganizationMembership addOrganizationMembership(
            TenantContext tenant,
            UUID subjectIdentityId,
            UUID organizationId,
            Instant validFrom,
            Instant validUntil,
            RelationshipProvenance provenance,
            Instant now) {
        OrganizationMembership desired = new OrganizationMembership(
                idGenerator.nextId(),
                subjectIdentityId,
                organizationId,
                validFrom,
                validUntil,
                provenance,
                1,
                now,
                now);
        return transactions.required(() -> {
            requireIdentity(tenant, subjectIdentityId);
            if (organizations.findById(tenant, organizationId).isEmpty()) {
                throw new IllegalArgumentException("Organization does not exist");
            }
            requireProvenance(tenant, subjectIdentityId, provenance);
            relationships.insertOrganizationMembership(tenant, desired);
            return relationships.findOrganizationMembership(tenant, desired.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created Organization membership could not be reloaded"));
        });
    }

    public OwnershipRelationship assignOwner(
            TenantContext tenant,
            UUID subjectIdentityId,
            UUID ownerIdentityId,
            Instant validFrom,
            Instant validUntil,
            RelationshipProvenance provenance,
            Instant now) {
        OwnershipRelationship desired = new OwnershipRelationship(
                idGenerator.nextId(),
                subjectIdentityId,
                ownerIdentityId,
                validFrom,
                validUntil,
                provenance,
                1,
                now,
                now);
        return transactions.required(() -> {
            requireIdentity(tenant, subjectIdentityId);
            requireIdentity(tenant, ownerIdentityId);
            requireProvenance(tenant, subjectIdentityId, provenance);
            relationships.insertOwnershipRelationship(tenant, desired);
            return relationships.findOwnershipRelationship(tenant, desired.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created ownership relationship could not be reloaded"));
        });
    }

    public ManagerRelationship endManagerRelationship(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            ManagerRelationship current = relationships.findManagerRelationship(tenant, relationshipId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "manager relationship does not exist"));
            validateEnd(current.validFrom(), current.validUntil(), validUntil, current.revision(),
                    expectedRevision, relationshipId, "manager relationship");
            return relationships.endManagerRelationship(
                    tenant, relationshipId, validUntil, expectedRevision, now);
        });
    }

    public OrganizationMembership endOrganizationMembership(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            OrganizationMembership current =
                    relationships.findOrganizationMembership(tenant, relationshipId)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Organization membership does not exist"));
            validateEnd(current.validFrom(), current.validUntil(), validUntil, current.revision(),
                    expectedRevision, relationshipId, "organization membership");
            return relationships.endOrganizationMembership(
                    tenant, relationshipId, validUntil, expectedRevision, now);
        });
    }

    public OwnershipRelationship endOwnershipRelationship(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            OwnershipRelationship current =
                    relationships.findOwnershipRelationship(tenant, relationshipId)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "ownership relationship does not exist"));
            validateEnd(current.validFrom(), current.validUntil(), validUntil, current.revision(),
                    expectedRevision, relationshipId, "ownership relationship");
            return relationships.endOwnershipRelationship(
                    tenant, relationshipId, validUntil, expectedRevision, now);
        });
    }

    private void requireIdentity(TenantContext tenant, UUID identityId) {
        if (identities.findById(tenant, identityId).isEmpty()) {
            throw new IllegalArgumentException("Identity does not exist");
        }
    }

    private void requireProvenance(
            TenantContext tenant,
            UUID subjectIdentityId,
            RelationshipProvenance provenance) {
        Objects.requireNonNull(provenance, "provenance");
        if (provenance.kind() == RelationshipProvenanceKind.SOURCE_RECORD
                && !relationships.sourceRecordAcceptedForIdentity(
                        tenant, provenance.sourceRecordId(), subjectIdentityId)) {
            throw new IllegalArgumentException(
                    "relationship SourceRecord is not currently accepted for the subject Identity");
        }
    }

    private static void validateEnd(
            Instant validFrom,
            Instant currentValidUntil,
            Instant requestedValidUntil,
            long currentRevision,
            long expectedRevision,
            UUID relationshipId,
            String resourceType) {
        Objects.requireNonNull(requestedValidUntil, "validUntil");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        if (currentRevision != expectedRevision) {
            throw new StaleWriteException(resourceType, relationshipId, expectedRevision);
        }
        if (!requestedValidUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (currentValidUntil != null && requestedValidUntil.isAfter(currentValidUntil)) {
            throw new IllegalArgumentException("ending a relationship cannot extend its validity");
        }
    }
}
