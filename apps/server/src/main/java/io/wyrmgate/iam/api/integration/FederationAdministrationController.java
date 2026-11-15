package io.wyrmgate.iam.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.*;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.*;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.integration.application.federation.*;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class FederationAdministrationController {
    private final FederationTrustRepository repo;
    private final FederationTrustCommands commands;
    private final AdministrativeAuthorizationService auth;
    private final JdbcIdempotencyRepository idem;
    private final TransactionExecutor tx;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;
    private final ObjectMapper json;
    public FederationAdministrationController(FederationTrustRepository repo,FederationTrustCommands commands,
        AdministrativeAuthorizationService auth,JdbcIdempotencyRepository idem,TransactionExecutor tx,SecurityAuditPort audit,
        IdGenerator ids,ObjectMapper json){this.repo=repo;this.commands=commands;this.auth=auth;this.idem=idem;this.tx=tx;this.audit=audit;this.ids=ids;this.json=json;}

    record CreateTrust(String issuer,String audience,OidcTrustSettings settings){}
    record CreateLink(UUID trustId,String exactSubject,UUID principalId){
        @Override public String toString(){return "CreateLink[REDACTED]";}
    }
    @PostMapping("/federation-trusts")
    ResponseEntity<?> createTrust(@RequestHeader("Idempotency-Key")String key,@RequestBody CreateTrust body,HttpServletRequest request){
        var a=ControlPlaneActorRequestContext.require(request);var c=IntegrationAdminApiRequestContext.resolve(request,ids);var now=Instant.now();
        var result=mutate(a,AdministrativePermissions.FEDERATION_TRUST_CREATE,"federation-trust",null,"create",key,body,c,now,
            ()->commands.createOidc(a.tenant(),body.issuer(),body.audience(),body.settings(),now),
            v->v.trust().id(),id->repo.findTrust(a.tenant(),id).orElseThrow());
        return response(result,result.trust().revision(),c,HttpStatus.CREATED);
    }
    @GetMapping("/federation-trusts/{id}")
    ResponseEntity<?> trust(@PathVariable UUID id,HttpServletRequest request){
        var a=ControlPlaneActorRequestContext.require(request);var c=IntegrationAdminApiRequestContext.resolve(request,ids);
        require(a,AdministrativePermissions.FEDERATION_TRUST_READ,"federation-trust",id,Instant.now(),c);
        var result=repo.findTrust(a.tenant(),id).orElseThrow(()->IntegrationAdminApiException.notFound(c));
        return response(result,result.trust().revision(),c,HttpStatus.OK);
    }
    @PostMapping("/federation-trusts/{id}:retire")
    ResponseEntity<?> retireTrust(@PathVariable UUID id,@RequestHeader("If-Match")String tag,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){
        var a=ControlPlaneActorRequestContext.require(request);var c=IntegrationAdminApiRequestContext.resolve(request,ids);var now=Instant.now();long rev=revision(tag,c);
        var result=mutate(a,AdministrativePermissions.FEDERATION_TRUST_RETIRE,"federation-trust",id,"retire",key,List.of(id,rev),c,now,
            ()->commands.retireTrust(a.tenant(),id,rev,now),v->v.trust().id(),v->repo.findTrust(a.tenant(),v).orElseThrow());
        return response(result,result.trust().revision(),c,HttpStatus.OK);
    }
    @PostMapping("/federated-subject-links")
    ResponseEntity<?> createLink(@RequestHeader("Idempotency-Key")String key,@RequestBody CreateLink body,HttpServletRequest request){
        var a=ControlPlaneActorRequestContext.require(request);var c=IntegrationAdminApiRequestContext.resolve(request,ids);var now=Instant.now();
        var result=mutate(a,AdministrativePermissions.FEDERATED_SUBJECT_LINK_CREATE,"federated-subject-link",null,"create",key,body,c,now,
            ()->commands.link(a.tenant(),body.trustId(),body.exactSubject(),body.principalId(),now),
            v->v.link().id(),id->repo.findLink(a.tenant(),id).orElseThrow());
        return response(result,result.link().revision(),c,HttpStatus.CREATED);
    }
    @GetMapping("/federated-subject-links/{id}")
    ResponseEntity<?> link(@PathVariable UUID id,HttpServletRequest request){
        var a=ControlPlaneActorRequestContext.require(request);var c=IntegrationAdminApiRequestContext.resolve(request,ids);
        require(a,AdministrativePermissions.FEDERATED_SUBJECT_LINK_READ,"federated-subject-link",id,Instant.now(),c);
        var result=repo.findLink(a.tenant(),id).orElseThrow(()->IntegrationAdminApiException.notFound(c));
        return response(result,result.link().revision(),c,HttpStatus.OK);
    }
    @PostMapping("/federated-subject-links/{id}:retire")
    ResponseEntity<?> retireLink(@PathVariable UUID id,@RequestHeader("If-Match")String tag,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){
        var a=ControlPlaneActorRequestContext.require(request);var c=IntegrationAdminApiRequestContext.resolve(request,ids);var now=Instant.now();long rev=revision(tag,c);
        var result=mutate(a,AdministrativePermissions.FEDERATED_SUBJECT_LINK_RETIRE,"federated-subject-link",id,"retire",key,List.of(id,rev),c,now,
            ()->commands.retireLink(a.tenant(),id,rev,now),v->v.link().id(),v->repo.findLink(a.tenant(),v).orElseThrow());
        return response(result,result.link().revision(),c,HttpStatus.OK);
    }
    private <T>T mutate(AuthenticatedAdministrativeActor actor,AdministrativePermission permission,String type,UUID resource,
        String action,String key,Object input,UUID correlation,Instant now,Supplier<T> command,Function<T,UUID> resultId,Function<UUID,T> replay){
        if(key==null||key.isBlank()||key.length()<8||key.length()>200)throw IntegrationAdminApiException.validation(correlation,"Invalid Idempotency-Key.");
        final RequestFingerprint fingerprint;
        try{fingerprint=RequestFingerprint.sha256(json.writeValueAsBytes(input));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("invalid input");}
        T value=tx.required(()->{
            require(actor,permission,type,resource,now,correlation);
            String namespace="api."+type+"."+action+".v1";
            var registration=idem.register(actor.tenant(),namespace,key,fingerprint,now,null);
            if(registration.kind()==JdbcIdempotencyRepository.RegistrationKind.REPLAY){
                if(!"COMPLETED".equals(registration.operationState())||!type.equals(registration.resourceType()))
                    throw IntegrationAdminApiException.conflict(correlation,"idempotency_in_progress","Operation not complete.");
                return replay.apply(registration.resourceId());
            }
            T result=command.get();UUID id=resultId.apply(result);
            // Creation expands authentication trust: mandatory evidence in the same transaction.
            if("create".equals(action))audit.append(actor.tenant(),new AuditRecordDraft(ids.nextId(),now,actor.identityId(),type+":"+action,type,id,AuditOutcome.SUCCESS,correlation,null));
            idem.complete(actor.tenant(),namespace,key,fingerprint,type,id,now);return result;
        });
        if("retire".equals(action))try{audit.append(actor.tenant(),new AuditRecordDraft(ids.nextId(),now,actor.identityId(),type+":"+action,type,resultId.apply(value),AuditOutcome.SUCCESS,correlation,null));}
        catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Federation retirement audit unavailable; correlationId={}",correlation);}
        return value;
    }
    private void require(AuthenticatedAdministrativeActor a,AdministrativePermission permission,String type,UUID id,Instant now,UUID c){
        var resource=id==null?AdministrativeResource.collection(type):new AdministrativeResource(type,id);
        if(!auth.authorize(a,permission,resource,now).allowed())throw IntegrationAdminApiException.forbidden(c);
    }
    private static long revision(String tag,UUID c){
        if(tag==null||!tag.matches("[\\x22]rev-[1-9][0-9]*[\\x22]"))throw IntegrationAdminApiException.validation(c,"Invalid If-Match.");
        try{return Long.parseLong(tag.substring(5,tag.length()-1));}catch(NumberFormatException e){throw IntegrationAdminApiException.validation(c,"Invalid If-Match.");}
    }
    private static ResponseEntity<?> response(Object value,long revision,UUID c,HttpStatus status){
        return ResponseEntity.status(status).header(HttpHeaders.ETAG,"\"rev-"+revision+"\"").header("X-Correlation-Id",c.toString()).cacheControl(CacheControl.noStore()).body(value);
    }
}
