package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Canonical fingerprint of the exact Catalog meaning admitted by Access/Governance. */
public final class AccessTargetFingerprint {
    private AccessTargetFingerprint() {}

    public static String entitlement(UUID entitlementId, UUID targetId) {
        return digest("ENTITLEMENT|" + entitlementId + "|" + targetId);
    }

    public static String role(RoleExpansionQuery.Result result) {
        if (result.status() != RoleExpansionQuery.Status.AVAILABLE || result.paths().isEmpty()) {
            throw new IllegalArgumentException("Role must have a current nonempty expansion");
        }
        var canonical = new StringBuilder("ROLE|").append(result.roleId());
        result.paths().stream().map(path -> path.entitlementId() + "|" + path.applicationTargetId()
                + "|" + path.roleVersionPath()).sorted().forEach(path -> canonical.append(';').append(path));
        return digest(canonical.toString());
    }

    private static String digest(String value) {
        return RequestFingerprint.sha256(value.getBytes(StandardCharsets.UTF_8)).value();
    }
}
