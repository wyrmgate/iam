package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeDelegation;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Administration-owned mutation/query port for direct AdministrativeRole/Grant management. */
public interface AdministrativeAuthorityRepository {

    AdministrativeRole createRole(
            TenantContext tenant,
            UUID id,
            String code,
            String name,
            Set<AdministrativePermission> permissions,
            Instant now);

    Optional<AdministrativeRole> findRole(TenantContext tenant, UUID roleId);

    AdministrativeRole renameRole(
            TenantContext tenant,
            UUID roleId,
            String name,
            long expectedRevision,
            Instant now);

    AdministrativeRole addRolePermission(
            TenantContext tenant,
            UUID roleId,
            AdministrativePermission permission,
            long expectedRevision,
            Instant now);

    AdministrativeRole removeRolePermission(
            TenantContext tenant,
            UUID roleId,
            AdministrativePermission permission,
            long expectedRevision,
            Instant now);

    List<AdministrativeRole> listRoles(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    Optional<AdministrativeGrant> findGrant(TenantContext tenant, UUID grantId);

    AdministrativeGrant createGrant(
            TenantContext tenant,
            UUID id,
            UUID actorIdentityId,
            UUID roleId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            boolean grantable,
            boolean delegable,
            UUID authorityBasisGrantId,
            Instant now);

    AdministrativeGrant revokeGrant(
            TenantContext tenant,
            UUID grantId,
            long expectedRevision,
            Instant now);

    List<AdministrativeGrant> listGrants(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    List<AdministrativeGrant> findAuthorityBearingGrantsByRole(
            TenantContext tenant,
            UUID roleId,
            Instant now,
            int limit);

    List<AdministrativeGrant> findActorGrants(
            TenantContext tenant,
            UUID actorIdentityId,
            int limit);

    Optional<AdministrativeDelegation> findDelegation(TenantContext tenant, UUID delegationId);

    AdministrativeDelegation createDelegation(
            TenantContext tenant,
            UUID id,
            UUID delegateIdentityId,
            UUID delegatorIdentityId,
            UUID sourceGrantId,
            UUID roleId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            UUID createdByIdentityId,
            UUID correlationId,
            UUID causationId,
            Instant now);

    AdministrativeDelegation revokeDelegation(
            TenantContext tenant,
            UUID delegationId,
            long expectedRevision,
            UUID revokedByIdentityId,
            Instant now);

    List<AdministrativeDelegation> listDelegations(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    List<AdministrativeDelegation> findAuthorityBearingDelegationsByRole(
            TenantContext tenant,
            UUID roleId,
            Instant now,
            int limit);

}
