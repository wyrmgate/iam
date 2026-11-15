package io.wyrmgate.iam.governance.application;

import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded ordered scans; each expiry re-reads tenant-local current authority. */
public final class RequestExpiryProcessingScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(RequestExpiryProcessingScheduler.class);
    private final AccessRequestRepository requests;
    private final RequestTerminationService termination;
    private final Clock clock;
    private AccessRequestRepository.DueItem after;
    public RequestExpiryProcessingScheduler(AccessRequestRepository requests, RequestTerminationService termination){
        this(requests,termination,Clock.systemUTC());
    }
    RequestExpiryProcessingScheduler(AccessRequestRepository requests, RequestTerminationService termination,Clock clock){
        this.requests=Objects.requireNonNull(requests);
        this.termination=Objects.requireNonNull(termination);
        this.clock=Objects.requireNonNull(clock);
    }
    @Scheduled(fixedDelayString="${iam.governance.request-expiry.poll-interval:PT1S}")
    public synchronized void processAvailable(){
        var now=clock.instant();
        var page=requests.findDueItems(now,after,50);
        for(var item:page){
            try{termination.expireIfDue(item.tenant(),item.itemId(),now);}
            catch(RuntimeException retryable){LOG.warn("Request expiry retry required: tenant={} item={}",item.tenant().tenantId(),item.itemId());}
        }
        after=page.size()==50?page.getLast():null;
    }
}
