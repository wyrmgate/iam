package io.wyrmgate.iam.catalog.domain;

import java.util.Objects;
import java.util.UUID;

/** Typed normalized member of a RoleVersion. */
public record RoleVersionMember(
        UUID id,
        UUID roleVersionId,
        MemberKind kind,
        UUID memberRoleId,
        UUID memberEntitlementId,
        int ordinal) {

    public RoleVersionMember {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(roleVersionId, "roleVersionId");
        Objects.requireNonNull(kind, "kind");
        if (kind == MemberKind.APPLICATION_ROLE) {
            Objects.requireNonNull(memberRoleId, "memberRoleId");
            if (memberEntitlementId != null) throw new IllegalArgumentException("role member must not carry entitlement");
        } else {
            Objects.requireNonNull(memberEntitlementId, "memberEntitlementId");
            if (memberRoleId != null) throw new IllegalArgumentException("entitlement member must not carry role");
        }
        if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
    }

    public enum MemberKind {
        APPLICATION_ROLE,
        ENTITLEMENT
    }
}
