package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.administration.application.TenantOwnerOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.application.TenantLifecycleContinuityGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Administration-owned owner-continuity decisions consumed by Tenancy. */
public final class JdbcTenantLifecycleContinuityGuard
        implements TenantLifecycleContinuityGuard {

    private final JdbcTemplate jdbc;
    private final TenantOwnerOverviewQuery tenantOwners;
    private final PlatformOwnerAuthorityQuery platformOwners;

    public JdbcTenantLifecycleContinuityGuard(
            JdbcTemplate jdbc,
            TenantOwnerOverviewQuery tenantOwners,
            PlatformOwnerAuthorityQuery platformOwners) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tenantOwners = Objects.requireNonNull(tenantOwners, "tenantOwners");
        this.platformOwners = Objects.requireNonNull(platformOwners, "platformOwners");
    }

    @Override
    public void assertMayBecomeActive(TenantContext tenant, Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(at, "at");
        if (tenantOwners.snapshot(tenant, at, 1).viableOwnerCount() < 1) {
            throw new IllegalStateException(
                    "Tenant activation requires a viable TENANT_OWNER");
        }
    }

    @Override
    public void assertMaySuspendOrDecommission(TenantContext tenant, Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(at, "at");

        List<PlatformOwnerCandidate> candidates = jdbc.query(
                """
                SELECT actor_tenant_id, actor_identity_id, actor_principal_id
                FROM administration.platform_owner_authority
                WHERE state = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                ORDER BY actor_tenant_id, actor_identity_id
                """,
                (rs, rowNum) -> new PlatformOwnerCandidate(
                        rs.getObject("actor_tenant_id", UUID.class),
                        rs.getObject("actor_identity_id", UUID.class),
                        rs.getObject("actor_principal_id", UUID.class)),
                Timestamp.from(at),
                Timestamp.from(at));

        boolean survivingOwner = candidates.stream().anyMatch(candidate -> {
            boolean affectedFirstPartyAnchor =
                    candidate.tenantId().equals(tenant.tenantId())
                            && candidate.principalId() != null;
            if (affectedFirstPartyAnchor) {
                return false;
            }
            return platformOwners.isViablePlatformOwner(
                    new TenantContext(candidate.tenantId()),
                    candidate.identityId(),
                    at);
        });
        if (!survivingOwner) {
            throw new IllegalStateException(
                    "Tenant suspension or decommissioning would remove the final viable PLATFORM_OWNER");
        }
    }

    private record PlatformOwnerCandidate(
            UUID tenantId,
            UUID identityId,
            UUID principalId) {
    }
}
