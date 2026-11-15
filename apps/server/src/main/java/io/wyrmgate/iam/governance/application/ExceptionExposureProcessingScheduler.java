package io.wyrmgate.iam.governance.application;

import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodic bounded repair; durable finding state is the result, the cursor is only scan progress. */
public final class ExceptionExposureProcessingScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(ExceptionExposureProcessingScheduler.class);
    private final GovernanceExceptionRepository exceptions;
    private final ExceptionExposureProcessingService service;
    private final Clock clock;
    private GovernanceExceptionRepository.ExposureCandidate after;
    public ExceptionExposureProcessingScheduler(GovernanceExceptionRepository exceptions,ExceptionExposureProcessingService service){
        this(exceptions,service,Clock.systemUTC());
    }
    ExceptionExposureProcessingScheduler(GovernanceExceptionRepository exceptions,ExceptionExposureProcessingService service,Clock clock){
        this.exceptions=Objects.requireNonNull(exceptions);this.service=Objects.requireNonNull(service);this.clock=Objects.requireNonNull(clock);
    }
    @Scheduled(fixedDelayString="${iam.governance.exception-exposure.poll-interval:PT5S}")
    public synchronized void processAvailable(){
        var now=clock.instant();
        var page=exceptions.findExpiredExposurePage(now,after,50);
        for(var candidate:page){
            try{service.evaluate(candidate.tenant(),candidate.exceptionId(),now);}
            catch(RuntimeException unavailable){LOG.warn("Exception exposure evaluation unavailable: tenant={} exception={}",candidate.tenant().tenantId(),candidate.exceptionId());}
        }
        after=page.size()==50?page.getLast():null;
    }
}
