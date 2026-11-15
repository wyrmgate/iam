package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.FindingContext;
import io.wyrmgate.iam.governance.application.GovernanceFindingQueryService;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository.GovernanceFinding;
import io.wyrmgate.iam.governance.application.ReviewQueryModels;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/governance/findings")
public final class GovernanceFindingController {
    private final GovernanceFindingQueryService findings;
    private final AdministrativeAuthorizationService authorization;
    private final GovernanceCursorCodec cursors;
    private final IdGenerator ids;
    public GovernanceFindingController(GovernanceFindingQueryService findings,AdministrativeAuthorizationService authorization,
            GovernanceCursorCodec cursors,IdGenerator ids){
        this.findings=findings;this.authorization=authorization;this.cursors=cursors;this.ids=ids;
    }

    @GetMapping
    public ResponseEntity<FindingPage> page(@RequestParam(required=false) FindingContext.State state,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit,HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        var actor=ControlPlaneActorRequestContext.require(request);
        if(!authorization.authorize(actor,AdministrativePermissions.GOVERNANCE_FINDING_READ,
                AdministrativeResource.collection("governance-finding"),Instant.now()).allowed()) throw GovernanceApiException.forbidden(cid);
        int size=limit==null?50:limit;
        if(size<1 || size>100) throw GovernanceApiException.validation(cid,"limit","invalid_limit","limit must be between 1 and 100.");
        String scope=state==null?"all":state.name();
        GovernanceFindingQueryService.Position after=null;
        if(cursor!=null){
            try{
                var decoded=cursors.decodeFindings(cursor,actor.tenant(),scope);
                after=new GovernanceFindingQueryService.Position(decoded.createdAt(),decoded.id());
            }catch(IllegalArgumentException invalid){
                throw GovernanceApiException.validation(cid,"cursor","invalid_cursor","Invalid finding cursor.");
            }
        }
        var page=findings.page(actor.tenant(),state,after,size);
        String next=page.next()==null?null:cursors.encodeFindings(actor.tenant(),scope,
                new ReviewQueryModels.Position(page.next().createdAt(),page.next().id()));
        return ResponseEntity.ok().header("X-Correlation-Id",cid.toString()).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(new FindingPage(page.items().stream().map(GovernanceFindingController::resource).toList(),next));
    }

    @GetMapping("/{findingId}")
    public ResponseEntity<FindingResource> get(@PathVariable UUID findingId,HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        var actor=ControlPlaneActorRequestContext.require(request);
        if(!authorization.authorize(actor,AdministrativePermissions.GOVERNANCE_FINDING_READ,
                new AdministrativeResource("governance-finding",findingId),Instant.now()).allowed()) throw GovernanceApiException.forbidden(cid);
        var finding=findings.find(actor.tenant(),findingId).orElseThrow(()->GovernanceApiException.notFound(cid));
        return ResponseEntity.ok().header("X-Correlation-Id",cid.toString()).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.ETAG,"\"rev-"+finding.revision()+"\"").body(resource(finding));
    }

    private static FindingResource resource(GovernanceFinding f){
        return new FindingResource(f.id(),FindingContext.Type.valueOf(f.findingType()),FindingContext.Subject.valueOf(f.subjectKind()),
                FindingContext.State.valueOf(f.lifecycleState()),f.connectorBindingId(),f.providerStableId(),f.relatedProviderStableId(),f.context(),
                f.firstObservedAt(),f.lastObservedAt(),f.resolvedAt(),f.revision(),f.createdAt(),f.updatedAt());
    }
    public record FindingResource(UUID id,FindingContext.Type findingType,FindingContext.Subject subjectKind,FindingContext.State state,
            UUID connectorBindingId,String providerStableId,String relatedProviderStableId,FindingContext context,
            Instant firstObservedAt,Instant lastObservedAt,Instant resolvedAt,long revision,Instant createdAt,Instant updatedAt){}
    public record FindingPage(List<FindingResource> items,String nextCursor){}
}
