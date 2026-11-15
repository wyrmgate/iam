package io.wyrmgate.iam.governance.application;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository.GovernanceFinding;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class GovernanceFindingQueryService {
    private final GovernanceFindingRepository findings;
    public GovernanceFindingQueryService(GovernanceFindingRepository findings){this.findings=Objects.requireNonNull(findings);}
    public Optional<GovernanceFinding> find(TenantContext tenant,UUID id){return findings.findById(tenant,id);}
    public record Position(Instant createdAt,UUID id){}
    public record Page(List<GovernanceFinding> items,Position next){
        public Page{items=List.copyOf(items);}
    }
    public Page page(TenantContext tenant,FindingContext.State state,Position after,int limit){
        if(limit<1 || limit>100) throw new IllegalArgumentException("finding page limit");
        var rows=findings.findPage(tenant,state,after==null?null:after.createdAt(),after==null?null:after.id(),limit+1);
        var selected=rows.subList(0,Math.min(limit,rows.size()));
        var next=rows.size()>limit?new Position(selected.getLast().createdAt(),selected.getLast().id()):null;
        return new Page(selected,next);
    }
}
