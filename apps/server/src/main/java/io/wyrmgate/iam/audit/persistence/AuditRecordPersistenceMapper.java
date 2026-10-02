package io.wyrmgate.iam.audit.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.domain.AuditIntegrityMetadata;
import io.wyrmgate.iam.audit.domain.AuditMaterialSnapshot;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class AuditRecordPersistenceMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditRecordPersistenceMapper() {
    }

    static AuditRecord row(ResultSet rs) throws SQLException {
        return new AuditRecord(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action_type"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                AuditOutcome.valueOf(rs.getString("outcome")),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                material(rs.getString("material_snapshot")),
                integrity(rs.getString("integrity_metadata")));
    }

    static String materialJson(AuditMaterialSnapshot value) {
        if (value == null) return null;
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("schemaVersion", AuditMaterialSnapshot.SCHEMA_VERSION);
        if (value.actorDisplayLabel() != null) json.put("actorDisplayLabel", value.actorDisplayLabel());
        if (value.resourceDisplayLabel() != null) json.put("resourceDisplayLabel", value.resourceDisplayLabel());
        if (value.resourceRevision() != null) json.put("resourceRevision", value.resourceRevision());
        if (value.resourceState() != null) json.put("resourceState", value.resourceState());
        return write(json);
    }

    static String integrityJson(AuditIntegrityMetadata value) {
        if (value == null) return null;
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("schemaVersion", AuditIntegrityMetadata.SCHEMA_VERSION);
        json.put("algorithm", AuditIntegrityMetadata.ALGORITHM);
        json.put("contentSha256", value.contentSha256());
        if (value.materialSnapshotSha256() != null) {
            json.put("materialSnapshotSha256", value.materialSnapshotSha256());
        }
        return write(json);
    }

    private static AuditMaterialSnapshot material(String value) throws SQLException {
        if (value == null) return null;
        try {
            JsonNode json = JSON.readTree(value);
            if (!AuditMaterialSnapshot.SCHEMA_VERSION.equals(json.path("schemaVersion").asText())) {
                throw new SQLException("unsupported Audit material snapshot schema");
            }
            return new AuditMaterialSnapshot(
                    text(json, "actorDisplayLabel"),
                    text(json, "resourceDisplayLabel"),
                    json.has("resourceRevision") ? json.get("resourceRevision").longValue() : null,
                    text(json, "resourceState"));
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new SQLException("invalid Audit material snapshot", invalid);
        }
    }

    private static AuditIntegrityMetadata integrity(String value) throws SQLException {
        if (value == null) return null;
        try {
            JsonNode json = JSON.readTree(value);
            if (!AuditIntegrityMetadata.SCHEMA_VERSION.equals(json.path("schemaVersion").asText())
                    || !AuditIntegrityMetadata.ALGORITHM.equals(json.path("algorithm").asText())) {
                throw new SQLException("unsupported Audit integrity metadata schema");
            }
            return new AuditIntegrityMetadata(
                    json.path("contentSha256").asText(),
                    text(json, "materialSnapshotSha256"));
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new SQLException("invalid Audit integrity metadata", invalid);
        }
    }

    private static String text(JsonNode json, String field) {
        return json.has(field) && !json.get(field).isNull() ? json.get(field).asText() : null;
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("unable to encode Audit metadata", impossible);
        }
    }
}
