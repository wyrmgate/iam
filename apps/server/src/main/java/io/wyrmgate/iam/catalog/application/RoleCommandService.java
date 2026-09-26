package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.Entitlement;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.domain.RoleVersion;
import io.wyrmgate.iam.catalog.domain.RoleVersionMember;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RoleCommandService {

    private final CatalogRepository catalog;
    private final RoleRepository roles;
    private final RoleExpansionFactSink facts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public RoleCommandService(
            CatalogRepository catalog,
            RoleRepository roles,
            RoleExpansionFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Role createRole(
            TenantContext tenant,
            Role.RoleType type,
            UUID applicationId,
            String code,
            String name,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(now, "now");
        if (type == Role.RoleType.APPLICATION) {
            var application = catalog.findApplication(tenant, applicationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "application does not exist"));
            if (application.lifecycleState() != CatalogLifecycleState.ACTIVE) {
                throw new IllegalArgumentException("application is retired");
            }
        } else if (applicationId != null) {
            throw new IllegalArgumentException(
                    "BUSINESS role must not carry applicationId");
        }

        Role role = new Role(
                ids.nextId(),
                type,
                applicationId,
                code,
                name,
                CatalogLifecycleState.ACTIVE,
                1,
                now,
                now);
        return transactions.required(() -> {
            roles.insertRole(tenant, role);
            return roles.findRole(tenant, role.id()).orElseThrow();
        });
    }

    public RoleVersion createDraftVersion(
            TenantContext tenant,
            UUID roleId,
            List<MemberSpec> members,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(now, "now");
        if (members.isEmpty()) {
            throw new IllegalArgumentException(
                    "RoleVersion must contain at least one member");
        }
        Role role = requireActiveRole(tenant, roleId);
        requireUniqueMembers(members);
        validateMembers(tenant, role, members, false);

        List<MemberSpec> canonical = members.stream()
                .sorted(Comparator
                        .comparing((MemberSpec member) -> member.kind().name())
                        .thenComparing(member -> member.targetId().toString()))
                .toList();
        String canonicalText = canonical.stream()
                .map(member -> member.kind().name() + ":" + member.targetId())
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
        String contentHash = RequestFingerprint.sha256(
                canonicalText.getBytes(StandardCharsets.UTF_8)).value();

        return transactions.required(() -> {
            long versionNumber = roles.nextVersionNumber(tenant, roleId);
            RoleVersion version = new RoleVersion(
                    ids.nextId(),
                    roleId,
                    versionNumber,
                    RoleVersion.State.DRAFT,
                    contentHash,
                    1,
                    null,
                    now,
                    now);
            List<RoleVersionMember> persisted = new ArrayList<>();
            for (int ordinal = 0; ordinal < members.size(); ordinal++) {
                MemberSpec member = members.get(ordinal);
                persisted.add(new RoleVersionMember(
                        ids.nextId(),
                        version.id(),
                        member.kind(),
                        member.kind() == RoleVersionMember.MemberKind.APPLICATION_ROLE
                                ? member.targetId() : null,
                        member.kind() == RoleVersionMember.MemberKind.ENTITLEMENT
                                ? member.targetId() : null,
                        ordinal));
            }
            roles.insertVersion(tenant, version, persisted);
            return roles.findVersion(tenant, version.id()).orElseThrow();
        });
    }

    public RoleVersion markReady(
            TenantContext tenant,
            UUID roleVersionId,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            RoleVersion version = roles.findVersion(tenant, roleVersionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "role version does not exist"));
            if (version.state() != RoleVersion.State.DRAFT) {
                throw new IllegalStateException(
                        "only DRAFT RoleVersion can become READY");
            }
            Role role = requireActiveRole(tenant, version.roleId());
            validatePersistedVersion(tenant, role, version, true);
            return roles.markReady(tenant, roleVersionId, now);
        });
    }

    public RoleVersion activate(
            TenantContext tenant,
            UUID roleVersionId,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            RoleVersion version = roles.findVersion(tenant, roleVersionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "role version does not exist"));
            if (version.state() != RoleVersion.State.READY) {
                throw new IllegalStateException(
                        "only READY RoleVersion can be activated");
            }
            Role role = requireActiveRole(tenant, version.roleId());
            validatePersistedVersion(tenant, role, version, true);
            RoleVersion activated = roles.activate(
                    tenant, roleVersionId, now);
            UUID correlationId = ids.nextId();
            publishRoleAndParents(
                    tenant, role, now, correlationId, activated.id());
            return activated;
        });
    }

    public Role retireRole(
            TenantContext tenant,
            UUID roleId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            Role current = requireActiveRole(tenant, roleId);
            List<UUID> parents = current.type() == Role.RoleType.APPLICATION
                    ? roles.findActiveBusinessParents(tenant, current.id())
                    : List.of();
            Role retired = roles.retireRole(
                    tenant, roleId, expectedRevision, now);
            UUID correlationId = ids.nextId();
            facts.changed(
                    tenant,
                    retired.id(),
                    retired.revision(),
                    now,
                    correlationId,
                    null);
            for (UUID parentId : parents) {
                roles.findRole(tenant, parentId).ifPresent(parent ->
                        facts.changed(
                                tenant,
                                parent.id(),
                                parent.revision(),
                                now,
                                correlationId,
                                retired.id()));
            }
            return retired;
        });
    }

    private void publishRoleAndParents(
            TenantContext tenant,
            Role role,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        facts.changed(
                tenant,
                role.id(),
                role.revision(),
                now,
                correlationId,
                causationId);
        if (role.type() != Role.RoleType.APPLICATION) return;
        for (UUID parentId : roles.findActiveBusinessParents(
                tenant, role.id())) {
            roles.findRole(tenant, parentId).ifPresent(parent ->
                    facts.changed(
                            tenant,
                            parent.id(),
                            parent.revision(),
                            now,
                            correlationId,
                            causationId));
        }
    }

    private Role requireActiveRole(TenantContext tenant, UUID roleId) {
        Role role = roles.findRole(tenant, roleId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "role does not exist"));
        if (role.lifecycleState() != CatalogLifecycleState.ACTIVE) {
            throw new IllegalArgumentException("role is retired");
        }
        return role;
    }

    private void validatePersistedVersion(
            TenantContext tenant,
            Role role,
            RoleVersion version,
            boolean requireCurrentChildVersion) {
        List<MemberSpec> members = roles.findMembers(tenant, version.id())
                .stream()
                .map(member -> new MemberSpec(
                        member.kind(),
                        member.kind() == RoleVersionMember.MemberKind.APPLICATION_ROLE
                                ? member.memberRoleId()
                                : member.memberEntitlementId()))
                .toList();
        validateMembers(
                tenant, role, members, requireCurrentChildVersion);
    }

    private void validateMembers(
            TenantContext tenant,
            Role role,
            List<MemberSpec> members,
            boolean requireCurrentChildVersion) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException(
                    "RoleVersion must contain at least one member");
        }
        requireUniqueMembers(members);
        for (MemberSpec member : members) {
            if (role.type() == Role.RoleType.APPLICATION
                    && member.kind()
                            != RoleVersionMember.MemberKind.ENTITLEMENT) {
                throw new IllegalArgumentException(
                        "APPLICATION Role may contain only Entitlements");
            }
            if (member.kind()
                    == RoleVersionMember.MemberKind.ENTITLEMENT) {
                Entitlement entitlement = catalog.findEntitlement(
                                tenant, member.targetId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "role entitlement does not exist"));
                if (entitlement.lifecycleState()
                        != CatalogLifecycleState.ACTIVE) {
                    throw new IllegalArgumentException(
                            "role entitlement is retired");
                }
                if (entitlement.applicationTargetId() == null) {
                    throw new IllegalArgumentException(
                            "role entitlement must be target-scoped");
                }
                var target = catalog.findTarget(
                                tenant, entitlement.applicationTargetId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "role entitlement target does not exist"));
                if (target.lifecycleState()
                        != CatalogLifecycleState.ACTIVE) {
                    throw new IllegalArgumentException(
                            "role entitlement target is retired");
                }
                if (role.type() == Role.RoleType.APPLICATION
                        && !role.applicationId().equals(
                                entitlement.applicationId())) {
                    throw new IllegalArgumentException(
                            "APPLICATION Role entitlement belongs to another Application");
                }
                continue;
            }

            if (role.type() != Role.RoleType.BUSINESS) {
                throw new IllegalArgumentException(
                        "only BUSINESS Role may contain APPLICATION Role");
            }
            Role child = roles.findRole(tenant, member.targetId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "member role does not exist"));
            if (child.type() != Role.RoleType.APPLICATION) {
                throw new IllegalArgumentException(
                        "BUSINESS Role may contain only APPLICATION Roles");
            }
            if (child.lifecycleState() != CatalogLifecycleState.ACTIVE) {
                throw new IllegalArgumentException(
                        "member APPLICATION Role is retired");
            }
            if (requireCurrentChildVersion) {
                RoleVersion childVersion = roles.findActiveVersion(
                                tenant, child.id())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "member APPLICATION Role has no active version"));
                validatePersistedVersion(
                        tenant, child, childVersion, false);
            }
        }
    }

    private static void requireUniqueMembers(List<MemberSpec> members) {
        Set<String> unique = new HashSet<>();
        for (MemberSpec member : members) {
            Objects.requireNonNull(member, "member");
            String key = member.kind().name() + ":" + member.targetId();
            if (!unique.add(key)) {
                throw new IllegalArgumentException(
                        "RoleVersion contains duplicate member " + key);
            }
        }
    }

    public record MemberSpec(
            RoleVersionMember.MemberKind kind,
            UUID targetId) {
        public MemberSpec {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(targetId, "targetId");
        }

        public static MemberSpec entitlement(UUID entitlementId) {
            return new MemberSpec(
                    RoleVersionMember.MemberKind.ENTITLEMENT,
                    entitlementId);
        }

        public static MemberSpec applicationRole(UUID roleId) {
            return new MemberSpec(
                    RoleVersionMember.MemberKind.APPLICATION_ROLE,
                    roleId);
        }
    }
}
