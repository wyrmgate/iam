package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.SourceAbsenceInferenceService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical polling only; Identity owns source absence process semantics. */
public final class SourceAbsenceInferenceScheduler {

    private final SourceAbsenceInferenceService service;

    public SourceAbsenceInferenceScheduler(SourceAbsenceInferenceService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.identity.source-absence.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
