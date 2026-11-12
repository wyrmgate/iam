package io.wyrmgate.iam.catalog.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Current Catalog-owned semantic availability decision for a known Role or Entitlement.
 *
 * <p>Missing targets are represented by an absent query result, not by inventing another
 * availability state.</p>
 */
public record CatalogAccessTargetAvailability(
        CatalogAccessTargetKind targetKind,
        UUID targetId,
        Status status,
        CatalogAvailabilityCauseKind causeKind,
        UUID causeId,
        long causeRevision) {

    public CatalogAccessTargetAvailability {
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(causeKind, "causeKind");
        Objects.requireNonNull(causeId, "causeId");
        if (causeRevision < 1) {
            throw new IllegalArgumentException("causeRevision must be positive");
        }
    }

    public boolean assignable() {
        return status == Status.ASSIGNABLE;
    }

    public boolean terminal() {
        return status == Status.TERMINALLY_UNAVAILABLE;
    }

    public enum Status {
        ASSIGNABLE,
        TEMPORARILY_UNAVAILABLE,
        TERMINALLY_UNAVAILABLE
    }
}
