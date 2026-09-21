package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcIntegrationAdministrationRepository
        implements IntegrationAdministrationRepository {

    private static final TypeReference<Map<String,Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcIntegrationAdministrationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public ConnectorInstance createConnector(
            TenantContext tenant, UUID id, String connectorType, String runtimeId,
            String runtimeVersion, long configurationVersion, Map<String,Object> configuration,
            String secretReference, Instant now) {
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type, runtime_id, runtime_version,
                    configuration_version, configuration_json, secret_reference,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'ACTIVE', 1, ?, ?)
                """,
                id, tenant.tenantId(), connectorType, runtimeId, runtimeVersion,
                configurationVersion, writeJson(configuration), nullableText(secretReference),
                Timestamp.from(now), Timestamp.from(now));
        return findConnector(tenant, id).orElseThrow();
    }

    @Override
    public Optional<ConnectorInstance> findConnector(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, connector_type, runtime_id, runtime_version, configuration_version,
                       configuration_json::text, secret_reference IS NOT NULL,
                       lifecycle_state, revision, created_at, updated_at
                FROM integration.connector_instance
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> new ConnectorInstance(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), readMap(rs.getString(6)), rs.getBoolean(7), rs.getString(8),
                        rs.getLong(9), rs.getTimestamp(10).toInstant(), rs.getTimestamp(11).toInstant()),
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public ConnectorInstance updateConnector(
            TenantContext tenant, UUID id, String runtimeId, String runtimeVersion,
            long configurationVersion, Map<String,Object> configuration, String secretReference,
            long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE integration.connector_instance
                SET runtime_id = ?, runtime_version = ?, configuration_version = ?,
                    configuration_json = ?::jsonb, secret_reference = ?,
                    revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """,
                runtimeId, runtimeVersion, configurationVersion, writeJson(configuration),
                nullableText(secretReference), Timestamp.from(now),
                tenant.tenantId(), id, expectedRevision);
        requireUpdated(affected, "connector", tenant, id, expectedRevision);
        return findConnector(tenant, id).orElseThrow();
    }

    @Override
    public ConnectorInstance disableConnector(
            TenantContext tenant, UUID id, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE integration.connector_instance
                SET lifecycle_state = 'DISABLED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, Timestamp.from(now), tenant.tenantId(), id, expectedRevision);
        requireUpdated(affected, "connector", tenant, id, expectedRevision);
        return findConnector(tenant, id).orElseThrow();
    }

    @Override
    public ConnectorBinding createBinding(
            TenantContext tenant, UUID id, UUID connectorInstanceId, String targetKind,
            UUID targetId, String contractId, int contractVersion,
            boolean supportsCompletePrincipalDiscovery,
            boolean supportsCompleteEntitlementDiscovery,
            boolean supportsCompleteGrantDiscovery,
            Instant now) {
        requireActiveConnector(tenant, connectorInstanceId);
        jdbc.update("""
                INSERT INTO integration.connector_binding (
                    id, tenant_id, connector_instance_id, target_kind, target_id,
                    contract_id, contract_version,
                    supports_complete_principal_discovery,
                    supports_complete_entitlement_discovery,
                    supports_complete_grant_discovery,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """,
                id, tenant.tenantId(), connectorInstanceId, targetKind, targetId,
                contractId, contractVersion,
                supportsCompletePrincipalDiscovery,
                supportsCompleteEntitlementDiscovery,
                supportsCompleteGrantDiscovery,
                Timestamp.from(now), Timestamp.from(now));
        return findBinding(tenant, id).orElseThrow();
    }

    @Override
    public Optional<ConnectorBinding> findBinding(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, connector_instance_id, target_kind, target_id, contract_id,
                       contract_version,
                       supports_complete_principal_discovery,
                       supports_complete_entitlement_discovery,
                       supports_complete_grant_discovery,
                       lifecycle_state, revision, created_at, updated_at
                FROM integration.connector_binding
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> new ConnectorBinding(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getInt(6), rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9),
                        rs.getString(10), rs.getLong(11),
                        rs.getTimestamp(12).toInstant(), rs.getTimestamp(13).toInstant()),
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public ConnectorBinding updateBinding(
            TenantContext tenant, UUID id, String contractId, int contractVersion,
            boolean supportsCompletePrincipalDiscovery,
            boolean supportsCompleteEntitlementDiscovery,
            boolean supportsCompleteGrantDiscovery,
            long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE integration.connector_binding
                SET contract_id = ?, contract_version = ?,
                    supports_complete_principal_discovery = ?,
                    supports_complete_entitlement_discovery = ?,
                    supports_complete_grant_discovery = ?,
                    revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """,
                contractId, contractVersion,
                supportsCompletePrincipalDiscovery,
                supportsCompleteEntitlementDiscovery,
                supportsCompleteGrantDiscovery,
                Timestamp.from(now), tenant.tenantId(), id, expectedRevision);
        requireUpdated(affected, "connector-binding", tenant, id, expectedRevision);
        return findBinding(tenant, id).orElseThrow();
    }

    @Override
    public ConnectorBinding disableBinding(
            TenantContext tenant, UUID id, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE integration.connector_binding
                SET lifecycle_state = 'DISABLED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, Timestamp.from(now), tenant.tenantId(), id, expectedRevision);
        requireUpdated(affected, "connector-binding", tenant, id, expectedRevision);
        return findBinding(tenant, id).orElseThrow();
    }

    @Override
    public ConnectorWorker createWorker(
            TenantContext tenant, UUID id, WorkerExternalSubject externalSubject,
            int protocolMajorMin, int protocolMajorMax, List<UUID> bindingScope,
            List<WorkerPermissionSpec> permissions, Instant now) {
        validateWorkerScopeAndPermissions(tenant, bindingScope, permissions);
        try {
            jdbc.update("""
                    INSERT INTO integration.connector_worker_registration (
                        id, tenant_id, external_subject_key, issuer, subject, state,
                        protocol_major_min, protocol_major_max, revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'ENABLED', ?, ?, 1, ?, ?)
                    """,
                    id, tenant.tenantId(), JdbcIntegrationRuntimeRepository.subjectKey(externalSubject),
                    externalSubject.issuer(), externalSubject.subject(),
                    protocolMajorMin, protocolMajorMax, Timestamp.from(now), Timestamp.from(now));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IntegrationAdministrationException(
                    "external_subject_already_registered",
                    "The external worker subject is already registered.");
        }
        replaceWorkerChildren(tenant, id, bindingScope, permissions, now);
        return findWorker(tenant, id).orElseThrow();
    }

    @Override
    public Optional<ConnectorWorker> findWorker(TenantContext tenant, UUID id) {
        record Base(UUID id, String issuer, String subject, String state, int min, int max,
                    long revision, Instant created, Instant updated) {}
        List<Base> bases = jdbc.query("""
                SELECT id, issuer, subject, state, protocol_major_min, protocol_major_max,
                       revision, created_at, updated_at
                FROM integration.connector_worker_registration
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> new Base(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6), rs.getLong(7),
                        rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant()),
                tenant.tenantId(), id);
        if (bases.isEmpty()) return Optional.empty();

        List<UUID> bindings = jdbc.query("""
                SELECT connector_binding_id
                FROM integration.connector_worker_binding_scope
                WHERE tenant_id = ? AND worker_registration_id = ?
                ORDER BY connector_binding_id
                """, (rs,row) -> rs.getObject(1, UUID.class), tenant.tenantId(), id);
        List<WorkerPermissionSpec> permissions = jdbc.query("""
                SELECT runtime_id, runtime_version, capability, contract_id, contract_version
                FROM integration.connector_worker_runtime_permission
                WHERE tenant_id = ? AND worker_registration_id = ?
                ORDER BY runtime_id, runtime_version, capability, contract_id, contract_version
                """,
                (rs,row) -> new WorkerPermissionSpec(
                        rs.getString(1), rs.getString(2),
                        WorkerCapability.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getInt(5)),
                tenant.tenantId(), id);
        Base b = bases.getFirst();
        return Optional.of(new ConnectorWorker(
                b.id(), b.issuer(), b.subject(), b.state(), b.min(), b.max(),
                List.copyOf(bindings), List.copyOf(permissions),
                b.revision(), b.created(), b.updated()));
    }

    @Override
    public ConnectorWorker updateWorker(
            TenantContext tenant, UUID id, int protocolMajorMin, int protocolMajorMax,
            List<UUID> bindingScope, List<WorkerPermissionSpec> permissions,
            long expectedRevision, Instant now) {
        validateWorkerScopeAndPermissions(tenant, bindingScope, permissions);
        int affected = jdbc.update("""
                UPDATE integration.connector_worker_registration
                SET protocol_major_min = ?, protocol_major_max = ?,
                    revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'ENABLED'
                """,
                protocolMajorMin, protocolMajorMax, Timestamp.from(now),
                tenant.tenantId(), id, expectedRevision);
        requireUpdated(affected, "connector-worker", tenant, id, expectedRevision);
        invalidateWorkerSessions(tenant, id);
        replaceWorkerChildren(tenant, id, bindingScope, permissions, now);
        return findWorker(tenant, id).orElseThrow();
    }

    @Override
    public ConnectorWorker disableWorker(
            TenantContext tenant, UUID id, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE integration.connector_worker_registration
                SET state = 'DISABLED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'ENABLED'
                """, Timestamp.from(now), tenant.tenantId(), id, expectedRevision);
        requireUpdated(affected, "connector-worker", tenant, id, expectedRevision);
        invalidateWorkerSessions(tenant, id);
        return findWorker(tenant, id).orElseThrow();
    }

    private void invalidateWorkerSessions(TenantContext tenant, UUID workerId) {
        jdbc.update("""
                DELETE FROM integration.connector_worker_session_contract
                WHERE tenant_id = ? AND session_id IN (
                    SELECT id FROM integration.connector_worker_session
                    WHERE tenant_id = ? AND worker_registration_id = ?
                )
                """, tenant.tenantId(), tenant.tenantId(), workerId);
        jdbc.update("""
                DELETE FROM integration.connector_worker_session_capability
                WHERE tenant_id = ? AND session_id IN (
                    SELECT id FROM integration.connector_worker_session
                    WHERE tenant_id = ? AND worker_registration_id = ?
                )
                """, tenant.tenantId(), tenant.tenantId(), workerId);
        jdbc.update("""
                DELETE FROM integration.connector_worker_session
                WHERE tenant_id = ? AND worker_registration_id = ?
                """, tenant.tenantId(), workerId);
    }

    private void replaceWorkerChildren(
            TenantContext tenant, UUID workerId, List<UUID> bindingScope,
            List<WorkerPermissionSpec> permissions, Instant now) {
        jdbc.update("""
                DELETE FROM integration.connector_worker_binding_scope
                WHERE tenant_id = ? AND worker_registration_id = ?
                """, tenant.tenantId(), workerId);
        jdbc.update("""
                DELETE FROM integration.connector_worker_runtime_permission
                WHERE tenant_id = ? AND worker_registration_id = ?
                """, tenant.tenantId(), workerId);
        for (UUID bindingId : bindingScope.stream().distinct().toList()) {
            jdbc.update("""
                    INSERT INTO integration.connector_worker_binding_scope (
                        tenant_id, worker_registration_id, connector_binding_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """, tenant.tenantId(), workerId, bindingId, Timestamp.from(now));
        }
        for (WorkerPermissionSpec permission : permissions.stream().distinct().toList()) {
            jdbc.update("""
                    INSERT INTO integration.connector_worker_runtime_permission (
                        tenant_id, worker_registration_id, runtime_id, runtime_version,
                        capability, contract_id, contract_version, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenant.tenantId(), workerId, permission.runtimeId(), permission.runtimeVersion(),
                    permission.capability().name(), permission.contractId(),
                    permission.contractVersion(), Timestamp.from(now));
        }
    }

    private void validateWorkerScopeAndPermissions(
            TenantContext tenant, List<UUID> bindingScope, List<WorkerPermissionSpec> permissions) {
        if (bindingScope == null || bindingScope.isEmpty()) {
            throw new IntegrationAdministrationException(
                    "worker_scope_required", "At least one connector binding is required.");
        }
        if (permissions == null || permissions.isEmpty()) {
            throw new IntegrationAdministrationException(
                    "worker_permission_required", "At least one runtime permission is required.");
        }
        for (UUID bindingId : bindingScope.stream().distinct().toList()) {
            Boolean active = jdbc.query("""
                    SELECT true FROM integration.connector_binding
                    WHERE tenant_id = ? AND id = ? AND lifecycle_state = 'ACTIVE'
                    """, (rs,row) -> Boolean.TRUE, tenant.tenantId(), bindingId)
                    .stream().findFirst().orElse(false);
            if (!active) {
                throw new IntegrationAdministrationException(
                        "invalid_worker_scope", "Worker scope references a missing or inactive connector binding.");
            }
        }
        for (WorkerPermissionSpec permission : permissions) {
            boolean compatible = false;
            for (UUID bindingId : bindingScope.stream().distinct().toList()) {
                Integer count = jdbc.queryForObject("""
                        SELECT count(*)
                        FROM integration.connector_binding b
                        JOIN integration.connector_instance ci
                          ON ci.tenant_id = b.tenant_id AND ci.id = b.connector_instance_id
                        WHERE b.tenant_id = ?
                          AND b.id = ?
                          AND b.lifecycle_state = 'ACTIVE'
                          AND ci.lifecycle_state = 'ACTIVE'
                          AND ci.runtime_id = ?
                          AND ci.runtime_version = ?
                          AND b.contract_id = ?
                          AND b.contract_version = ?
                        """,
                        Integer.class,
                        tenant.tenantId(),
                        bindingId,
                        permission.runtimeId(),
                        permission.runtimeVersion(),
                        permission.contractId(),
                        permission.contractVersion());
                if (count != null && count > 0) {
                    compatible = true;
                    break;
                }
            }
            if (!compatible) {
                throw new IntegrationAdministrationException(
                        "worker_permission_outside_scope",
                        "Worker runtime permission is not compatible with any scoped connector binding.");
            }
        }
    }

    private void requireActiveConnector(TenantContext tenant, UUID connectorId) {
        Boolean active = jdbc.query("""
                SELECT true FROM integration.connector_instance
                WHERE tenant_id = ? AND id = ? AND lifecycle_state = 'ACTIVE'
                """, (rs,row) -> Boolean.TRUE, tenant.tenantId(), connectorId)
                .stream().findFirst().orElse(false);
        if (!active) {
            throw new IntegrationAdministrationException(
                    "connector_not_found", "Connector was not found or is not active.");
        }
    }

    private void requireUpdated(
            int affected, String resourceType, TenantContext tenant, UUID id, long expectedRevision) {
        if (affected == 1) return;
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM integration." + table(resourceType) + " WHERE tenant_id = ? AND id = ?",
                Integer.class, tenant.tenantId(), id);
        if (count == null || count == 0) {
            throw new IntegrationAdministrationException("not_found", "The requested resource was not found.");
        }
        throw new StaleWriteException(resourceType, id, expectedRevision);
    }

    private static String table(String resourceType) {
        return switch (resourceType) {
            case "connector" -> "connector_instance";
            case "connector-binding" -> "connector_binding";
            case "connector-worker" -> "connector_worker_registration";
            default -> throw new IllegalArgumentException("unsupported resource type");
        };
    }

    private Map<String,Object> readMap(String value) {
        try {
            return value == null ? Map.of() : json.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("persisted connector configuration is invalid", invalid);
        }
    }

    private String writeJson(Map<String,Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("connector configuration is not JSON serializable", invalid);
        }
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
