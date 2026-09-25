package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.domain.RoleVersion;
import io.wyrmgate.iam.catalog.domain.RoleVersionMember;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository {

    void insertRole(TenantContext tenant, Role role);

    Optional<Role> findRole(TenantContext tenant, UUID roleId);

    Role retireRole(TenantContext tenant, UUID roleId, long expectedRevision, Instant now);

    long nextVersionNumber(TenantContext tenant, UUID roleId);

    void insertVersion(
            TenantContext tenant,
            RoleVersion version,
            List<RoleVersionMember> members);

    Optional<RoleVersion> findVersion(TenantContext tenant, UUID roleVersionId);

    Optional<RoleVersion> findActiveVersion(TenantContext tenant, UUID roleId);

    List<RoleVersionMember> findMembers(TenantContext tenant, UUID roleVersionId);

    RoleVersion markReady(
            TenantContext tenant,
            UUID roleVersionId,
            Instant now);

    RoleVersion activate(
            TenantContext tenant,
            UUID roleVersionId,
            Instant now);

    List<UUID> findActiveBusinessParents(
            TenantContext tenant, UUID applicationRoleId);
}
