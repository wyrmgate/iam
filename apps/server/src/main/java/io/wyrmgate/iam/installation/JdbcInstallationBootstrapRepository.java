package io.wyrmgate.iam.installation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for the single deployment-scoped installation bootstrap process. */
public final class JdbcInstallationBootstrapRepository implements InstallationBootstrapRepository {

    private final JdbcTemplate jdbc;

    public JdbcInstallationBootstrapRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<InstallationBootstrapPlan> find() {
        List<InstallationBootstrapPlan> rows = jdbc.query(
                """
                SELECT state, tenant_id, tenant_display_name,
                       identity_id, identity_display_name,
                       console_application_id, console_target_id,
                       console_sso_registration_id, console_client_id,
                       console_redirect_uri, principal_id, principal_key,
                       credential_id, correlation_id, bootstrap_version,
                       started_at, updated_at, sealed_at
                FROM platform.installation_bootstrap
                WHERE singleton_key = true
                """,
                (rs, rowNum) -> new InstallationBootstrapPlan(
                        InstallationBootstrapState.valueOf(rs.getString("state")),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_display_name"),
                        rs.getObject("identity_id", UUID.class),
                        rs.getString("identity_display_name"),
                        rs.getObject("console_application_id", UUID.class),
                        rs.getObject("console_target_id", UUID.class),
                        rs.getObject("console_sso_registration_id", UUID.class),
                        rs.getString("console_client_id"),
                        rs.getString("console_redirect_uri"),
                        rs.getObject("principal_id", UUID.class),
                        rs.getString("principal_key"),
                        rs.getObject("credential_id", UUID.class),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getInt("bootstrap_version"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("sealed_at") == null
                                ? null
                                : rs.getTimestamp("sealed_at").toInstant()));
        return rows.stream().findFirst();
    }

    @Override
    public boolean createPlan(InstallationBootstrapPlan plan) {
        int affected = jdbc.update(
                """
                INSERT INTO platform.installation_bootstrap (
                    singleton_key, state,
                    tenant_id, tenant_display_name,
                    identity_id, identity_display_name,
                    console_application_id, console_target_id,
                    console_sso_registration_id, console_client_id,
                    console_redirect_uri, principal_id, principal_key,
                    credential_id, correlation_id, bootstrap_version,
                    started_at, updated_at, sealed_at)
                VALUES (true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (singleton_key) DO NOTHING
                """,
                plan.state().name(),
                plan.tenantId(),
                plan.tenantDisplayName(),
                plan.identityId(),
                plan.identityDisplayName(),
                plan.consoleApplicationId(),
                plan.consoleTargetId(),
                plan.consoleSsoRegistrationId(),
                plan.consoleClientId(),
                plan.consoleRedirectUri(),
                plan.principalId(),
                plan.principalKey(),
                plan.credentialId(),
                plan.correlationId(),
                plan.bootstrapVersion(),
                Timestamp.from(plan.startedAt()),
                Timestamp.from(plan.updatedAt()),
                plan.sealedAt() == null ? null : Timestamp.from(plan.sealedAt()));
        return affected == 1;
    }

    @Override
    public boolean advance(
            InstallationBootstrapState expected,
            InstallationBootstrapState next,
            Instant now) {
        if (expected == InstallationBootstrapState.SEALED) {
            throw new IllegalStateException("SEALED installation bootstrap is terminal");
        }
        int affected = jdbc.update(
                """
                UPDATE platform.installation_bootstrap
                SET state = ?, updated_at = ?, sealed_at = ?
                WHERE singleton_key = true AND state = ?
                """,
                next.name(),
                Timestamp.from(now),
                next == InstallationBootstrapState.SEALED ? Timestamp.from(now) : null,
                expected.name());
        return affected == 1;
    }
}
