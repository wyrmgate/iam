package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical polling trigger for Catalog-owned bounded retirement propagation. */
public final class CatalogAvailabilityPropagationScheduler {

    private final CatalogAvailabilityPropagationService service;

    public CatalogAvailabilityPropagationScheduler(
            CatalogAvailabilityPropagationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.catalog.availability-propagation.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
