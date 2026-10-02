package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditIntegrityMetadata;
import io.wyrmgate.iam.audit.domain.AuditMaterialSnapshot;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Canonical ADR-0036 SHA-256 derivation for immutable AuditRecord content. */
final class AuditRecordIntegrity {

    private AuditRecordIntegrity() {
    }

    static AuditIntegrityMetadata derive(
            TenantContext tenant,
            AuditRecordDraft draft,
            Instant recordedAt) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(recordedAt, "recordedAt");
        String material = draft.materialSnapshot() == null
                ? null
                : digest(materialBytes(draft.materialSnapshot()));
        String content = digest(recordBytes(
                tenant.tenantId(),
                draft.id(),
                draft.occurredAt(),
                recordedAt,
                draft.actorId(),
                draft.actionType(),
                draft.resourceType(),
                draft.resourceId(),
                draft.outcome().name(),
                draft.correlationId(),
                draft.causationId(),
                draft.materialSnapshot()));
        return new AuditIntegrityMetadata(content, material);
    }

    static boolean verifies(TenantContext tenant, AuditRecord record) {
        if (record.integrityMetadata() == null) return true;
        AuditRecordDraft draft = new AuditRecordDraft(
                record.id(),
                record.occurredAt(),
                record.actorId(),
                record.actionType(),
                record.resourceType(),
                record.resourceId(),
                record.outcome(),
                record.correlationId(),
                record.causationId(),
                record.materialSnapshot());
        AuditIntegrityMetadata expected = derive(tenant, draft, record.recordedAt());
        return expected.equals(record.integrityMetadata());
    }

    private static byte[] recordBytes(
            UUID tenantId,
            UUID id,
            Instant occurredAt,
            Instant recordedAt,
            UUID actorId,
            String actionType,
            String resourceType,
            UUID resourceId,
            String outcome,
            UUID correlationId,
            UUID causationId,
            AuditMaterialSnapshot material) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(AuditIntegrityMetadata.SCHEMA_VERSION);
            uuid(out, tenantId);
            uuid(out, id);
            instant(out, occurredAt);
            instant(out, recordedAt);
            nullableUuid(out, actorId);
            out.writeUTF(actionType);
            out.writeUTF(resourceType);
            nullableUuid(out, resourceId);
            out.writeUTF(outcome);
            nullableUuid(out, correlationId);
            nullableUuid(out, causationId);
            if (material == null) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);
                out.write(materialBytes(material));
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("unable to canonicalize AuditRecord", impossible);
        }
    }

    private static byte[] materialBytes(AuditMaterialSnapshot material) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(AuditMaterialSnapshot.SCHEMA_VERSION);
            nullableString(out, material.actorDisplayLabel());
            nullableString(out, material.resourceDisplayLabel());
            if (material.resourceRevision() == null) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);
                out.writeLong(material.resourceRevision());
            }
            nullableString(out, material.resourceState());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("unable to canonicalize Audit material snapshot", impossible);
        }
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void nullableUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) uuid(out, value);
    }

    private static void instant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }

    private static void nullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
