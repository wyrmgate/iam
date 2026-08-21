package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.persistence.OptimisticUpdate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for the Identity authoritative aggregate and its required typed profile. */
public final class JdbcIdentityRepository implements IdentityRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdentityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void insert(TenantContext tenant, Identity identity) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identity, "identity");

        jdbcTemplate.update(
                """
                INSERT INTO identity.identity (
                    id, tenant_id, identity_type, lifecycle_state, display_name,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                identity.id(),
                tenant.tenantId(),
                identity.type().name(),
                identity.lifecycleState().name(),
                identity.displayName(),
                identity.revision(),
                Timestamp.from(identity.createdAt()),
                Timestamp.from(identity.updatedAt()));

        String profileTable = profileTable(identity.type());
        jdbcTemplate.update(
                "INSERT INTO identity." + profileTable
                        + " (identity_id, tenant_id, identity_type, created_at) VALUES (?, ?, ?, ?)",
                identity.id(),
                tenant.tenantId(),
                identity.type().name(),
                Timestamp.from(identity.createdAt()));
    }

    @Override
    public Optional<Identity> findById(TenantContext tenant, UUID identityId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");

        List<Identity> rows = jdbcTemplate.query(
                """
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
                WHERE i.tenant_id = ? AND i.id = ?
                """,
                (rs, rowNum) -> {
                    IdentityType type = IdentityType.valueOf(rs.getString("identity_type"));
                    IdentityProfile profile = profileFor(
                            type,
                            rs.getBoolean("has_person_profile"),
                            rs.getBoolean("has_service_profile"),
                            rs.getBoolean("has_workload_profile"));
                    return new Identity(
                            rs.getObject("id", UUID.class),
                            type,
                            profile,
                            IdentityLifecycleState.valueOf(rs.getString("lifecycle_state")),
                            rs.getString("display_name"),
                            rs.getLong("revision"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant());
                },
                tenant.tenantId(),
                identityId);
        return rows.stream().findFirst();
    }

    @Override
    public Identity updateDisplayName(
            TenantContext tenant,
            UUID identityId,
            String displayName,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(now, "now");
        requireText(displayName, "displayName");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }

        int affected = jdbcTemplate.update(
                """
                UPDATE identity.identity
                SET display_name = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                displayName,
                Timestamp.from(now),
                tenant.tenantId(),
                identityId,
                expectedRevision);
        OptimisticUpdate.requireSingleRow(affected, "identity", identityId, expectedRevision);
        return findById(tenant, identityId)
                .orElseThrow(() -> new IllegalStateException("updated identity could not be reloaded"));
    }

    private static String profileTable(IdentityType type) {
        return switch (type) {
            case PERSON -> "person_profile";
            case SERVICE -> "service_profile";
            case WORKLOAD -> "workload_profile";
        };
    }

    private static IdentityProfile profileFor(
            IdentityType type,
            boolean hasPerson,
            boolean hasService,
            boolean hasWorkload) {
        int count = (hasPerson ? 1 : 0) + (hasService ? 1 : 0) + (hasWorkload ? 1 : 0);
        if (count != 1) {
            throw new IllegalStateException("identity must have exactly one typed profile");
        }
        return switch (type) {
            case PERSON -> {
                if (!hasPerson) {
                    throw new IllegalStateException("PERSON identity is missing its person profile");
                }
                yield new IdentityProfile.PersonProfile();
            }
            case SERVICE -> {
                if (!hasService) {
                    throw new IllegalStateException("SERVICE identity is missing its service profile");
                }
                yield new IdentityProfile.ServiceProfile();
            }
            case WORKLOAD -> {
                if (!hasWorkload) {
                    throw new IllegalStateException("WORKLOAD identity is missing its workload profile");
                }
                yield new IdentityProfile.WorkloadProfile();
            }
        };
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
