package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.governance.GovernanceExceptionApiModels.GovernanceExceptionResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.application.GovernanceExceptionService;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.GovernanceException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/governance/exceptions")
public final class GovernanceExceptionController {
    private final GovernanceExceptionService exceptions;
    private final GovernanceExceptionApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    public GovernanceExceptionController(
            GovernanceExceptionService exceptions,
            GovernanceExceptionApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids){
        this.exceptions=exceptions;this.mutations=mutations;this.authorization=authorization;this.ids=ids;
    }

    @PostMapping
    public ResponseEntity<GovernanceExceptionResource> request(
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        String key=idempotencyKey(idempotencyKey,cid);
        exact(body,Set.of(
                "subjectIdentityId","sodRuleId","businessReason","validFrom","validUntil",
                "predecessorExceptionId","approvalPlan"),cid);
        UUID subjectId=uuid(body.get("subjectIdentityId"),"subjectIdentityId",cid);
        UUID ruleId=uuid(body.get("sodRuleId"),"sodRuleId",cid);
        String reason=text(body.get("businessReason"),"businessReason",cid);
        Instant from=instant(body.get("validFrom"),"validFrom",cid);
        Instant until=instant(body.get("validUntil"),"validUntil",cid);
        UUID predecessor=optionalUuid(body.get("predecessorExceptionId"),"predecessorExceptionId",cid);
        PlanSpec plan=parsePlan(body.get("approvalPlan"),cid);
        var actor=ControlPlaneActorRequestContext.require(request);
        var result=mutations.request(
                actor,subjectId,ruleId,reason,from,until,predecessor,plan,key,
                fingerprintCreate(subjectId,ruleId,reason,from,until,predecessor,plan),
                Instant.now(),cid);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG,etag(result.revision()))
                .header(HttpHeaders.LOCATION,"/api/v1/governance/exceptions/"+result.id())
                .header("X-Correlation-Id",cid.toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(resource(result));
    }

    @GetMapping("/{exceptionId}")
    public ResponseEntity<GovernanceExceptionResource> get(
            @PathVariable UUID exceptionId,HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        var actor=ControlPlaneActorRequestContext.require(request);
        if(!authorization.authorize(
                actor,AdministrativePermissions.GOVERNANCE_EXCEPTION_READ,
                new AdministrativeResource("governance-exception",exceptionId),Instant.now()).allowed()){
            throw GovernanceApiException.forbidden(cid);
        }
        GovernanceException value=exceptions.find(actor.tenant(),exceptionId)
                .orElseThrow(() -> GovernanceApiException.notFound(cid));
        return response(value,cid);
    }

    @PostMapping("/{exceptionId}:revoke")
    public ResponseEntity<GovernanceExceptionResource> revoke(
            @PathVariable UUID exceptionId,
            @RequestHeader(name="If-Match",required=false) String ifMatch,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        long revision=parseIfMatch(ifMatch,cid);
        String key=idempotencyKey(idempotencyKey,cid);
        var actor=ControlPlaneActorRequestContext.require(request);
        GovernanceException value=mutations.revoke(
                actor,exceptionId,revision,key,
                fingerprint("revoke",exceptionId,revision),Instant.now(),cid);
        return response(value,cid);
    }

    private static ResponseEntity<GovernanceExceptionResource> response(
            GovernanceException value,UUID cid){
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG,etag(value.revision()))
                .header("X-Correlation-Id",cid.toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(resource(value));
    }

    private static GovernanceExceptionResource resource(GovernanceException e){
        return new GovernanceExceptionResource(
                e.id(),e.scopeKind().name(),e.subjectIdentityId(),e.sodRuleId(),
                e.requesterIdentityId(),e.businessReason(),e.validFrom(),e.validUntil(),
                e.lifecycleState().name(),e.approvalCaseId(),e.predecessorExceptionId(),
                e.revision(),e.createdAt(),e.updatedAt(),e.approvedAt(),e.rejectedAt(),
                e.revokedAt(),e.expiredAt());
    }

    private static PlanSpec parsePlan(Object raw,UUID cid){
        if(!(raw instanceof Map<?,?> rm)){
            throw GovernanceApiException.validation(cid,"approvalPlan","required_object","approvalPlan is required.");
        }
        Map<String,Object> m=stringMap(rm,cid);
        exact(m,Set.of("stages"),cid);
        if(!(m.get("stages") instanceof List<?> items) || items.isEmpty() || items.size()>20){
            throw GovernanceApiException.validation(cid,"approvalPlan.stages","invalid_size","approvalPlan stages must contain 1 to 20 entries.");
        }
        List<StageSpec> stages=new ArrayList<>();
        for(Object item:items){
            if(!(item instanceof Map<?,?> sm))throw GovernanceApiException.validation(cid,"approvalPlan.stages","invalid_shape","each stage must be an object.");
            Map<String,Object> s=stringMap(sm,cid);
            exact(s,Set.of("decisionMode","approverIdentityIds"),cid);
            DecisionMode mode=enumValue(s.get("decisionMode"),DecisionMode.class,"decisionMode",cid);
            if(!(s.get("approverIdentityIds") instanceof List<?> values) || values.isEmpty() || values.size()>100){
                throw GovernanceApiException.validation(cid,"approverIdentityIds","invalid_size","approverIdentityIds must contain 1 to 100 entries.");
            }
            List<UUID> approvers=values.stream().map(v->uuid(v,"approverIdentityIds",cid)).toList();
            try{stages.add(new StageSpec(mode,approvers));}
            catch(IllegalArgumentException invalid){throw GovernanceApiException.validation(cid,"approvalPlan","invalid_plan","approvalPlan contains invalid or duplicate approvers.");}
        }
        return new PlanSpec(stages);
    }

