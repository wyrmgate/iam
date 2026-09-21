package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.Application;
import io.wyrmgate.iam.catalog.domain.ApplicationTarget;
import io.wyrmgate.iam.catalog.domain.Entitlement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogQueryModels {
    private CatalogQueryModels() {}

    public record PagePosition(Instant createdAt, UUID id) {}

    public record ApplicationPage(List<Application> items, PagePosition nextPosition) {
        public ApplicationPage { items = List.copyOf(items); }
    }

    public record ApplicationTargetPage(List<ApplicationTarget> items, PagePosition nextPosition) {
        public ApplicationTargetPage { items = List.copyOf(items); }
    }

    public record EntitlementPage(List<Entitlement> items, PagePosition nextPosition) {
        public EntitlementPage { items = List.copyOf(items); }
    }
}
