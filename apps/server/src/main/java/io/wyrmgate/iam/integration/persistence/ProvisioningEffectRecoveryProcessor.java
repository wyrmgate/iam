package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.integration.application.GrantProvisioningRepository;
import io.wyrmgate.iam.integration.application.GrantProvisioningRepository.TaskSpec;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProvisioningCandidate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Forward recovery from immutable provider evidence; never reopens an uncertain mutation. */
public final class ProvisioningEffectRecoveryProcessor {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DesiredProvisioningStateQuery desired;
    private final GrantProvisioningRepository grants;
    private final JdbcIntegrationRuntimeRepository fence;
    private final TransactionExecutor transactions;
    public ProvisioningEffectRecoveryProcessor(JdbcTemplate jdbc,ObjectMapper json,DesiredProvisioningStateQuery desired,
            GrantProvisioningRepository grants,JdbcIntegrationRuntimeRepository fence,TransactionExecutor transactions) {
        this.jdbc=jdbc;this.json=json;this.desired=desired;this.grants=grants;this.fence=fence;this.transactions=transactions;
    }
    private record Evidence(UUID tenant,UUID attempt,UUID task,UUID binding,UUID subject,String kind,String operation,
            String contract,int version,String payload,UUID correlation,UUID target,boolean pinned) {}

    @Scheduled(fixedDelayString="${iam.integration.effect-recovery.poll-interval:PT5S}")
    public void process() {
        for(int i=0;i<50;i++) if(!transactions.required(()->advance(Instant.now())))break;
    }

