package io.wyrmgate.iam.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.IntegrationOperationalPermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.integration.persistence.VerifiedProvisioningRecovery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class ProvisioningRecoveryController {
    private final VerifiedProvisioningRecovery recovery;
    private final AdministrativeAuthorizationService authorization;
    private final JdbcIdempotencyRepository idem;
    private final TransactionExecutor tx;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;
    private final ObjectMapper json;

    public ProvisioningRecoveryController(VerifiedProvisioningRecovery recovery, AdministrativeAuthorizationService authorization,
            JdbcIdempotencyRepository idem, TransactionExecutor tx, SecurityAuditPort audit, IdGenerator ids, ObjectMapper json) {
        this.recovery=recovery;this.authorization=authorization;this.idem=idem;this.tx=tx;this.audit=audit;this.ids=ids;this.json=json;
    }

    record RecoverRequest(UUID reconciliationRunId, UUID principalId, Instant executionQuiescedAt) {}

    @PostMapping("/provisioning-tasks/{id}:recover")
    ResponseEntity<VerifiedProvisioningRecovery.Decision> recover(@PathVariable UUID id,
            @RequestHeader("If-Match") String tag,@RequestHeader("Idempotency-Key") String key,
            @RequestBody RecoverRequest body,HttpServletRequest request) {
        var actor=ControlPlaneActorRequestContext.require(request);
        UUID correlation=IntegrationAdminApiRequestContext.resolve(request,ids);
        if(tag==null||!tag.matches("[\\x22]rev-[1-9][0-9]*[\\x22]")||key==null||key.isBlank()
                ||key.length()<8||key.length()>200||body==null||body.reconciliationRunId()==null||body.executionQuiescedAt()==null)
            throw IntegrationAdminApiException.validation(correlation,"Invalid recovery request.");
        long revision;RequestFingerprint fingerprint;
        try{revision=Long.parseLong(tag.substring(5,tag.length()-1));fingerprint=RequestFingerprint.sha256(json.writeValueAsBytes(List.of(id,revision,body)));}
        catch(Exception invalid){throw IntegrationAdminApiException.validation(correlation,"Invalid recovery request.");}
        Instant now=Instant.now();
        var result=tx.required(()->{
            if(!authorization.authorize(actor,AdministrativePermissions.PROVISIONING_TASK_RECOVER,
                    new AdministrativeResource("provisioning-task",id),now).allowed()
                ||!authorization.authorize(actor,IntegrationOperationalPermissions.RECONCILIATION_RUN_READ,
                    new AdministrativeResource("reconciliation-run",body.reconciliationRunId()),now).allowed())
                throw IntegrationAdminApiException.forbidden(correlation);
            String namespace="api.provisioning-task.recover.v1";
            var registered=idem.register(actor.tenant(),namespace,key,fingerprint,now,null);
            if(registered.kind()==JdbcIdempotencyRepository.RegistrationKind.REPLAY){
                if(!"COMPLETED".equals(registered.operationState())||!"provisioning-recovery-decision".equals(registered.resourceType()))
                    throw IntegrationAdminApiException.conflict(correlation,"idempotency_in_progress","Operation not complete.");
                return recovery.read(actor.tenant(),registered.resourceId());
            }
            var decision=recovery.recover(actor.tenant(),id,revision,body.reconciliationRunId(),body.principalId(),
                    body.executionQuiescedAt(),actor.identityId(),now,correlation);
            audit.append(actor.tenant(),new AuditRecordDraft(ids.nextId(),now,actor.identityId(),"provisioning-task:recover",
                    "provisioning-task",id,AuditOutcome.SUCCESS,correlation,null));
            idem.complete(actor.tenant(),namespace,key,fingerprint,"provisioning-recovery-decision",decision.id(),now);
            return decision;
        });
        return ResponseEntity.ok().header("X-Correlation-Id",correlation.toString()).cacheControl(CacheControl.noStore()).body(result);
    }
}
