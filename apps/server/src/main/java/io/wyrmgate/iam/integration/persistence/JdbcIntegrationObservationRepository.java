package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository.EntitlementObservationMapping;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.integration.application.IntegrationPrincipalMappingRepository;
import io.wyrmgate.iam.integration.application.IntegrationPrincipalMappingRepository.PrincipalObservationMapping;
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
        implements IntegrationEntitlementMappingRepository,
                IntegrationPrincipalMappingRepository,
                IntegrationObservedAccessQuery {

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
                """, (rs,row) -> entitlementMapping(rs), tenant.tenantId(), mappingId)
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
                """, (rs,row) -> entitlementMapping(rs),
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
    public boolean hasPresentObservedPrincipal(
            TenantContext tenant,
            UUID connectorBindingId,
            String providerStableId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM integration.observed_principal
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND provider_stable_id = ? AND present
                """,
                Integer.class,
                tenant.tenantId(),
                connectorBindingId,
                providerStableId);
        return count != null && count > 0;
    }

    @Override
    public Optional<PrincipalObservationMapping> findPrincipalMapping(
            TenantContext tenant,
            UUID mappingId) {
        return jdbc.query("""
                SELECT id, connector_binding_id, provider_stable_id, principal_id,
                       lifecycle_state, revision, created_at, updated_at, retired_at
                FROM integration.principal_observation_mapping
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> principalMapping(rs),
                tenant.tenantId(),
                mappingId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<PrincipalObservationMapping> findActivePrincipalMappingByProvider(
            TenantContext tenant,
            UUID connectorBindingId,
            String providerStableId) {
        return jdbc.query("""
                SELECT id, connector_binding_id, provider_stable_id, principal_id,
                       lifecycle_state, revision, created_at, updated_at, retired_at
                FROM integration.principal_observation_mapping
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND provider_stable_id = ? AND lifecycle_state = 'ACTIVE'
                """,
                (rs,row) -> principalMapping(rs),
                tenant.tenantId(),
                connectorBindingId,
                providerStableId)
                .stream()
                .findFirst();
    }

    @Override
    public PrincipalObservationMapping createPrincipalMapping(
            TenantContext tenant,
            UUID id,
            UUID connectorBindingId,
            String providerStableId,
            UUID principalId,
            Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO integration.principal_observation_mapping (
                        id, tenant_id, connector_binding_id, provider_stable_id,
                        principal_id, lifecycle_state, revision,
                        created_at, updated_at, retired_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, NULL)
                    """,
                    id,
                    tenant.tenantId(),
                    connectorBindingId,
                    providerStableId,
                    principalId,
                    Timestamp.from(now),
                    Timestamp.from(now));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IntegrationAdministrationException(
                    "principal_mapping_conflict",
                    "The provider account is already actively mapped for this binding.");
        }
        return findPrincipalMapping(tenant, id).orElseThrow();
    }

    @Override
    public PrincipalObservationMapping retirePrincipalMapping(
            TenantContext tenant,
            UUID id,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE integration.principal_observation_mapping
                SET lifecycle_state = 'RETIRED',
                    revision = revision + 1,
                    updated_at = ?,
                    retired_at = ?
                WHERE tenant_id = ? AND id = ?
                  AND revision = ? AND lifecycle_state = 'ACTIVE'
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                id,
                expectedRevision);
        if (affected != 1) {
            if (findPrincipalMapping(tenant, id).isEmpty()) {
                throw new IntegrationAdministrationException(
                        "not_found", "The Principal observation mapping was not found.");
            }
            throw new StaleWriteException(
                    "principal-observation-mapping",
                    id,
                    expectedRevision);
        }
        return findPrincipalMapping(tenant, id).orElseThrow();
    }

    @Override
    public long currentGeneration(TenantContext tenant, UUID connectorBindingId) {
        return jdbc.query("""
                SELECT COALESCE(g.generation, 0)
                FROM integration.connector_binding b
                LEFT JOIN integration.observed_access_generation g
                  ON g.tenant_id = b.tenant_id AND g.connector_binding_id = b.id
                WHERE b.tenant_id = ? AND b.id = ?
                """, (rs,n) -> rs.getLong(1), tenant.tenantId(), connectorBindingId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("unknown ConnectorBinding"));
    }

    private static final String ENTITLEMENTS = """
            SELECT o.connector_binding_id, o.provider_stable_id, m.entitlement_id, o.observed_at,
                   o.present, COALESCE(r.effective_completeness = 'COMPLETE'
                       AND r.state = 'SUCCEEDED' AND r.scope_object_class = 'ENTITLEMENT', false)
            FROM integration.observed_entitlement o
            LEFT JOIN integration.entitlement_observation_mapping m
              ON m.tenant_id = o.tenant_id AND m.connector_binding_id = o.connector_binding_id
             AND m.provider_stable_id = o.provider_stable_id AND m.lifecycle_state = 'ACTIVE'
            LEFT JOIN integration.reconciliation_run r
              ON r.tenant_id = o.tenant_id AND r.connector_binding_id = o.connector_binding_id
             AND r.id = o.last_observed_run_id
            WHERE o.tenant_id = ? AND o.connector_binding_id = ?
            """;

    private static final String GRANTS = """
            SELECT g.connector_binding_id, b.target_id, g.provider_stable_id,
                   g.principal_provider_id, g.entitlement_provider_id,
                   em.entitlement_id, pm.principal_id, g.observed_at,
                   g.present, COALESCE(r.effective_completeness = 'COMPLETE'
                       AND r.state = 'SUCCEEDED' AND r.scope_object_class = 'GRANT', false)
            FROM integration.observed_grant g
            JOIN integration.connector_binding b
              ON b.tenant_id = g.tenant_id AND b.id = g.connector_binding_id
             AND b.target_kind = 'APPLICATION_TARGET'
            LEFT JOIN integration.entitlement_observation_mapping em
              ON em.tenant_id = g.tenant_id AND em.connector_binding_id = g.connector_binding_id
             AND em.provider_stable_id = g.entitlement_provider_id AND em.lifecycle_state = 'ACTIVE'
            LEFT JOIN integration.principal_observation_mapping pm
              ON pm.tenant_id = g.tenant_id AND pm.connector_binding_id = g.connector_binding_id
             AND pm.provider_stable_id = g.principal_provider_id AND pm.lifecycle_state = 'ACTIVE'
            LEFT JOIN integration.reconciliation_run r
              ON r.tenant_id = g.tenant_id AND r.connector_binding_id = g.connector_binding_id
             AND r.id = g.last_observed_run_id
            WHERE g.tenant_id = ? AND g.connector_binding_id = ?
            """;

    @Override
    public List<ObservedEntitlementFact> entitlementPage(
            TenantContext tenant, UUID binding, String after, int limit) {
        requirePageLimit(limit);
        return jdbc.query(ENTITLEMENTS + """
                 AND o.present AND (?::text IS NULL OR o.provider_stable_id > ?)
                 ORDER BY o.provider_stable_id LIMIT ?
                """, (rs,n) -> entitlementFact(rs), tenant.tenantId(), binding, after, after, limit);
    }

    @Override
    public List<ObservedGrantFact> grantPage(
            TenantContext tenant, UUID binding, String after, int limit) {
        requirePageLimit(limit);
        return jdbc.query(GRANTS + """
                 AND g.present AND (?::text IS NULL OR g.provider_stable_id > ?)
                 ORDER BY g.provider_stable_id LIMIT ?
                """, (rs,n) -> grantFact(rs), tenant.tenantId(), binding, after, after, limit);
    }

    @Override
    public Observation<ObservedEntitlementFact> entitlement(
            TenantContext tenant, UUID binding, String providerId) {
        return jdbc.query(ENTITLEMENTS + " AND o.provider_stable_id = ?",
                (rs,n) -> rs.getBoolean(5)
                        ? new Observation<>(Presence.PRESENT, entitlementFact(rs))
                        : new Observation<ObservedEntitlementFact>(
                                rs.getBoolean(6) ? Presence.TRUSTED_ABSENT : Presence.UNKNOWN, null),
                tenant.tenantId(), binding, providerId).stream().findFirst()
                .orElse(new Observation<>(Presence.UNKNOWN, null));
    }

    @Override
    public Observation<ObservedGrantFact> grant(
            TenantContext tenant, UUID binding, String providerId) {
        return jdbc.query(GRANTS + " AND g.provider_stable_id = ?",
                (rs,n) -> rs.getBoolean(9)
                        ? new Observation<>(Presence.PRESENT, grantFact(rs))
                        : new Observation<ObservedGrantFact>(
                                rs.getBoolean(10) ? Presence.TRUSTED_ABSENT : Presence.UNKNOWN, null),
                tenant.tenantId(), binding, providerId).stream().findFirst()
                .orElse(new Observation<>(Presence.UNKNOWN, null));
    }

    private static ObservedEntitlementFact entitlementFact(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ObservedEntitlementFact(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant());
    }

    private static ObservedGrantFact grantFact(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ObservedGrantFact(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class),
                rs.getObject(7, UUID.class), rs.getTimestamp(8).toInstant());
    }

    private static void requirePageLimit(int limit) {
        if (limit < 1 || limit > 101) throw new IllegalArgumentException("observation page limit");
    }

    @Override
    public List<ObservedGrantFact> mappedGrantWitnesses(TenantContext tenant,java.util.Set<UUID> principalIds,java.util.Set<UUID> entitlementIds){
        if(principalIds.isEmpty() || entitlementIds.isEmpty()) return List.of();
        if(principalIds.size()>1000 || entitlementIds.size()>1000) throw new IllegalArgumentException("mapped witness budget");
        String principals=String.join(",",java.util.Collections.nCopies(principalIds.size(),"?"));
        String entitlements=String.join(",",java.util.Collections.nCopies(entitlementIds.size(),"?"));
        String sql=GRANTS.replace("SELECT g.connector_binding_id","SELECT DISTINCT ON (em.entitlement_id) g.connector_binding_id")
                .replace("WHERE g.tenant_id = ? AND g.connector_binding_id = ?","WHERE g.tenant_id = ?")
                +" AND g.present AND b.lifecycle_state='ACTIVE' AND pm.principal_id IN ("+principals+") AND em.entitlement_id IN ("+entitlements+")"
                +" ORDER BY em.entitlement_id,g.connector_binding_id,g.provider_stable_id";
        java.util.List<Object> args=new java.util.ArrayList<>();
        args.add(tenant.tenantId());args.addAll(principalIds);args.addAll(entitlementIds);
        return jdbc.query(sql,(rs,n)->grantFact(rs),args.toArray());
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

    private static EntitlementObservationMapping entitlementMapping(
            java.sql.ResultSet rs) throws java.sql.SQLException {
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

    private static PrincipalObservationMapping principalMapping(
            java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp retired = rs.getTimestamp("retired_at");
        return new PrincipalObservationMapping(
                rs.getObject("id", UUID.class),
                rs.getObject("connector_binding_id", UUID.class),
                rs.getString("provider_stable_id"),
                rs.getObject("principal_id", UUID.class),
                rs.getString("lifecycle_state"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                retired == null ? null : retired.toInstant());
    }
}