    public boolean advance(Instant now) {
        var rows=jdbc.query("""
                SELECT q.tenant_id,q.attempt_id,t.id,j.connector_binding_id,t.subject_id,t.subject_kind,
                    t.operation_type,t.contract_id,t.contract_version,t.payload::text,t.correlation_id,b.target_id,
                    (b.lifecycle_state='ACTIVE' AND ci.lifecycle_state='ACTIVE'
                    AND b.target_kind='APPLICATION_TARGET' AND b.revision=j.planned_binding_revision
                    AND ci.revision=j.planned_connector_revision AND ci.configuration_version=j.planned_configuration_version
                    AND b.contract_id=t.contract_id AND b.contract_version=t.contract_version)
                FROM integration.provisioning_effect_recovery q
                JOIN integration.provisioning_attempt a ON a.tenant_id=q.tenant_id AND a.id=q.attempt_id
                JOIN integration.provisioning_task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id
                JOIN integration.provisioning_job j ON j.tenant_id=t.tenant_id AND j.id=t.provisioning_job_id
                JOIN integration.connector_binding b ON b.tenant_id=j.tenant_id AND b.id=j.connector_binding_id
                JOIN integration.connector_instance ci ON ci.tenant_id=b.tenant_id AND ci.id=b.connector_instance_id
                WHERE q.state='PENDING' AND q.next_attempt_at<=?
                ORDER BY q.next_attempt_at,q.attempt_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                """,(rs,n)->new Evidence(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7),rs.getString(8),
                rs.getInt(9),rs.getString(10),rs.getObject(11,UUID.class),rs.getObject(12,UUID.class),rs.getBoolean(13)),Timestamp.from(now));
        if(rows.isEmpty())return false;
        Evidence e=rows.getFirst();
        // Recheck and lock exact planned configuration before using historical provider identifiers.
        Boolean pinned=jdbc.queryForObject("""
                SELECT b.lifecycle_state='ACTIVE' AND ci.lifecycle_state='ACTIVE'
                   AND b.target_kind='APPLICATION_TARGET' AND b.revision=j.planned_binding_revision
                   AND ci.revision=j.planned_connector_revision AND ci.configuration_version=j.planned_configuration_version
                   AND b.contract_id=t.contract_id AND b.contract_version=t.contract_version
                FROM integration.provisioning_task t JOIN integration.provisioning_job j ON j.tenant_id=t.tenant_id AND j.id=t.provisioning_job_id
                JOIN integration.connector_binding b ON b.tenant_id=j.tenant_id AND b.id=j.connector_binding_id
                JOIN integration.connector_instance ci ON ci.tenant_id=b.tenant_id AND ci.id=b.connector_instance_id
                WHERE t.tenant_id=? AND t.id=? FOR SHARE OF b,ci
                """,Boolean.class,e.tenant(),e.task());
        if(!e.pinned()||!Boolean.TRUE.equals(pinned)) {finish(e,"MANUAL","historical_route_changed",now);return true;}
        if(!"DESIRED_GRANT".equals(e.kind())||!("ADD_GRANT".equals(e.operation())||"REMOVE_GRANT".equals(e.operation()))) {
            finish(e,"MANUAL","principal_effect_requires_exact_resolution",now);return true;
        }
        Map<String,Object> payload;
        UUID principal,entitlement;
        try {
            payload=json.readValue(e.payload(),new TypeReference<Map<String,Object>>(){});
            principal=UUID.fromString((String)payload.get("principalId"));
            entitlement=UUID.fromString((String)payload.get("entitlementId"));
            if(!(payload.get("providerPrincipalId") instanceof String p)||p.isBlank()
                ||!(payload.get("providerEntitlementId") instanceof String g)||g.isBlank())throw new IllegalArgumentException();
        }catch(Exception malformed){finish(e,"MANUAL","exact_effect_identity_missing",now);return true;}
        TenantContext tenant=new TenantContext(e.tenant());
        var result=desired.desiredGrant(tenant,e.subject());
        if(result.status()==DesiredProvisioningStateQuery.Status.UNAVAILABLE){retry(e,now);return true;}
        if(result.status()!=DesiredProvisioningStateQuery.Status.CURRENT){finish(e,"MANUAL","desired_cause_missing",now);return true;}
        var grant=result.grant();
        if(!e.target().equals(grant.applicationTargetId())||!entitlement.equals(grant.entitlementId())){
            finish(e,"MANUAL","desired_cause_changed_identity",now);return true;
        }
        var demand=desired.technicalGrantDemand(tenant,e.target(),entitlement,principal);
        if(demand==DesiredProvisioningStateQuery.TechnicalGrantDemand.UNAVAILABLE){retry(e,now);return true;}
        boolean required=demand==DesiredProvisioningStateQuery.TechnicalGrantDemand.REQUIRED;
        boolean added="ADD_GRANT".equals(e.operation());
        if(!required&&!added){finish(e,"RESOLVED","effect_matches_current_demand",now);return true;}
        String op;
        UUID binding=e.binding();String contract=e.contract();int version=e.version();
        var corrective=new LinkedHashMap<>(payload);corrective.remove("providerEntitlementVersion");
        if(!required) {
            var original=new ProvisioningCandidate(e.task(),e.attempt(),e.tenant(),binding,e.operation(),e.kind(),
                e.subject(),grant.desiredRevision(),contract,version,"effect",e.correlation(),e.attempt(),payload);
            if(!fence.removalMappingSafe(original,principal,entitlement)){
                finish(e,"MANUAL","historical_mapping_conflict",now);return true;
            }
            op="REMOVE_GRANT";
        } else {
            var routes=grants.addTargets(tenant,e.target(),entitlement,principal);
            if(routes.size()!=1){finish(e,"MANUAL","current_route_unresolved",now);return true;}
            var route=routes.getFirst();
            boolean same=route.connectorBindingId().equals(binding)&&route.providerPrincipalId().equals(payload.get("providerPrincipalId"))
                    &&route.providerEntitlementId().equals(payload.get("providerEntitlementId"));
            if(added&&same){finish(e,"RESOLVED","effect_matches_current_demand",now);return true;}
            if(added){finish(e,"MANUAL","obsolete_route_requires_replacement",now);return true;}
            if(grant.desiredState()!=DesiredPresence.PRESENT||!principal.equals(grant.principalId())){
                finish(e,"MANUAL","other_current_cause_required",now);return true;
            }
            op="ADD_GRANT";binding=route.connectorBindingId();contract=route.contractId();version=route.contractVersion();
            corrective.put("providerPrincipalId",route.providerPrincipalId());corrective.put("providerEntitlementId",route.providerEntitlementId());
            if(route.providerEntitlementVersion()!=null)corrective.put("providerEntitlementVersion",route.providerEntitlementVersion());
        }
        String key="effect:"+e.attempt()+":r"+grant.desiredRevision()+":"+op;
        grants.createPlan(tenant,key,e.subject(),grant.desiredRevision(),
            List.of(new TaskSpec(key,binding,contract,version,op,corrective,null)),e.correlation(),e.attempt(),now);
        finish(e,"PLANNED","corrective_plan_created",now);return true;
    }
    private void retry(Evidence e,Instant now){
        jdbc.update("""
                UPDATE integration.provisioning_effect_recovery SET attempts=attempts+1,
                   state=CASE WHEN attempts>=19 THEN 'MANUAL' ELSE 'PENDING' END,
                   reason='desired_state_unavailable',next_attempt_at=?,updated_at=?
                WHERE tenant_id=? AND attempt_id=?
                """,Timestamp.from(now.plusSeconds(30)),Timestamp.from(now),e.tenant(),e.attempt());
    }
    private void finish(Evidence e,String state,String reason,Instant now){
        jdbc.update("UPDATE integration.provisioning_effect_recovery SET state=?,reason=?,updated_at=? WHERE tenant_id=? AND attempt_id=?",
            state,reason,Timestamp.from(now),e.tenant(),e.attempt());
    }
}
