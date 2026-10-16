package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.identity.application.PrincipalAdministrativeContinuityGuard;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Administration-owned continuity evaluation for disabling a Platform Owner authentication anchor. */
public final class JdbcPrincipalAdministrativeContinuityGuard
        implements PrincipalAdministrativeContinuityGuard {

    private final JdbcTemplate jdbc;
    private final PlatformOwnerAuthorityQuery platformOwners;

    public JdbcPrincipalAdministrativeContinuityGuard(
            JdbcTemplate jdbc,
            PlatformOwnerAuthorityQuery platformOwners) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.platformOwners = Objects.requireNonNull(platformOwners, "platformOwners");
    }

    @Override
    public void assertMayDisable(
            TenantContext tenant,
            UUID principalId,
            UUID identityId,
            Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(at, "at");

        Integer anchored = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM administration.platform_owner_authority
                WHERE actor_tenant_id = ?
                  AND actor_identity_id = ?
                  AND actor_principal_id = ?
                  AND state = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                """,
                Integer.class,
                tenant.tenantId(),
                identityId,
                principalId,
                Timestamp.from(at),
                Timestamp.from(at));
        if (anchored == null || anchored == 0) {
            return;
        }

        List<OwnerCandidate> alternatives = jdbc.query(
                """
                SELECT DISTINCT actor_tenant_id, actor_identity_id
                FROM administration.platform_owner_authority
                WHERE state = 'ACTIVE'
                  AND NOT (actor_tenant_id = ? AND actor_identity_id = ?)
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                """,
                (rs, rowNum) -> new OwnerCandidate(
                        rs.getObject("actor_tenant_id", UUID.class),
                        rs.getObject("actor_identity_id", UUID.class)),
                tenant.tenantId(),
                identityId,
                Timestamp.from(at),
                Timestamp.from(at));
        boolean hasAlternative = alternatives.stream().anyMatch(candidate ->
                platformOwners.isViablePlatformOwner(
                        new TenantContext(candidate.tenantId()), candidate.identityId(), at));
        if (!hasAlternative) {
            throw new IllegalStateException("cannot disable the final viable PLATFORM_OWNER principal");
        }
    }

    private record OwnerCandidate(UUID tenantId, UUID identityId) {
    }
}
