package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.CanonicalAttributeReadRepository;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Non-locking PostgreSQL read adapter for materialized canonical state. */
public final class JdbcCanonicalAttributeReadRepository implements CanonicalAttributeReadRepository {

    private final JdbcTemplate jdbc;

    public JdbcCanonicalAttributeReadRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<CanonicalAttributeState> findState(
            TenantContext tenant,
            UUID identityId,
            UUID attributeDefinitionId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(attributeDefinitionId, "attributeDefinitionId");
        return jdbc.query("""
                SELECT id, identity_id, attribute_definition_id, attribute_definition_version_id,
                       resolution_status, selected_candidate_id, authority_rule_version_id,
                       value_revision, created_at, updated_at
                FROM identity.canonical_attribute_state
                WHERE tenant_id = ? AND identity_id = ? AND attribute_definition_id = ?
                """, (rs, rowNum) -> state(tenant, rs), tenant.tenantId(), identityId, attributeDefinitionId)
                .stream().findFirst();
    }

    private CanonicalAttributeState state(TenantContext tenant, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new CanonicalAttributeState(
                id,
                rs.getObject("identity_id", UUID.class),
                rs.getObject("attribute_definition_id", UUID.class),
                rs.getObject("attribute_definition_version_id", UUID.class),
                CanonicalAttributeState.ResolutionStatus.valueOf(rs.getString("resolution_status")),
                rs.getObject("selected_candidate_id", UUID.class),
                rs.getObject("authority_rule_version_id", UUID.class),
                rs.getLong("value_revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                readValues(tenant, id));
    }

    private List<CanonicalValue> readValues(TenantContext tenant, UUID stateId) {
        return jdbc.query("""
                SELECT data_type, value_string, value_boolean, value_integer, value_decimal,
                       value_date, value_datetime, value_enum_key
                FROM identity.canonical_attribute_state_value
                WHERE tenant_id = ? AND state_id = ?
                ORDER BY value_ordinal
                """, (rs, rowNum) -> value(rs), tenant.tenantId(), stateId);
    }

    private static CanonicalValue value(ResultSet rs) throws SQLException {
        return switch (CanonicalAttributeType.valueOf(rs.getString("data_type"))) {
            case STRING -> new CanonicalValue.StringValue(rs.getString("value_string"));
            case BOOLEAN -> new CanonicalValue.BooleanValue(rs.getBoolean("value_boolean"));
            case INTEGER -> new CanonicalValue.IntegerValue(rs.getLong("value_integer"));
            case DECIMAL -> new CanonicalValue.DecimalValue(rs.getObject("value_decimal", BigDecimal.class));
            case DATE -> new CanonicalValue.DateValue(rs.getDate("value_date").toLocalDate());
            case DATETIME -> new CanonicalValue.DateTimeValue(rs.getTimestamp("value_datetime").toInstant());
            case ENUM -> new CanonicalValue.EnumValue(rs.getString("value_enum_key"));
        };
    }
}
