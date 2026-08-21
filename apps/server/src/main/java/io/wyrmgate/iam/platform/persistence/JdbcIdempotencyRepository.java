package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable causal idempotency storage with request-fingerprint conflict detection. */
public final class JdbcIdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;

    public JdbcIdempotencyRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public Registration register(
            TenantContext tenant,
            String operationNamespace,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant createdAt,
            Instant expiresAt) {
        Objects.requireNonNull(tenant, "tenant");
        requireText(operationNamespace, "operationNamespace");
        requireText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(createdAt, "createdAt");

        UUID recordId = idGenerator.nextId();
        int affected = jdbcTemplate.update(
                """
                INSERT INTO platform.idempotency_record (
                    id, tenant_id, operation_namespace, idempotency_key,
                    request_fingerprint, operation_state, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
                ON CONFLICT (tenant_id, operation_namespace, idempotency_key) DO NOTHING
                """,
                recordId,
                tenant.tenantId(),
                operationNamespace,
                idempotencyKey,
                fingerprint.value(),
                JdbcValues.timestamp(createdAt),
                JdbcValues.nullableTimestamp(expiresAt));

        if (affected == 1) {
            return new Registration(recordId, RegistrationKind.NEW, "IN_PROGRESS", null, null);
        }

        StoredRecord existing = jdbcTemplate.queryForObject(
                """
                SELECT id, request_fingerprint, operation_state, resource_type, resource_id
                FROM platform.idempotency_record
                WHERE tenant_id = ? AND operation_namespace = ? AND idempotency_key = ?
                """,
                (rs, rowNum) -> new StoredRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_fingerprint"),
                        rs.getString("operation_state"),
                        rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class)),
                tenant.tenantId(),
                operationNamespace,
                idempotencyKey);

        if (!existing.requestFingerprint().equals(fingerprint.value())) {
            throw new IdempotencyConflictException(operationNamespace, idempotencyKey);
        }

        return new Registration(
                existing.id(),
                RegistrationKind.REPLAY,
                existing.operationState(),
                existing.resourceType(),
                existing.resourceId());
    }

    public void complete(
            TenantContext tenant,
            String operationNamespace,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            String resourceType,
            UUID resourceId,
            Instant completedAt) {
        Objects.requireNonNull(tenant, "tenant");
        requireText(operationNamespace, "operationNamespace");
        requireText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(fingerprint, "fingerprint");
        requireText(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(completedAt, "completedAt");

        int affected = jdbcTemplate.update(
                """
                UPDATE platform.idempotency_record
                SET operation_state = 'COMPLETED', resource_type = ?, resource_id = ?, completed_at = ?
                WHERE tenant_id = ? AND operation_namespace = ? AND idempotency_key = ?
                  AND request_fingerprint = ? AND operation_state = 'IN_PROGRESS'
                """,
                resourceType,
                resourceId,
                JdbcValues.timestamp(completedAt),
                tenant.tenantId(),
                operationNamespace,
                idempotencyKey,
                fingerprint.value());
        if (affected != 1) {
            Registration registration = register(
                    tenant,
                    operationNamespace,
                    idempotencyKey,
                    fingerprint,
                    completedAt,
                    null);
            if (!"COMPLETED".equals(registration.operationState())
                    || !resourceType.equals(registration.resourceType())
                    || !resourceId.equals(registration.resourceId())) {
                throw new IllegalStateException("Idempotency record cannot be completed with a different result");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum RegistrationKind {
        NEW,
        REPLAY
    }

    public record Registration(
            UUID recordId,
            RegistrationKind kind,
            String operationState,
            String resourceType,
            UUID resourceId) {
    }

    private record StoredRecord(
            UUID id,
            String requestFingerprint,
            String operationState,
            String resourceType,
            UUID resourceId) {
    }
}
