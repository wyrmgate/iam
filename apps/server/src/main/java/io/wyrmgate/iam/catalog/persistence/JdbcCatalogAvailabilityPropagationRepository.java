package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationRepository;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityPropagation;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC persistence and bounded candidate discovery for Catalog retirement propagation. */
public final class JdbcCatalogAvailabilityPropagationRepository
        implements CatalogAvailabilityPropagationRepository {

    private final JdbcTemplate jdbc;

    public JdbcCatalogAvailabilityPropagationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public CatalogAvailabilityPropagation startIfAbsent(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation) {
        jdbc.update(
                """
                INSERT INTO catalog.access_target_availability_propagation (
                    id, tenant_id, cause_kind, cause_id, cause_revision,
                    phase, after_created_at, after_resource_id,
                    processed_resource_count, revision, lifecycle_state,
                    created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, cause_kind, cause_id, cause_revision)
                DO NOTHING
                """,
                propagation.id(),
                tenant.tenantId(),
                propagation.causeKind().name(),
                propagation.causeId(),
                propagation.causeRevision(),
                propagation.phase().name(),
                timestamp(propagation.afterCreatedAt()),
                propagation.afterResourceId(),
                propagation.processedResourceCount(),
                propagation.revision(),
                propagation.state().name(),
                Timestamp.from(propagation.createdAt()),
                Timestamp.from(propagation.updatedAt()),
                timestamp(propagation.completedAt()));
        return findBySource(
                        tenant,
                        propagation.causeKind(),
                        propagation.causeId(),
                        propagation.causeRevision())
                .orElseThrow(() -> new IllegalStateException(
                        "Catalog availability propagation could not be reloaded"));
    }

    @Override
    public Optional<CatalogAvailabilityPropagation> findById(
            TenantContext tenant, UUID propagationId) {
        return jdbc.query(
                        baseSelect() + " WHERE tenant_id = ? AND id = ?",
                        (rs, rowNum) -> propagation(rs),
                        tenant.tenantId(),
                        propagationId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ResourceRef> findPhasePage(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            int limit) {
        if (limit < 1 || limit > 501) {
            throw new IllegalArgumentException("propagation page limit must be between 1 and 501");
        }
        return switch (propagation.phase()) {
            case ENTITLEMENTS -> entitlementPage(tenant, propagation, limit);
            case ROLES -> roleTargetPage(tenant, propagation, limit);
            case ROLE_EXPANSIONS -> roleExpansionPage(tenant, propagation, limit);
        };
    }

    @Override
    public CatalogAvailabilityPropagation recordProgress(
            TenantContext tenant,
            UUID propagationId,
            CatalogAvailabilityPropagation.Phase nextPhase,
            Instant afterCreatedAt,
            UUID afterResourceId,
            long processedDelta,
            boolean completed,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(nextPhase, "nextPhase");
        if ((afterCreatedAt == null) != (afterResourceId == null)) {
            throw new IllegalArgumentException("checkpoint fields must be both present or both absent");
        }
        if (processedDelta < 0 || expectedRevision < 1) {
            throw new IllegalArgumentException("invalid propagation progress");
        }
        int affected = jdbc.update(
                """
                UPDATE catalog.access_target_availability_propagation
                SET phase = ?,
                    after_created_at = ?,
                    after_resource_id = ?,
                    processed_resource_count = processed_resource_count + ?,
                    revision = revision + 1,
                    lifecycle_state = ?,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ? AND id = ?
                  AND revision = ? AND lifecycle_state = 'RUNNING'
                """,
                nextPhase.name(),
                timestamp(afterCreatedAt),
                afterResourceId,
                processedDelta,
                completed ? "COMPLETED" : "RUNNING",
                Timestamp.from(now),
                completed ? Timestamp.from(now) : null,
                tenant.tenantId(),
                propagationId,
                expectedRevision);
        if (affected != 1) {
            CatalogAvailabilityPropagation current = findById(tenant, propagationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Catalog availability propagation does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "catalog-availability-propagation",
                        propagationId,
                        expectedRevision);
            }
            if (current.state() == CatalogAvailabilityPropagation.State.COMPLETED) {
                return current;
            }
            throw new IllegalStateException("Catalog availability propagation did not update");
        }
        return findById(tenant, propagationId).orElseThrow();
    }

    private Optional<CatalogAvailabilityPropagation> findBySource(
            TenantContext tenant,
            CatalogAvailabilityCauseKind causeKind,
            UUID causeId,
            long causeRevision) {
        return jdbc.query(
                        baseSelect()
                                + """
                                  WHERE tenant_id = ?
                                    AND cause_kind = ?
                                    AND cause_id = ?
                                    AND cause_revision = ?
                                """,
                        (rs, rowNum) -> propagation(rs),
                        tenant.tenantId(),
                        causeKind.name(),
                        causeId,
                        causeRevision)
                .stream()
                .findFirst();
    }

    private List<ResourceRef> entitlementPage(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            int limit) {
        String predicate = checkpointPredicate(propagation);
        String condition = switch (propagation.causeKind()) {
            case APPLICATION -> "application_id = ?";
            case APPLICATION_TARGET -> "application_target_id = ?";
            case ENTITLEMENT -> "id = ?";
            case ROLE -> "FALSE";
        };
        return jdbc.query(
                """
                SELECT id, revision, created_at
                FROM catalog.entitlement
                WHERE tenant_id = ? AND %s %s
                ORDER BY created_at, id
                LIMIT ?
                """.formatted(condition, predicate),
                (rs, rowNum) -> resource(rs),
                args(tenant, propagation, limit));
    }

    private List<ResourceRef> roleTargetPage(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            int limit) {
        String predicate = checkpointPredicate(propagation);
        String condition = switch (propagation.causeKind()) {
            case APPLICATION -> "role_type = 'APPLICATION' AND application_id = ?";
            case ROLE -> "id = ?";
            case APPLICATION_TARGET, ENTITLEMENT -> "FALSE";
        };
        return jdbc.query(
                """
                SELECT id, revision, created_at
                FROM catalog.role
                WHERE tenant_id = ? AND %s %s
                ORDER BY created_at, id
                LIMIT ?
                """.formatted(condition, predicate),
                (rs, rowNum) -> resource(rs),
                args(tenant, propagation, limit));
    }

    private List<ResourceRef> roleExpansionPage(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            int limit) {
        String checkpoint = checkpointPredicateForAlias(propagation, "r");
        return switch (propagation.causeKind()) {
            case APPLICATION -> jdbc.query(
                    """
                    SELECT r.id, r.revision, r.created_at
                    FROM catalog.role r
                    WHERE r.tenant_id = ?
                      AND r.lifecycle_state = 'ACTIVE'
                      AND (
                          (r.role_type = 'APPLICATION' AND r.application_id = ?)
                          OR EXISTS (
                              SELECT 1
                              FROM catalog.role_version rv
                              JOIN catalog.role_version_member m
                                ON m.tenant_id = rv.tenant_id
                               AND m.role_version_id = rv.id
                              LEFT JOIN catalog.entitlement e
                                ON e.tenant_id = m.tenant_id
                               AND e.id = m.member_entitlement_id
                              LEFT JOIN catalog.role child
                                ON child.tenant_id = m.tenant_id
                               AND child.id = m.member_role_id
                              WHERE rv.tenant_id = r.tenant_id
                                AND rv.role_id = r.id
                                AND rv.state = 'ACTIVE'
                                AND (
                                    (m.member_kind = 'ENTITLEMENT' AND e.application_id = ?)
                                    OR (m.member_kind = 'APPLICATION_ROLE' AND child.application_id = ?)
                                )
                          )
                      ) %s
                    ORDER BY r.created_at, r.id
                    LIMIT ?
                    """.formatted(checkpoint),
                    (rs, rowNum) -> resource(rs),
                    roleArgs(tenant, propagation, limit, 3));
            case APPLICATION_TARGET -> jdbc.query(
                    """
                    SELECT r.id, r.revision, r.created_at
                    FROM catalog.role r
                    WHERE r.tenant_id = ?
                      AND r.lifecycle_state = 'ACTIVE'
                      AND EXISTS (
                          SELECT 1
                          FROM catalog.role_version rv
                          JOIN catalog.role_version_member m
                            ON m.tenant_id = rv.tenant_id
                           AND m.role_version_id = rv.id
                          WHERE rv.tenant_id = r.tenant_id
                            AND rv.role_id = r.id
                            AND rv.state = 'ACTIVE'
                            AND (
                                (m.member_kind = 'ENTITLEMENT' AND EXISTS (
                                    SELECT 1 FROM catalog.entitlement e
                                    WHERE e.tenant_id = m.tenant_id
                                      AND e.id = m.member_entitlement_id
                                      AND e.application_target_id = ?))
                                OR
                                (m.member_kind = 'APPLICATION_ROLE' AND EXISTS (
                                    SELECT 1
                                    FROM catalog.role_version crv
                                    JOIN catalog.role_version_member cm
                                      ON cm.tenant_id = crv.tenant_id
                                     AND cm.role_version_id = crv.id
                                    JOIN catalog.entitlement ce
                                      ON ce.tenant_id = cm.tenant_id
                                     AND ce.id = cm.member_entitlement_id
                                    WHERE crv.tenant_id = m.tenant_id
                                      AND crv.role_id = m.member_role_id
                                      AND crv.state = 'ACTIVE'
                                      AND cm.member_kind = 'ENTITLEMENT'
                                      AND ce.application_target_id = ?))
                            )
                      ) %s
                    ORDER BY r.created_at, r.id
                    LIMIT ?
                    """.formatted(checkpoint),
                    (rs, rowNum) -> resource(rs),
                    roleArgs(tenant, propagation, limit, 2));
            case ENTITLEMENT -> jdbc.query(
                    """
                    SELECT r.id, r.revision, r.created_at
                    FROM catalog.role r
                    WHERE r.tenant_id = ?
                      AND r.lifecycle_state = 'ACTIVE'
                      AND EXISTS (
                          SELECT 1
                          FROM catalog.role_version rv
                          JOIN catalog.role_version_member m
                            ON m.tenant_id = rv.tenant_id
                           AND m.role_version_id = rv.id
                          WHERE rv.tenant_id = r.tenant_id
                            AND rv.role_id = r.id
                            AND rv.state = 'ACTIVE'
                            AND (
                                (m.member_kind = 'ENTITLEMENT' AND m.member_entitlement_id = ?)
                                OR
                                (m.member_kind = 'APPLICATION_ROLE' AND EXISTS (
                                    SELECT 1
                                    FROM catalog.role_version crv
                                    JOIN catalog.role_version_member cm
                                      ON cm.tenant_id = crv.tenant_id
                                     AND cm.role_version_id = crv.id
                                    WHERE crv.tenant_id = m.tenant_id
                                      AND crv.role_id = m.member_role_id
                                      AND crv.state = 'ACTIVE'
                                      AND cm.member_kind = 'ENTITLEMENT'
                                      AND cm.member_entitlement_id = ?))
                            )
                      ) %s
                    ORDER BY r.created_at, r.id
                    LIMIT ?
                    """.formatted(checkpoint),
                    (rs, rowNum) -> resource(rs),
                    roleArgs(tenant, propagation, limit, 2));
            case ROLE -> jdbc.query(
                    """
                    SELECT r.id, r.revision, r.created_at
                    FROM catalog.role r
                    WHERE r.tenant_id = ?
                      AND (
                          r.id = ?
                          OR (
                              r.lifecycle_state = 'ACTIVE'
                              AND r.role_type = 'BUSINESS'
                              AND EXISTS (
                                  SELECT 1
                                  FROM catalog.role_version rv
                                  JOIN catalog.role_version_member m
                                    ON m.tenant_id = rv.tenant_id
                                   AND m.role_version_id = rv.id
                                  WHERE rv.tenant_id = r.tenant_id
                                    AND rv.role_id = r.id
                                    AND rv.state = 'ACTIVE'
                                    AND m.member_kind = 'APPLICATION_ROLE'
                                    AND m.member_role_id = ?
                              )
                          )
                      ) %s
                    ORDER BY r.created_at, r.id
                    LIMIT ?
                    """.formatted(checkpoint),
                    (rs, rowNum) -> resource(rs),
                    roleArgs(tenant, propagation, limit, 2));
        };
    }

    private Object[] args(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            int limit) {
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(tenant.tenantId());
        if (propagation.causeKind() != CatalogAvailabilityCauseKind.ROLE
                || propagation.phase() == CatalogAvailabilityPropagation.Phase.ROLES) {
            if (propagation.causeKind() != CatalogAvailabilityCauseKind.ROLE
                    || propagation.phase() != CatalogAvailabilityPropagation.Phase.ENTITLEMENTS) {
                args.add(propagation.causeId());
            }
        }
        appendCheckpoint(args, propagation);
        args.add(limit);
        return args.toArray();
    }

    private Object[] roleArgs(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            int limit,
            int causeCopies) {
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(tenant.tenantId());
        for (int i = 0; i < causeCopies; i++) {
            args.add(propagation.causeId());
        }
        appendCheckpoint(args, propagation);
        args.add(limit);
        return args.toArray();
    }

    private static String checkpointPredicate(
            CatalogAvailabilityPropagation propagation) {
        return propagation.afterCreatedAt() == null
                ? ""
                : " AND (created_at, id) > (?, ?)";
    }

    private static String checkpointPredicateForAlias(
            CatalogAvailabilityPropagation propagation,
            String alias) {
        return propagation.afterCreatedAt() == null
                ? ""
                : " AND (" + alias + ".created_at, " + alias + ".id) > (?, ?)";
    }

    private static void appendCheckpoint(
            java.util.List<Object> args,
            CatalogAvailabilityPropagation propagation) {
        if (propagation.afterCreatedAt() != null) {
            args.add(Timestamp.from(propagation.afterCreatedAt()));
            args.add(propagation.afterResourceId());
        }
    }

    private static ResourceRef resource(ResultSet rs) throws SQLException {
        return new ResourceRef(
                rs.getObject("id", UUID.class),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String baseSelect() {
        return """
                SELECT id, cause_kind, cause_id, cause_revision,
                       phase, after_created_at, after_resource_id,
                       processed_resource_count, revision, lifecycle_state,
                       created_at, updated_at, completed_at
                FROM catalog.access_target_availability_propagation
                """;
    }

    private static CatalogAvailabilityPropagation propagation(
            ResultSet rs) throws SQLException {
        Timestamp after = rs.getTimestamp("after_created_at");
        Timestamp completed = rs.getTimestamp("completed_at");
        return new CatalogAvailabilityPropagation(
                rs.getObject("id", UUID.class),
                CatalogAvailabilityCauseKind.valueOf(rs.getString("cause_kind")),
                rs.getObject("cause_id", UUID.class),
                rs.getLong("cause_revision"),
                CatalogAvailabilityPropagation.Phase.valueOf(rs.getString("phase")),
                after == null ? null : after.toInstant(),
                rs.getObject("after_resource_id", UUID.class),
                rs.getLong("processed_resource_count"),
                rs.getLong("revision"),
                CatalogAvailabilityPropagation.State.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
