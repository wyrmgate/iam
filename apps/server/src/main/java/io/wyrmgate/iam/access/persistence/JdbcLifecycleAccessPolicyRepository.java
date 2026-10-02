package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.LifecycleAccessPolicyRepository;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.LifecycleAccessPolicyVersion;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcLifecycleAccessPolicyRepository implements LifecycleAccessPolicyRepository {

    private final JdbcTemplate jdbc;

    public JdbcLifecycleAccessPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LifecycleAccessPolicyVersion replaceActive(
            TenantContext tenant,
            List<LifecycleAccessPolicyVersion.Rule> rules,
            Instant activatedAt,
            UUID policyVersionId) {
        Long next = jdbc.queryForObject(
                "SELECT COALESCE(max(version_number),0)+1 FROM access.lifecycle_access_policy_version WHERE tenant_id=?",
                Long.class, tenant.tenantId());
        jdbc.update("""
                UPDATE access.lifecycle_access_policy_version
                SET state='SUPERSEDED', superseded_at=?
                WHERE tenant_id=? AND state='ACTIVE'
                """, Timestamp.from(activatedAt), tenant.tenantId());
        jdbc.update("""
                INSERT INTO access.lifecycle_access_policy_version
                    (id,tenant_id,version_number,state,created_at,activated_at)
                VALUES (?,?,?,'ACTIVE',?,?)
                """, policyVersionId, tenant.tenantId(), next,
                Timestamp.from(activatedAt), Timestamp.from(activatedAt));
        for (var rule : rules) {
            jdbc.update("""
                    INSERT INTO access.lifecycle_access_policy_rule
                        (policy_version_id,tenant_id,rule_id,predicate_kind,
                         canonical_key,expected_string,expected_boolean,expected_integer,
                         expected_decimal,expected_date,expected_datetime,expected_enum,target_kind,target_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    policyVersionId, tenant.tenantId(), rule.ruleId(), rule.predicateKind().name(),
                    rule.canonicalKey(), rule.expectedString(), rule.expectedBoolean(),
                    rule.expectedInteger(), rule.expectedDecimal(),
                    rule.expectedDate() == null ? null : Date.valueOf(rule.expectedDate()),
                    rule.expectedDateTime() == null ? null : Timestamp.from(rule.expectedDateTime()),
                    rule.expectedEnum(), rule.targetKind().name(), rule.targetId());
        }
        return findActive(tenant).orElseThrow();
    }

    @Override
    public Optional<LifecycleAccessPolicyVersion> findActive(TenantContext tenant) {
        return jdbc.query("""
                SELECT id,version_number,state,created_at,activated_at,superseded_at
                FROM access.lifecycle_access_policy_version
                WHERE tenant_id=? AND state='ACTIVE'
                """,
                (rs,row) -> {
                    UUID id=rs.getObject("id",UUID.class);
                    List<LifecycleAccessPolicyVersion.Rule> rules=jdbc.query("""
                            SELECT rule_id,predicate_kind,canonical_key,expected_string,
                                   expected_boolean,expected_integer,expected_decimal,
                                   expected_date,expected_datetime,expected_enum,target_kind,target_id
                            FROM access.lifecycle_access_policy_rule
                            WHERE tenant_id=? AND policy_version_id=?
                            ORDER BY rule_id
                            """,
                            (rr,n)->new LifecycleAccessPolicyVersion.Rule(
                                    rr.getObject("rule_id",UUID.class),
                                    LifecycleAccessPolicyVersion.PredicateKind.valueOf(rr.getString("predicate_kind")),
                                    rr.getString("canonical_key"),
                                    rr.getString("expected_string"),
                                    rr.getObject("expected_boolean", Boolean.class),
                                    rr.getObject("expected_integer", Long.class),
                                    rr.getObject("expected_decimal", BigDecimal.class),
                                    rr.getObject("expected_date", LocalDate.class),
                                    rr.getTimestamp("expected_datetime") == null
                                            ? null
                                            : rr.getTimestamp("expected_datetime").toInstant(),
                                    rr.getString("expected_enum"),
                                    AccessAssignment.TargetKind.valueOf(rr.getString("target_kind")),
                                    rr.getObject("target_id",UUID.class)),
                            tenant.tenantId(),id);
                    return new LifecycleAccessPolicyVersion(
                            id,rs.getLong("version_number"),
                            LifecycleAccessPolicyVersion.State.valueOf(rs.getString("state")),
                            rules,rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("activated_at").toInstant(),
                            rs.getTimestamp("superseded_at")==null?null:rs.getTimestamp("superseded_at").toInstant());
                },tenant.tenantId()).stream().findFirst();
    }
}
