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

    default boolean supportsPolicyMultiAttribute(
            TenantContext tenant, String canonicalKey, ScalarType type) {
        return false;
    }

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
        DECIMAL,
        DATE,
        DATETIME,
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
                    case DECIMAL -> value instanceof java.math.BigDecimal;
                    case DATE -> value instanceof java.time.LocalDate;
                    case DATETIME -> value instanceof java.time.Instant;
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

    record CanonicalMulti(
            boolean trusted,
            ScalarType type,
            java.util.List<Object> values,
            long valueRevision) {
        public CanonicalMulti {
            if (trusted) {
                Objects.requireNonNull(type, "type");
                values = java.util.List.copyOf(Objects.requireNonNull(values, "values"));
                if (values.isEmpty() || valueRevision < 1 || values.stream().anyMatch(value -> !matches(type, value))) {
                    throw new IllegalArgumentException("trusted multi requires matching non-empty typed values/revision");
                }
            } else {
                values = java.util.List.of();
                if (type != null || valueRevision != 0) {
                    throw new IllegalArgumentException("untrusted multi must not carry type/revision");
                }
            }
        }

        private static boolean matches(ScalarType type, Object value) {
            return switch (type) {
                case STRING, ENUM -> value instanceof String;
                case BOOLEAN -> value instanceof Boolean;
                case INTEGER -> value instanceof Long;
                case DECIMAL -> value instanceof java.math.BigDecimal;
                case DATE -> value instanceof java.time.LocalDate;
                case DATETIME -> value instanceof java.time.Instant;
            };
        }

        public static CanonicalMulti trusted(ScalarType type, java.util.List<?> values, long revision) {
            return new CanonicalMulti(true, type, java.util.List.copyOf(values), revision);
        }

        public static CanonicalMulti unavailable() {
            return new CanonicalMulti(false, null, java.util.List.of(), 0);
        }
    }

    record Context(
            Status status,
            String lifecycleState,
            long identityRevision,
            Map<String, CanonicalScalar> canonicalScalars,
            Map<String, CanonicalMulti> canonicalMultis) {
        public Context {
            Objects.requireNonNull(status, "status");
            canonicalScalars = Map.copyOf(Objects.requireNonNull(canonicalScalars, "canonicalScalars"));
            canonicalMultis = Map.copyOf(Objects.requireNonNull(canonicalMultis, "canonicalMultis"));
            if (status == Status.AVAILABLE) {
                if (lifecycleState == null || lifecycleState.isBlank() || identityRevision < 1) {
                    throw new IllegalArgumentException("available Identity context requires lifecycle/revision");
                }
            } else if (lifecycleState != null || identityRevision != 0
                    || !canonicalScalars.isEmpty() || !canonicalMultis.isEmpty()) {
                throw new IllegalArgumentException("NOT_FOUND context must not carry state");
            }
        }

        public Context(
                Status status,
                String lifecycleState,
                long identityRevision,
                Map<String, CanonicalScalar> canonicalScalars) {
            this(status, lifecycleState, identityRevision, canonicalScalars, Map.of());
        }

        public static Context notFound() {
            return new Context(Status.NOT_FOUND, null, 0, Map.of(), Map.of());
        }
    }
}
