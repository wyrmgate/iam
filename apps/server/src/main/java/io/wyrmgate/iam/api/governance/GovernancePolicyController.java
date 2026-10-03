package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.governance.GovernancePolicyApiModels.ApprovalStageResource;
import io.wyrmgate.iam.api.governance.GovernancePolicyApiModels.PolicyVersionResource;
import io.wyrmgate.iam.api.governance.GovernancePolicyApiModels.SoDRuleResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.application.GovernancePolicyService;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.*;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/governance/policies/access-request")
public final class GovernancePolicyController {
    private final GovernancePolicyService policies;
    private final GovernancePolicyApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    public GovernancePolicyController(
            GovernancePolicyService policies,
            GovernancePolicyApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids){
        this.policies=policies;this.mutations=mutations;this.authorization=authorization;this.ids=ids;
    }

    @GetMapping("/active")
    public ResponseEntity<PolicyVersionResource> active(HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        var actor=ControlPlaneActorRequestContext.require(request);
        requireRead(actor,cid);
        var snapshot=policies.findActiveSnapshot(actor.tenant(),PolicyKind.ACCESS_REQUEST)
                .orElseThrow(() -> GovernanceApiException.notFound(cid));
        return response(snapshot,cid);
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<PolicyVersionResource> get(
            @PathVariable UUID versionId,HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        var actor=ControlPlaneActorRequestContext.require(request);
        requireRead(actor,cid);
        var snapshot=policies.findSnapshot(actor.tenant(),versionId)
                .orElseThrow(() -> GovernanceApiException.notFound(cid));
        return response(snapshot,cid);
    }

    @PostMapping("/versions")
    public ResponseEntity<PolicyVersionResource> create(
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        String key=idempotencyKey(idempotencyKey,cid);
        exact(body,Set.of("defaultDecision","rules","approvalPlan"),cid);
        PolicyDecision decision=enumValue(body.get("defaultDecision"),PolicyDecision.class,"defaultDecision",cid);
        List<SoDRuleSpec> rules=parseRules(body.get("rules"),cid);
        PlanSpec plan=parsePlan(body.get("approvalPlan"),cid);
        var actor=ControlPlaneActorRequestContext.require(request);
        var result=mutations.createDraft(
                actor,decision,rules,plan,key,fingerprintCreate(decision,rules,plan),Instant.now(),cid);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG,etag(result.version().revision()))
                .header(HttpHeaders.LOCATION,
                        "/api/v1/governance/policies/access-request/versions/"+result.version().id())
                .header("X-Correlation-Id",cid.toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(resource(result));
    }

    @PostMapping("/versions/{versionId}:ready")
    public ResponseEntity<PolicyVersionResource> ready(
            @PathVariable UUID versionId,
            @RequestHeader(name="If-Match",required=false) String ifMatch,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            HttpServletRequest request){
        return transition(versionId,ifMatch,idempotencyKey,"ready",request);
    }

    @PostMapping("/versions/{versionId}:activate")
    public ResponseEntity<PolicyVersionResource> activate(
            @PathVariable UUID versionId,
            @RequestHeader(name="If-Match",required=false) String ifMatch,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            HttpServletRequest request){
        return transition(versionId,ifMatch,idempotencyKey,"activate",request);
    }

    @PostMapping("/versions/{versionId}:cancel")
    public ResponseEntity<PolicyVersionResource> cancel(
            @PathVariable UUID versionId,
            @RequestHeader(name="If-Match",required=false) String ifMatch,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            HttpServletRequest request){
        return transition(versionId,ifMatch,idempotencyKey,"cancel",request);
    }

    private ResponseEntity<PolicyVersionResource> transition(
            UUID versionId,String ifMatch,String idempotencyKey,String action,HttpServletRequest request){
        UUID cid=GovernanceApiRequestContext.resolveCorrelationId(request,ids);
        long revision=parseIfMatch(ifMatch,cid);
        String key=idempotencyKey(idempotencyKey,cid);
        var actor=ControlPlaneActorRequestContext.require(request);
        RequestFingerprint fp=fingerprint(action,versionId,revision);
        PolicySnapshot result=switch(action){
            case "ready" -> mutations.markReady(actor,versionId,revision,key,fp,Instant.now(),cid);
            case "activate" -> mutations.activate(actor,versionId,revision,key,fp,Instant.now(),cid);
            case "cancel" -> mutations.cancel(actor,versionId,revision,key,fp,Instant.now(),cid);
            default -> throw new IllegalStateException("unsupported policy transition");
        };
        return response(result,cid);
    }

    private void requireRead(AuthenticatedAdministrativeActor actor,UUID cid){
        if(!authorization.authorize(
                actor,AdministrativePermissions.GOVERNANCE_POLICY_READ,
                AdministrativeResource.collection("governance-policy"),Instant.now()).allowed()){
            throw GovernanceApiException.forbidden(cid);
        }
    }

    private static ResponseEntity<PolicyVersionResource> response(PolicySnapshot s,UUID cid){
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG,etag(s.version().revision()))
                .header("X-Correlation-Id",cid.toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(resource(s));
    }

    private static PolicyVersionResource resource(PolicySnapshot s){
        PolicyVersion v=s.version();
        return new PolicyVersionResource(
                v.id(),v.policyId(),v.versionNumber(),v.state().name(),v.defaultDecision().name(),v.revision(),
                s.rules().stream().map(r -> new SoDRuleResource(
                        r.id(),r.code(),r.leftEntitlementId(),r.rightEntitlementId(),
                        r.severity().name(),r.action().name(),r.createdAt())).toList(),
                s.approvalStages().stream().map(st -> new ApprovalStageResource(
                        st.stage().id(),st.stage().ordinal(),st.stage().decisionMode().name(),
                        st.approvers().stream().map(a->a.approverIdentityId()).toList(),
                        st.stage().createdAt())).toList(),
                v.createdAt(),v.updatedAt(),v.activatedAt(),v.supersededAt());
    }

    private static List<SoDRuleSpec> parseRules(Object raw,UUID cid){
        if(!(raw instanceof List<?> items) || items.size()>1000){
            throw GovernanceApiException.validation(cid,"rules","invalid_size","rules must be an array with at most 1000 entries.");
        }
        List<SoDRuleSpec> result=new ArrayList<>();
        for(Object item:items){
            if(!(item instanceof Map<?,?> rm)) throw GovernanceApiException.validation(cid,"rules","invalid_shape","each rule must be an object.");
            Map<String,Object> m=stringMap(rm,cid);
            exact(m,Set.of("code","leftEntitlementId","rightEntitlementId","severity","action"),cid);
            result.add(new SoDRuleSpec(
                    text(m.get("code"),"code",cid),
                    uuid(m.get("leftEntitlementId"),"leftEntitlementId",cid),
                    uuid(m.get("rightEntitlementId"),"rightEntitlementId",cid),
                    enumValue(m.get("severity"),RiskSeverity.class,"severity",cid),
                    enumValue(m.get("action"),SoDAction.class,"action",cid)));
        }
        result.sort(Comparator.comparing(SoDRuleSpec::code));
        return List.copyOf(result);
    }

    private static PlanSpec parsePlan(Object raw,UUID cid){
        if(raw==null) return null;
        if(!(raw instanceof Map<?,?> rm)) throw GovernanceApiException.validation(cid,"approvalPlan","invalid_shape","approvalPlan must be an object or null.");
        Map<String,Object> m=stringMap(rm,cid);
        exact(m,Set.of("stages"),cid);
        Object stagesRaw=m.get("stages");
        if(!(stagesRaw instanceof List<?> items) || items.isEmpty() || items.size()>20){
            throw GovernanceApiException.validation(cid,"approvalPlan.stages","invalid_size","approvalPlan stages must contain 1 to 20 entries.");
        }
        List<StageSpec> stages=new ArrayList<>();
        for(Object item:items){
            if(!(item instanceof Map<?,?> sm)) throw GovernanceApiException.validation(cid,"approvalPlan.stages","invalid_shape","each stage must be an object.");
            Map<String,Object> s=stringMap(sm,cid);
            exact(s,Set.of("decisionMode","approverIdentityIds"),cid);
            DecisionMode mode=enumValue(s.get("decisionMode"),DecisionMode.class,"decisionMode",cid);
            Object idsRaw=s.get("approverIdentityIds");
            if(!(idsRaw instanceof List<?> idItems) || idItems.isEmpty() || idItems.size()>100){
                throw GovernanceApiException.validation(cid,"approverIdentityIds","invalid_size","approverIdentityIds must contain 1 to 100 entries.");
            }
            List<UUID> approvers=idItems.stream().map(x->uuid(x,"approverIdentityIds",cid)).toList();
            try{stages.add(new StageSpec(mode,approvers));}
            catch(IllegalArgumentException invalid){throw GovernanceApiException.validation(cid,"approvalPlan","invalid_plan","approvalPlan contains invalid or duplicate approvers.");}
        }
        return new PlanSpec(stages);
    }

    private static RequestFingerprint fingerprintCreate(PolicyDecision d,List<SoDRuleSpec> rules,PlanSpec plan){
        StringBuilder b=new StringBuilder("v1|").append(part(d.name()));
        for(var r:rules)b.append(part(r.code())).append(part(r.leftEntitlementId())).append(part(r.rightEntitlementId())).append(part(r.severity())).append(part(r.action()));
        if(plan==null)b.append(part(null));
        else for(var s:plan.stages()){
            b.append(part(s.decisionMode()));
            s.approverIdentityIds().stream().sorted(Comparator.comparing(UUID::toString)).forEach(id->b.append(part(id)));
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
        if(value==null || value.isBlank() || value.length()>200) throw GovernanceApiException.validation(cid,"Idempotency-Key","invalid_header","Idempotency-Key is required and must be at most 200 characters.");
        return value;
    }
    private static void exact(Map<String,Object> m,Set<String> allowed,UUID cid){
        if(m==null) throw GovernanceApiException.validation(cid,"request","required_object","Request body must be an object.");
        for(String f:allowed)if(!m.containsKey(f))throw GovernanceApiException.validation(cid,f,"required",f+" is required.");
        Set<String> unknown=new HashSet<>(m.keySet());unknown.removeAll(allowed);
        if(!unknown.isEmpty()){String f=unknown.stream().sorted().findFirst().orElseThrow();throw GovernanceApiException.validation(cid,f,"unknown_field","Unknown field is not permitted.");}
    }
    private static Map<String,Object> stringMap(Map<?,?> raw,UUID cid){
        Map<String,Object> m=new LinkedHashMap<>();for(var e:raw.entrySet()){if(!(e.getKey() instanceof String k))throw GovernanceApiException.validation(cid,"request","invalid_object","Object keys must be strings.");m.put(k,e.getValue());}return m;
    }
    private static String text(Object raw,String f,UUID cid){if(!(raw instanceof String s)||s.isBlank())throw GovernanceApiException.validation(cid,f,"required_string",f+" must be a non-blank string.");return s;}
    private static UUID uuid(Object raw,String f,UUID cid){if(!(raw instanceof String s))throw GovernanceApiException.validation(cid,f,"required_uuid",f+" must be a UUID.");try{return UUID.fromString(s);}catch(IllegalArgumentException e){throw GovernanceApiException.validation(cid,f,"invalid_uuid",f+" must be a UUID.");}}
    private static <E extends Enum<E>> E enumValue(Object raw,Class<E> type,String f,UUID cid){if(!(raw instanceof String s))throw GovernanceApiException.validation(cid,f,"required_enum",f+" must be a string enum.");try{return Enum.valueOf(type,s);}catch(IllegalArgumentException e){throw GovernanceApiException.validation(cid,f,"invalid_enum",f+" contains an unsupported value.");}}
}
