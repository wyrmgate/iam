package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Narrow Identity semantic query for Access lifecycle policy evaluation. */
public interface IdentityLifecycleAccessQuery {

    boolean supportsPolicySingleStringAttribute(
            TenantContext tenant, String canonicalKey);

    Context currentContext(
            TenantContext tenant,
            UUID identityId,
            Set<String> canonicalKeys);

    enum Status {
        NOT_FOUND,
        AVAILABLE
    }

    record CanonicalString(
            boolean trusted,
            String value,
            long valueRevision) {
        public CanonicalString {
            if (trusted) {
                Objects.requireNonNull(value, "value");
                if (valueRevision < 1) {
                    throw new IllegalArgumentException("trusted value requires positive revision");
                }
            } else if (value != null || valueRevision != 0) {
                throw new IllegalArgumentException("untrusted value must not carry value/revision");
            }
        }

        public static CanonicalString trusted(String value, long revision) {
            return new CanonicalString(true, value, revision);
        }

        public static CanonicalString unavailable() {
            return new CanonicalString(false, null, 0);
        }
    }

    record Context(
            Status status,
            String lifecycleState,
            long identityRevision,
            Map<String, CanonicalString> canonicalStrings) {
        public Context {
            Objects.requireNonNull(status, "status");
            canonicalStrings = Map.copyOf(Objects.requireNonNull(canonicalStrings, "canonicalStrings"));
            if (status == Status.AVAILABLE) {
                if (lifecycleState == null || lifecycleState.isBlank() || identityRevision < 1) {
                    throw new IllegalArgumentException("available Identity context requires lifecycle/revision");
                }
            } else if (lifecycleState != null || identityRevision != 0 || !canonicalStrings.isEmpty()) {
                throw new IllegalArgumentException("NOT_FOUND context must not carry state");
            }
        }

        public static Context notFound() {
            return new Context(Status.NOT_FOUND, null, 0, Map.of());
        }
    }
}
