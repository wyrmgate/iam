package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository.EntitlementObservationMapping;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcIntegrationObservationRepository
        implements IntegrationEntitlementMappingRepository, IntegrationObservedAccessQuery {

    private final JdbcTemplate jdbc;

    public JdbcIntegrationObservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasPresentObservedEntitlement(
            TenantContext tenant, UUID connectorBindingId, String providerStableId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM integration.observed_entitlement
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND provider_stable_id = ? AND present
                """, Integer.class, tenant.tenantId(), connectorBindingId, providerStableId);
        return count != null && count > 0;
    }

    @Override
    public Optional<EntitlementObservationMapping> find(
            TenantContext tenant, UUID mappingId) {
        return jdbc.query("""
                SELECT id, connector_binding_id, provider_stable_id, entitlement_id,
                       lifecycle_state, revision, created_at, updated_at, retired_at
                FROM integration.entitlement_observation_mapping
                WHERE tenant_id = ? AND id = ?
                """, (rs,row) -> mapping(rs), tenant.tenantId(), mappingId)
                .stream().findFirst();
    }

    @Override
    public Optional<EntitlementObservationMapping> findActiveByProvider(
            TenantContext tenant, UUID connectorBindingId, String providerStableId) {
        return jdbc.query("""
                SELECT id, connector_binding_id, provider_stable_id, entitlement_id,
                       lifecycle_state, revision, created_at, updated_at, retired_at
                FROM integration.entitlement_observation_mapping
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND provider_stable_id = ? AND lifecycle_state = 'ACTIVE'
                """, (rs,row) -> mapping(rs),
                tenant.tenantId(), connectorBindingId, providerStableId)
                .stream().findFirst();
    }

    @Override
    public EntitlementObservationMapping create(
            TenantContext tenant,
            UUID id,
            UUID connectorBindingId,
            String providerStableId,
            UUID entitlementId,
            Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO integration.entitlement_observation_mapping (
                        id, tenant_id, connector_binding_id, provider_stable_id,
                        entitlement_id, lifecycle_state, revision,
                        created_at, updated_at, retired_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, NULL)
                    """,
                    id, tenant.tenantId(), connectorBindingId, providerStableId,
                    entitlementId, Timestamp.from(now), Timestamp.from(now));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IntegrationAdministrationException(
                    "mapping_conflict",
                    "The provider entitlement is already actively mapped for this binding.");
        }
        return find(tenant, id).orElseThrow();
    }

    @Override
    public EntitlementObservationMapping retire(
            TenantContext tenant, UUID id, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE integration.entitlement_observation_mapping
                SET lifecycle_state = 'RETIRED',
                    revision = revision + 1,
                    updated_at = ?,
                    retired_at = ?
                WHERE tenant_id = ? AND id = ?
                  AND revision = ? AND lifecycle_state = 'ACTIVE'
                """,
                Timestamp.from(now), Timestamp.from(now),
                tenant.tenantId(), id, expectedRevision);
        if (affected != 1) {
            if (find(tenant, id).isEmpty()) {
                throw new IntegrationAdministrationException(
                        "not_found", "The entitlement observation mapping was not found.");
            }
            throw new StaleWriteException("entitlement-observation-mapping", id, expectedRevision);
        }
        return find(tenant, id).orElseThrow();
    }

    @Override
    public List<ObservedEntitlementFact> currentEntitlements(
            TenantContext tenant, UUID connectorBindingId) {
        return jdbc.query("""
                SELECT o.connector_binding_id, o.provider_stable_id,
                       m.entitlement_id, o.observed_at
                FROM integration.observed_entitlement o
                LEFT JOIN integration.entitlement_observation_mapping m
                  ON m.tenant_id = o.tenant_id
                 AND m.connector_binding_id = o.connector_binding_id
                 AND m.provider_stable_id = o.provider_stable_id
                 AND m.lifecycle_state = 'ACTIVE'
                WHERE o.tenant_id = ? AND o.connector_binding_id = ? AND o.present
                ORDER BY o.provider_stable_id
                """,
                (rs,row) -> new ObservedEntitlementFact(
                        rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant()),
                tenant.tenantId(), connectorBindingId);
    }

    @Override
    public List<ObservedGrantFact> currentGrants(
            TenantContext tenant, UUID connectorBindingId) {
        return jdbc.query("""
                SELECT g.connector_binding_id, b.target_id, g.provider_stable_id,
                       g.principal_provider_id, g.entitlement_provider_id,
                       m.entitlement_id, g.observed_at
                FROM integration.observed_grant g
                JOIN integration.connector_binding b
                  ON b.tenant_id = g.tenant_id
                 AND b.id = g.connector_binding_id
                 AND b.target_kind = 'APPLICATION_TARGET'
                LEFT JOIN integration.entitlement_observation_mapping m
                  ON m.tenant_id = g.tenant_id
                 AND m.connector_binding_id = g.connector_binding_id
                 AND m.provider_stable_id = g.entitlement_provider_id
                 AND m.lifecycle_state = 'ACTIVE'
                WHERE g.tenant_id = ? AND g.connector_binding_id = ? AND g.present
                ORDER BY g.provider_stable_id
                """,
                (rs,row) -> new ObservedGrantFact(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getTimestamp(7).toInstant()),
                tenant.tenantId(), connectorBindingId);
    }

    @Override
    public List<UUID> activeApplicationTargetBindings(
            TenantContext tenant, UUID applicationTargetId) {
        return jdbc.query("""
                SELECT id
                FROM integration.connector_binding
                WHERE tenant_id = ?
                  AND target_kind = 'APPLICATION_TARGET'
                  AND target_id = ?
                  AND lifecycle_state = 'ACTIVE'
                ORDER BY id
                """,
                (rs,row) -> rs.getObject(1, UUID.class),
                tenant.tenantId(), applicationTargetId);
    }

    private static EntitlementObservationMapping mapping(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Timestamp retired = rs.getTimestamp("retired_at");
        return new EntitlementObservationMapping(
                rs.getObject("id", UUID.class),
                rs.getObject("connector_binding_id", UUID.class),
                rs.getString("provider_stable_id"),
                rs.getObject("entitlement_id", UUID.class),
                rs.getString("lifecycle_state"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                retired == null ? null : retired.toInstant());
    }
}
