package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityRelationshipRepository;
import io.wyrmgate.iam.identity.domain.ManagerRelationship;
import io.wyrmgate.iam.identity.domain.OrganizationMembership;
import io.wyrmgate.iam.identity.domain.OwnershipRelationship;
import io.wyrmgate.iam.identity.domain.RelationshipProvenance;
import io.wyrmgate.iam.identity.domain.RelationshipProvenanceKind;
import io.wyrmgate.iam.platform.persistence.OptimisticUpdate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for typed first-class Identity relationships. */
public final class JdbcIdentityRelationshipRepository implements IdentityRelationshipRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdentityRelationshipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public boolean sourceRecordExists(TenantContext tenant, UUID sourceRecordId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Boolean result = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM identity.source_record
                    WHERE tenant_id = ? AND id = ?
                )
                """,
                Boolean.class,
                tenant.tenantId(),
                sourceRecordId);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void insertManagerRelationship(
            TenantContext tenant, ManagerRelationship relationship) {
        jdbcTemplate.update(
                """
                INSERT INTO identity.manager_relationship (
                    id, tenant_id, subject_identity_id, manager_identity_id,
                    valid_from, valid_until, provenance_kind, source_record_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                relationship.id(),
                tenant.tenantId(),
                relationship.subjectIdentityId(),
                relationship.managerIdentityId(),
                Timestamp.from(relationship.validFrom()),
                timestamp(relationship.validUntil()),
                relationship.provenance().kind().name(),
                relationship.provenance().sourceRecordId(),
                relationship.revision(),
                Timestamp.from(relationship.createdAt()),
                Timestamp.from(relationship.updatedAt()));
    }

    @Override
    public Optional<ManagerRelationship> findManagerRelationship(
            TenantContext tenant, UUID relationshipId) {
        List<ManagerRelationship> rows = jdbcTemplate.query(
                """
                SELECT id, subject_identity_id, manager_identity_id,
                       valid_from, valid_until, provenance_kind, source_record_id,
                       revision, created_at, updated_at
                FROM identity.manager_relationship
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new ManagerRelationship(
                        rs.getObject("id", UUID.class),
                        rs.getObject("subject_identity_id", UUID.class),
                        rs.getObject("manager_identity_id", UUID.class),
                        rs.getTimestamp("valid_from").toInstant(),
                        instant(rs.getTimestamp("valid_until")),
                        provenance(
                                rs.getString("provenance_kind"),
                                rs.getObject("source_record_id", UUID.class)),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                relationshipId);
        return rows.stream().findFirst();
    }

    @Override
    public ManagerRelationship endManagerRelationship(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now) {
        updateValidity(
                "manager_relationship",
                "manager relationship",
                tenant,
                relationshipId,
                validUntil,
                expectedRevision,
                now);
        return findManagerRelationship(tenant, relationshipId)
                .orElseThrow(() -> new IllegalStateException(
                        "updated manager relationship could not be reloaded"));
    }

    @Override
    public void insertOrganizationMembership(
            TenantContext tenant, OrganizationMembership membership) {
        jdbcTemplate.update(
                """
                INSERT INTO identity.organization_membership (
                    id, tenant_id, subject_identity_id, organization_id,
                    valid_from, valid_until, provenance_kind, source_record_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                membership.id(),
                tenant.tenantId(),
                membership.subjectIdentityId(),
                membership.organizationId(),
                Timestamp.from(membership.validFrom()),
                timestamp(membership.validUntil()),
                membership.provenance().kind().name(),
                membership.provenance().sourceRecordId(),
                membership.revision(),
                Timestamp.from(membership.createdAt()),
                Timestamp.from(membership.updatedAt()));
    }

    @Override
    public Optional<OrganizationMembership> findOrganizationMembership(
            TenantContext tenant, UUID relationshipId) {
        List<OrganizationMembership> rows = jdbcTemplate.query(
                """
                SELECT id, subject_identity_id, organization_id,
                       valid_from, valid_until, provenance_kind, source_record_id,
                       revision, created_at, updated_at
                FROM identity.organization_membership
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new OrganizationMembership(
                        rs.getObject("id", UUID.class),
                        rs.getObject("subject_identity_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getTimestamp("valid_from").toInstant(),
                        instant(rs.getTimestamp("valid_until")),
                        provenance(
                                rs.getString("provenance_kind"),
                                rs.getObject("source_record_id", UUID.class)),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                relationshipId);
        return rows.stream().findFirst();
    }

    @Override
    public OrganizationMembership endOrganizationMembership(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now) {
        updateValidity(
                "organization_membership",
                "organization membership",
                tenant,
                relationshipId,
                validUntil,
                expectedRevision,
                now);
        return findOrganizationMembership(tenant, relationshipId)
                .orElseThrow(() -> new IllegalStateException(
                        "updated Organization membership could not be reloaded"));
    }

    @Override
    public void insertOwnershipRelationship(
            TenantContext tenant, OwnershipRelationship relationship) {
        jdbcTemplate.update(
                """
                INSERT INTO identity.ownership_relationship (
                    id, tenant_id, subject_identity_id, owner_identity_id,
                    valid_from, valid_until, provenance_kind, source_record_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                relationship.id(),
                tenant.tenantId(),
                relationship.subjectIdentityId(),
                relationship.ownerIdentityId(),
                Timestamp.from(relationship.validFrom()),
                timestamp(relationship.validUntil()),
                relationship.provenance().kind().name(),
                relationship.provenance().sourceRecordId(),
                relationship.revision(),
                Timestamp.from(relationship.createdAt()),
                Timestamp.from(relationship.updatedAt()));
    }

    @Override
    public Optional<OwnershipRelationship> findOwnershipRelationship(
            TenantContext tenant, UUID relationshipId) {
        List<OwnershipRelationship> rows = jdbcTemplate.query(
                """
                SELECT id, subject_identity_id, owner_identity_id,
                       valid_from, valid_until, provenance_kind, source_record_id,
                       revision, created_at, updated_at
                FROM identity.ownership_relationship
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new OwnershipRelationship(
                        rs.getObject("id", UUID.class),
                        rs.getObject("subject_identity_id", UUID.class),
                        rs.getObject("owner_identity_id", UUID.class),
                        rs.getTimestamp("valid_from").toInstant(),
                        instant(rs.getTimestamp("valid_until")),
                        provenance(
                                rs.getString("provenance_kind"),
                                rs.getObject("source_record_id", UUID.class)),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                relationshipId);
        return rows.stream().findFirst();
    }

    @Override
    public OwnershipRelationship endOwnershipRelationship(
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now) {
        updateValidity(
                "ownership_relationship",
                "ownership relationship",
                tenant,
                relationshipId,
                validUntil,
                expectedRevision,
                now);
        return findOwnershipRelationship(tenant, relationshipId)
                .orElseThrow(() -> new IllegalStateException(
                        "updated ownership relationship could not be reloaded"));
    }

    private void updateValidity(
            String table,
            String resourceType,
            TenantContext tenant,
            UUID relationshipId,
            Instant validUntil,
            long expectedRevision,
            Instant now) {
        int affected = jdbcTemplate.update(
                "UPDATE identity." + table
                        + " SET valid_until = ?, revision = revision + 1, updated_at = ?"
                        + " WHERE tenant_id = ? AND id = ? AND revision = ?",
                Timestamp.from(validUntil),
                Timestamp.from(now),
                tenant.tenantId(),
                relationshipId,
                expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, resourceType, relationshipId, expectedRevision);
    }

    private static RelationshipProvenance provenance(String kind, UUID sourceRecordId) {
        return new RelationshipProvenance(
                RelationshipProvenanceKind.valueOf(kind),
                sourceRecordId);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
