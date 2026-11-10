package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.OrganizationRepository;
import io.wyrmgate.iam.identity.domain.Organization;
import io.wyrmgate.iam.platform.persistence.OptimisticUpdate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Identity-owned Organization structure. */
public final class JdbcOrganizationRepository implements OrganizationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrganizationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void insert(TenantContext tenant, Organization organization) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(organization, "organization");
        jdbcTemplate.update(
                """
                INSERT INTO identity.organization (
                    id, tenant_id, name, organization_type, parent_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organization.id(),
                tenant.tenantId(),
                organization.name(),
                organization.organizationType(),
                organization.parentId(),
                organization.revision(),
                Timestamp.from(organization.createdAt()),
                Timestamp.from(organization.updatedAt()));
    }

    @Override
    public Optional<Organization> findById(TenantContext tenant, UUID organizationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(organizationId, "organizationId");
        List<Organization> rows = jdbcTemplate.query(
                """
                SELECT id, name, organization_type, parent_id, revision, created_at, updated_at
                FROM identity.organization
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new Organization(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("organization_type"),
                        rs.getObject("parent_id", UUID.class),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                organizationId);
        return rows.stream().findFirst();
    }

    @Override
    public void lockHierarchyMutation(TenantContext tenant) {
        Objects.requireNonNull(tenant, "tenant");
        jdbcTemplate.queryForObject(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(CAST(? AS text), 1197768013))
                """,
                Long.class,
                tenant.tenantId());
    }

    @Override
    public Organization updateMetadata(
            TenantContext tenant,
            UUID organizationId,
            String name,
            String organizationType,
            long expectedRevision,
            Instant now) {
        int affected = jdbcTemplate.update(
                """
                UPDATE identity.organization
                SET name = ?, organization_type = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                name,
                organizationType,
                Timestamp.from(now),
                tenant.tenantId(),
                organizationId,
                expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, "organization", organizationId, expectedRevision);
        return findById(tenant, organizationId)
                .orElseThrow(() -> new IllegalStateException(
                        "updated Organization could not be reloaded"));
    }

    @Override
    public Organization updateParent(
            TenantContext tenant,
            UUID organizationId,
            UUID parentId,
            long expectedRevision,
            Instant now) {
        int affected = jdbcTemplate.update(
                """
                UPDATE identity.organization
                SET parent_id = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                parentId,
                Timestamp.from(now),
                tenant.tenantId(),
                organizationId,
                expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, "organization", organizationId, expectedRevision);
        return findById(tenant, organizationId)
                .orElseThrow(() -> new IllegalStateException(
                        "updated Organization could not be reloaded"));
    }

    @Override
    public boolean wouldCreateCycle(
            TenantContext tenant,
            UUID organizationId,
            UUID proposedParentId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(proposedParentId, "proposedParentId");
        Boolean result = jdbcTemplate.queryForObject(
                """
                WITH RECURSIVE ancestors AS (
                    SELECT id, parent_id
                    FROM identity.organization
                    WHERE tenant_id = ? AND id = ?
                    UNION
                    SELECT parent.id, parent.parent_id
                    FROM identity.organization parent
                    JOIN ancestors child ON child.parent_id = parent.id
                    WHERE parent.tenant_id = ?
                )
                SELECT EXISTS (
                    SELECT 1 FROM ancestors WHERE id = ?
                )
                """,
                Boolean.class,
                tenant.tenantId(),
                proposedParentId,
                tenant.tenantId(),
                organizationId);
        return Boolean.TRUE.equals(result);
    }
}
