package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Narrow Identity semantic query for Access lifecycle policy evaluation. */
public interface IdentityLifecycleAccessQuery {

    boolean supportsPolicyScalarAttribute(
            TenantContext tenant, String canonicalKey, ScalarType type);

    Context currentContext(
            TenantContext tenant,
            UUID identityId,
            Set<String> canonicalKeys);

    enum Status {
        NOT_FOUND,
        AVAILABLE
    }

    enum ScalarType {
        STRING,
        BOOLEAN,
        INTEGER,
        ENUM
    }

    record CanonicalScalar(
            boolean trusted,
            ScalarType type,
            Object value,
            long valueRevision) {
        public CanonicalScalar {
            if (trusted) {
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(value, "value");
                boolean valid = switch (type) {
                    case STRING, ENUM -> value instanceof String;
                    case BOOLEAN -> value instanceof Boolean;
                    case INTEGER -> value instanceof Long;
                };
                if (!valid || valueRevision < 1) {
                    throw new IllegalArgumentException("trusted scalar requires matching typed value/revision");
                }
            } else if (type != null || value != null || valueRevision != 0) {
                throw new IllegalArgumentException("untrusted scalar must not carry type/value/revision");
            }
        }

        public static CanonicalScalar trusted(ScalarType type, Object value, long revision) {
            return new CanonicalScalar(true, type, value, revision);
        }

        public static CanonicalScalar unavailable() {
            return new CanonicalScalar(false, null, null, 0);
        }
    }

    record Context(
            Status status,
            String lifecycleState,
            long identityRevision,
            Map<String, CanonicalScalar> canonicalScalars) {
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