    private static RequestFingerprint fingerprintCreate(
            UUID subjectId,UUID ruleId,String reason,Instant from,Instant until,UUID predecessor,PlanSpec plan){
        StringBuilder b=new StringBuilder("v1|")
                .append(part(subjectId)).append(part(ruleId)).append(part(reason))
                .append(part(from)).append(part(until)).append(part(predecessor));
        for(StageSpec s:plan.stages()){
            b.append(part(s.decisionMode()));
            s.approverIdentityIds().stream().sorted(Comparator.comparing(UUID::toString))
                    .forEach(id->b.append(part(id)));
        }
        return RequestFingerprint.sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static RequestFingerprint fingerprint(Object... values){
        StringBuilder b=new StringBuilder("v1|");for(Object v:values)b.append(part(v));
        return RequestFingerprint.sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String part(Object v){String t=v==null?"<null>":v.toString();return t.length()+":"+t+"|";}
    private static String etag(long r){return "\"rev-"+r+"\"";}
    private static long parseIfMatch(String value,UUID cid){
        if(value==null || !value.matches("\\\"rev-[1-9][0-9]*\\\"")){
            throw GovernanceApiException.validation(cid,"If-Match","invalid_header","If-Match must be a strong revision ETag.");
        }
        return Long.parseLong(value.substring(5,value.length()-1));
    }
    private static String idempotencyKey(String value,UUID cid){
        if(value==null||value.isBlank()||value.length()>200)throw GovernanceApiException.validation(cid,"Idempotency-Key","invalid_header","Idempotency-Key is required and must be at most 200 characters.");
        return value;
    }
    private static void exact(Map<String,Object> m,Set<String> allowed,UUID cid){
        if(m==null)throw GovernanceApiException.validation(cid,"request","required_object","Request body must be an object.");
        for(String f:allowed)if(!m.containsKey(f))throw GovernanceApiException.validation(cid,f,"required",f+" is required.");
        Set<String> unknown=new HashSet<>(m.keySet());unknown.removeAll(allowed);
        if(!unknown.isEmpty()){String f=unknown.stream().sorted().findFirst().orElseThrow();throw GovernanceApiException.validation(cid,f,"unknown_field","Unknown field is not permitted.");}
    }
    private static Map<String,Object> stringMap(Map<?,?> raw,UUID cid){
        Map<String,Object> m=new LinkedHashMap<>();for(var e:raw.entrySet()){if(!(e.getKey() instanceof String k))throw GovernanceApiException.validation(cid,"request","invalid_object","Object keys must be strings.");m.put(k,e.getValue());}return m;
    }
    private static String text(Object raw,String f,UUID cid){if(!(raw instanceof String s)||s.isBlank())throw GovernanceApiException.validation(cid,f,"required_string",f+" must be a non-blank string.");return s;}
    private static UUID uuid(Object raw,String f,UUID cid){if(!(raw instanceof String s))throw GovernanceApiException.validation(cid,f,"required_uuid",f+" must be a UUID.");try{return UUID.fromString(s);}catch(IllegalArgumentException e){throw GovernanceApiException.validation(cid,f,"invalid_uuid",f+" must be a UUID.");}}
    private static UUID optionalUuid(Object raw,String f,UUID cid){return raw==null?null:uuid(raw,f,cid);}
    private static Instant instant(Object raw,String f,UUID cid){if(!(raw instanceof String s))throw GovernanceApiException.validation(cid,f,"required_datetime",f+" must be an ISO instant.");try{return Instant.parse(s);}catch(DateTimeParseException e){throw GovernanceApiException.validation(cid,f,"invalid_datetime",f+" must be an ISO instant.");}}
    private static <E extends Enum<E>> E enumValue(Object raw,Class<E> type,String f,UUID cid){if(!(raw instanceof String s))throw GovernanceApiException.validation(cid,f,"required_enum",f+" must be a string enum.");try{return Enum.valueOf(type,s);}catch(IllegalArgumentException e){throw GovernanceApiException.validation(cid,f,"invalid_enum",f+" contains an unsupported value.");}}
}
