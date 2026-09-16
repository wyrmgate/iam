package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributeDefinitionEntry;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributePagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryRepository;
import io.wyrmgate.iam.identity.domain.AttributeDefinition;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL bounded query adapter for authoritative Identity read surfaces. */
public final class JdbcIdentityQueryRepository implements IdentityQueryRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdentityQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<Identity> findIdentityPage(TenantContext tenant, IdentityPagePosition after, int limit) {
        Objects.requireNonNull(tenant, "tenant");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        String select = """
                SELECT i.id, i.identity_type, i.lifecycle_state, i.display_name,
                       i.revision, i.created_at, i.updated_at,
                       (p.identity_id IS NOT NULL) AS has_person_profile,
                       (s.identity_id IS NOT NULL) AS has_service_profile,
                       (w.identity_id IS NOT NULL) AS has_workload_profile
                FROM identity.identity i
                LEFT JOIN identity.person_profile p
                  ON p.tenant_id = i.tenant_id AND p.identity_id = i.id
                LEFT JOIN identity.service_profile s
                  ON s.tenant_id = i.tenant_id AND s.identity_id = i.id
                LEFT JOIN identity.workload_profile w
                  ON w.tenant_id = i.tenant_id AND w.identity_id = i.id
                WHERE i.tenant_id = ?
                """;
        if (after == null) {
            return jdbc.query(select + " ORDER BY i.created_at, i.id LIMIT ?",
                    (rs, rowNum) -> identity(rs), tenant.tenantId(), limit);
        }
        return jdbc.query(select + """
                  AND (i.created_at > ? OR (i.created_at = ? AND i.id > ?))
                ORDER BY i.created_at, i.id
                LIMIT ?
                """, (rs, rowNum) -> identity(rs), tenant.tenantId(),
                Timestamp.from(after.createdAt()), Timestamp.from(after.createdAt()), after.id(), limit);
    }

    @Override
    public List<CanonicalAttributeDefinitionEntry> findActiveCanonicalDefinitionPage(
            TenantContext tenant,
            CanonicalAttributePagePosition after,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        String select = """
                SELECT d.id AS definition_id, d.canonical_key, d.lifecycle_state,
                       d.revision AS definition_revision, d.created_at AS definition_created_at,
                       d.updated_at AS definition_updated_at,
                       v.id AS version_id, v.schema_version_id, v.data_type, v.cardinality,
                       v.classification, v.queryable, v.searchable, v.policy_addressable,
                       v.created_at AS version_created_at
                FROM identity.attribute_definition d
                JOIN identity.attribute_definition_version v
                  ON v.tenant_id = d.tenant_id AND v.attribute_definition_id = d.id
                JOIN identity.canonical_schema_version s
                  ON s.tenant_id = v.tenant_id AND s.id = v.schema_version_id AND s.state = 'ACTIVE'
                WHERE d.tenant_id = ? AND d.lifecycle_state = 'ACTIVE'
                """;
        if (after == null) {
            return jdbc.query(select + " ORDER BY d.canonical_key, d.id LIMIT ?",
                    (rs, rowNum) -> definitionEntry(rs), tenant.tenantId(), limit);
        }
        return jdbc.query(select + """
                  AND (d.canonical_key > ? OR (d.canonical_key = ? AND d.id > ?))
                ORDER BY d.canonical_key, d.id
                LIMIT ?
                """, (rs, rowNum) -> definitionEntry(rs), tenant.tenantId(),
                after.key(), after.key(), after.definitionId(), limit);
    }

    private static Identity identity(ResultSet rs) throws SQLException {
        IdentityType type = IdentityType.valueOf(rs.getString("identity_type"));
        boolean hasPerson = rs.getBoolean("has_person_profile");
        boolean hasService = rs.getBoolean("has_service_profile");
        boolean hasWorkload = rs.getBoolean("has_workload_profile");
        int count = (hasPerson ? 1 : 0) + (hasService ? 1 : 0) + (hasWorkload ? 1 : 0);
        if (count != 1) {
            throw new IllegalStateException("identity must have exactly one typed profile");
        }
        IdentityProfile profile = switch (type) {
            case PERSON -> {
                if (!hasPerson) throw new IllegalStateException("PERSON identity is missing its person profile");
                yield new IdentityProfile.PersonProfile();
            }
            case SERVICE -> {
                if (!hasService) throw new IllegalStateException("SERVICE identity is missing its service profile");
                yield new IdentityProfile.ServiceProfile();
            }
            case WORKLOAD -> {
                if (!hasWorkload) throw new IllegalStateException("WORKLOAD identity is missing its workload profile");
                yield new IdentityProfile.WorkloadProfile();
            }
        };
        return new Identity(
                rs.getObject("id", UUID.class),
                type,
                profile,
                IdentityLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getString("display_name"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static CanonicalAttributeDefinitionEntry definitionEntry(ResultSet rs) throws SQLException {
        UUID definitionId = rs.getObject("definition_id", UUID.class);
        AttributeDefinition definition = new AttributeDefinition(
                definitionId,
                rs.getString("canonical_key"),
                AttributeDefinition.LifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("definition_revision"),
                rs.getTimestamp("definition_created_at").toInstant(),
                rs.getTimestamp("definition_updated_at").toInstant());
        AttributeDefinitionVersion version = new AttributeDefinitionVersion(
                rs.getObject("version_id", UUID.class),
                rs.getObject("schema_version_id", UUID.class),
                definitionId,
                CanonicalAttributeType.valueOf(rs.getString("data_type")),
                CanonicalAttributeCardinality.valueOf(rs.getString("cardinality")),
                rs.getString("classification"),
                rs.getBoolean("queryable"),
                rs.getBoolean("searchable"),
                rs.getBoolean("policy_addressable"),
                rs.getTimestamp("version_created_at").toInstant());
        return new CanonicalAttributeDefinitionEntry(definition, version);
    }
}
