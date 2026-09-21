package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Semantic Access-owned query used by Integration to revalidate desired-state work. */
public interface DesiredAccessStateQuery {

    Freshness current(TenantContext tenant, SubjectKind subjectKind, UUID subjectId);

    enum SubjectKind {
        DESIRED_PRINCIPAL,
        DESIRED_GRANT
    }

    enum Status {
        CURRENT,
        ABSENT,
        UNAVAILABLE
    }

    record Freshness(Status status, Long revision) {
        public Freshness {
            Objects.requireNonNull(status, "status");
            if (status == Status.CURRENT) {
                if (revision == null || revision < 1) {
                    throw new IllegalArgumentException("CURRENT freshness requires a positive revision");
                }
            } else if (revision != null) {
                throw new IllegalArgumentException(status + " freshness must not carry a revision");
            }
        }

        public static Freshness current(long revision) {
            return new Freshness(Status.CURRENT, revision);
        }

        public static Freshness absent() {
            return new Freshness(Status.ABSENT, null);
        }

        public static Freshness unavailable() {
            return new Freshness(Status.UNAVAILABLE, null);
        }
    }
}
