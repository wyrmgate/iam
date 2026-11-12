package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Minimized committed Catalog trigger for ADR-0052 current-state re-evaluation. */
public interface CatalogAccessTargetAvailabilityFactSink {

    String ACCESS_TARGET_AVAILABILITY_CHANGED =
            "catalog.access-target-availability-changed";

    void changed(
            TenantContext tenant,
            CatalogAccessTargetKind targetKind,
            UUID targetId,
            CatalogAvailabilityCauseKind causeKind,
            UUID causeId,
            long causeRevision,
            Instant occurredAt,
            UUID correlationId,
            UUID causationId);
}
