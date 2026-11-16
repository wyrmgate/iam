package io.wyrmgate.iam.api.integration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.integration.persistence.IntegrationReconciliationCommands;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class ReconciliationCommandController {
    private final IntegrationReconciliationCommands commands;private final AdministrativeAuthorizationService auth;
    private final JdbcIdempotencyRepository idem;private final TransactionExecutor tx;private final SecurityAuditPort audit;
    private final IdGenerator ids;private final ObjectMapper json;
    public ReconciliationCommandController(IntegrationReconciliationCommands commands,AdministrativeAuthorizationService auth,
        JdbcIdempotencyRepository idem,TransactionExecutor tx,SecurityAuditPort audit,IdGenerator ids,ObjectMapper json){
        this.commands=commands;this.auth=auth;this.idem=idem;this.tx=tx;this.audit=audit;this.ids=ids;this.json=json;
    }
    record ScheduleRequest(String objectClass,String scopeMode,String checkpoint){}
    @PostMapping("/connector-bindings/{id}:reconcile")
    ResponseEntity<?> schedule(@PathVariable UUID id,@RequestHeader("If-Match")String tag,@RequestHeader("Idempotency-Key")String key,
        @RequestBody ScheduleRequest body,HttpServletRequest request){
        var actor=ControlPlaneActorRequestContext.require(request);UUID correlation=IntegrationAdminApiRequestContext.resolve(request,ids);
        if(tag==null||!tag.matches("[\\x22]rev-[1-9][0-9]*[\\x22]")||key==null||key.isBlank()||key.length()<8||key.length()>200)
            throw IntegrationAdminApiException.validation(correlation,"Invalid revision or idempotency key.");
        long revision;RequestFingerprint fingerprint;
        try{revision=Long.parseLong(tag.substring(5,tag.length()-1));fingerprint=RequestFingerprint.sha256(json.writeValueAsBytes(List.of(id,revision,body)));}
        catch(Exception invalid){throw IntegrationAdminApiException.validation(correlation,"Invalid reconciliation request.");}
        Instant now=Instant.now();
        var result=tx.required(()->{
            if(!auth.authorize(actor,AdministrativePermissions.CONNECTOR_BINDING_RECONCILE,new AdministrativeResource("connector-binding",id),now).allowed())
                throw IntegrationAdminApiException.forbidden(correlation);
            String namespace="api.connector-binding.reconcile.v1";
            var registered=idem.register(actor.tenant(),namespace,key,fingerprint,now,null);
            if(registered.kind()==JdbcIdempotencyRepository.RegistrationKind.REPLAY){
                if(!"COMPLETED".equals(registered.operationState())||!"reconciliation-run".equals(registered.resourceType()))
                    throw IntegrationAdminApiException.conflict(correlation,"idempotency_in_progress","Operation not complete.");
                return commands.read(actor.tenant(),registered.resourceId());
            }
            var run=commands.schedule(actor.tenant(),id,revision,body.objectClass(),body.scopeMode(),body.checkpoint(),now,correlation);
            audit.append(actor.tenant(),new AuditRecordDraft(ids.nextId(),now,actor.identityId(),"connector-binding:reconcile",
                "reconciliation-run",run.id(),AuditOutcome.SUCCESS,correlation,null));
            idem.complete(actor.tenant(),namespace,key,fingerprint,"reconciliation-run",run.id(),now);
            return run;
        });
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.LOCATION,"/api/v1/reconciliation-runs/"+result.id())
            .header("X-Correlation-Id",correlation.toString()).cacheControl(CacheControl.noStore()).body(result);
    }
}
