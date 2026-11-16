package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.integration.application.GrantProvisioningRepository;
import io.wyrmgate.iam.integration.application.PrincipalProvisioningRepository;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProvisioningCandidate;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** A transaction-bound Integration command. Never retries or changes the original task/attempt. */
public final class VerifiedProvisioningRecovery {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final DesiredProvisioningStateQuery desired;
    private final GrantProvisioningRepository grants;
    private final PrincipalProvisioningRepository principals;
    private final PrincipalTechnicalReferenceQuery identities;
    private final JdbcIntegrationRuntimeRepository fence;

    public VerifiedProvisioningRecovery(JdbcTemplate jdbc, ObjectMapper json, IdGenerator ids,
            DesiredProvisioningStateQuery desired, GrantProvisioningRepository grants,
            PrincipalProvisioningRepository principals, PrincipalTechnicalReferenceQuery identities,
            JdbcIntegrationRuntimeRepository fence) {
        this.jdbc=jdbc; this.json=json; this.ids=ids; this.desired=desired; this.grants=grants;
        this.principals=principals; this.identities=identities; this.fence=fence;
    }

    public record Decision(UUID id, UUID taskId, UUID reconciliationRunId, long taskRevision,
            long desiredRevision, String observation, String disposition, UUID correctiveJobId, Instant recordedAt) {}
    private record Task(UUID id, UUID binding, UUID subject, String kind, String operation,
            String contract, int version, String payload, long revision, Instant updatedAt,
            String state, String failure, Long bindingRevision, Long connectorRevision, Long configurationVersion) {}
    private record Run(UUID binding, String scope, Instant startedAt, long generation,
            long bindingRevision, long connectorRevision, long configurationVersion,
            String runtime, String runtimeVersion, String contract, int version) {}
    private record Result(long desiredRevision, String observation, String operation, Map<String,Object> payload) {}

    public Decision recover(TenantContext tenant, UUID taskId, long revision, UUID runId,
            UUID principalId, Instant quiescedAt, UUID operatorId, Instant now, UUID correlation) {
        if (runId==null || quiescedAt==null || operatorId==null || quiescedAt.isAfter(now))
            throw new IllegalArgumentException("recovery requires a completed verification run and execution quiescence");
        UUID tid=tenant.tenantId();
        Task t=jdbc.query("""
                SELECT t.id,j.connector_binding_id,t.subject_id,t.subject_kind,t.operation_type,
                    t.contract_id,t.contract_version,t.payload::text,t.revision,t.updated_at,t.state,t.failure_code,
                    j.planned_binding_revision,j.planned_connector_revision,j.planned_configuration_version
                FROM integration.provisioning_task t JOIN integration.provisioning_job j
                  ON j.tenant_id=t.tenant_id AND j.id=t.provisioning_job_id
                WHERE t.tenant_id=? AND t.id=? FOR UPDATE OF t
                """,(rs,n)->new Task(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8),rs.getLong(9),
                    rs.getTimestamp(10).toInstant(),rs.getString(11),rs.getString(12),rs.getObject(13,Long.class),
                    rs.getObject(14,Long.class),rs.getObject(15,Long.class)),tid,taskId).stream().findFirst()
                .orElseThrow(()->new IntegrationAdministrationException("not_found","provisioning task not found"));
        if(t.revision()!=revision)throw new IntegrationAdministrationException("stale_revision","task revision changed");
        if(!"BLOCKED".equals(t.state())||t.failure()==null||!java.util.Set.of("provider_effect_unknown_after_lease_expiry",
                "late_provider_success_requires_recovery","retry_budget_exhausted").contains(t.failure()))
            throw conflict("task is not eligible for verified recovery");
        if(quiescedAt.isBefore(t.updatedAt()))throw conflict("execution quiescence must follow the blocked task revision");
        if(exists("SELECT EXISTS(SELECT 1 FROM integration.provisioning_recovery_decision WHERE tenant_id=? AND task_id=?)",tid,taskId))
            throw conflict("task already has a recovery decision");
        if(exists("""
                SELECT EXISTS(SELECT 1 FROM platform.connector_work_lease
                  WHERE tenant_id=? AND work_id=? AND work_kind='PROVISION' AND lease_expires_at>?)
                """,tid,taskId,Timestamp.from(now)))throw conflict("task still has an active lease");
        // Serialize with the automatic confirmed-effect recovery queue before taking route locks.
        var effects=jdbc.query("""
                SELECT q.state FROM integration.provisioning_effect_recovery q
                JOIN integration.provisioning_attempt a ON a.tenant_id=q.tenant_id AND a.id=q.attempt_id
                WHERE a.tenant_id=? AND a.task_id=? FOR UPDATE OF q
                """,(rs,n)->rs.getString(1),tid,taskId);
        if(effects.stream().anyMatch(s->!"MANUAL".equals(s)))throw conflict("automatic effect recovery is active or already decided");
        String scope=switch(t.kind()){case "DESIRED_GRANT"->"GRANT";case "DESIRED_PRINCIPAL"->"PRINCIPAL";default->throw conflict("unsupported recovery subject");};
        Run run=jdbc.query("""
                SELECT connector_binding_id,scope_object_class,started_at,scan_generation,binding_revision,
                    connector_revision,configuration_version,runtime_id,runtime_version,contract_id,contract_version
                FROM integration.reconciliation_run
                WHERE tenant_id=? AND id=? AND state='SUCCEEDED' AND scope_mode='FULL'
                  AND effective_completeness='COMPLETE' AND scan_generation IS NOT NULL
                  AND completion_lease_epoch=1 AND checkpoint_start IS NULL AND checkpoint_end IS NULL
                FOR UPDATE
                """,(rs,n)->new Run(rs.getObject(1,UUID.class),rs.getString(2),rs.getTimestamp(3).toInstant(),
                    rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getString(8),rs.getString(9),
                    rs.getString(10),rs.getInt(11)),tid,runId).stream().findFirst()
                .orElseThrow(()->conflict("verification requires a finalized complete full scan"));
        if(!t.binding().equals(run.binding())||!scope.equals(run.scope())||!run.startedAt().isAfter(quiescedAt)
            ||!java.util.Objects.equals(t.bindingRevision(),run.bindingRevision())
            ||!java.util.Objects.equals(t.connectorRevision(),run.connectorRevision())
            ||!java.util.Objects.equals(t.configurationVersion(),run.configurationVersion())
            ||!t.contract().equals(run.contract())||t.version()!=run.version())
            throw conflict("verification run does not match the exact quiesced route");
        // Same run -> binding lock order as reconciliation materialization.
        UUID target=jdbc.query("""
                SELECT b.target_id FROM integration.connector_binding b JOIN integration.connector_instance ci
                  ON ci.tenant_id=b.tenant_id AND ci.id=b.connector_instance_id
                WHERE b.tenant_id=? AND b.id=? AND b.target_kind='APPLICATION_TARGET'
                  AND b.lifecycle_state='ACTIVE' AND ci.lifecycle_state='ACTIVE'
                  AND b.revision=? AND ci.revision=? AND ci.configuration_version=?
                  AND ci.runtime_id=? AND ci.runtime_version=? AND b.contract_id=? AND b.contract_version=?
                FOR UPDATE OF b,ci
                """,(rs,n)->rs.getObject(1,UUID.class),tid,t.binding(),run.bindingRevision(),run.connectorRevision(),
                    run.configurationVersion(),run.runtime(),run.runtimeVersion(),run.contract(),run.version())
                .stream().findFirst().orElseThrow(()->conflict("verification route is stale"));
        if(exists("""
                SELECT EXISTS(SELECT 1 FROM integration.reconciliation_run
                  WHERE tenant_id=? AND connector_binding_id=? AND scope_object_class=? AND scan_generation>?
                    AND (completed_at IS NOT NULL OR completion_lease_epoch IS NOT NULL))
                """,tid,t.binding(),scope,run.generation()))throw conflict("verification run has been superseded");

        // No competing current plan for this desired cause. Other causes remain protected by the shared admission checks.
        if(exists("""
                SELECT EXISTS(SELECT 1 FROM integration.provisioning_task
                  WHERE tenant_id=? AND subject_kind=? AND subject_id=? AND id<>?
                    AND state IN ('READY','RUNNING','FAILED_RETRYABLE'))
                """,tid,t.kind(),t.subject(),taskId))throw conflict("another current plan already exists");
        Map<String,Object> payload;
        try{payload=json.readValue(t.payload(),new TypeReference<Map<String,Object>>(){});}
        catch(Exception e){throw conflict("historical task route cannot be interpreted");}
        Result result="GRANT".equals(scope)?grant(tenant,t,target,runId,payload,principalId)
                :principal(tenant,t,target,runId,payload,principalId);
        UUID id=ids.nextId();
        UUID correctiveJob=null;
        if(result.operation()!=null){
            String key="verified-recovery:"+id;
            boolean created="GRANT".equals(scope)
                ?grants.createPlan(tenant,key,t.subject(),result.desiredRevision(),
                    List.of(new GrantProvisioningRepository.TaskSpec(key,t.binding(),t.contract(),t.version(),
                        result.operation(),result.payload(),null)),correlation,id,now)
                :principals.createPlan(tenant,key,t.subject(),result.desiredRevision(),
                    List.of(new PrincipalProvisioningRepository.TaskSpec(key,t.binding(),t.contract(),t.version(),
                        result.operation(),result.payload())),correlation,id,now);
            if(!created)throw conflict("corrective plan could not be created");
            correctiveJob=jdbc.queryForObject("SELECT id FROM integration.provisioning_job WHERE tenant_id=? AND plan_key=?",
                    UUID.class,tid,key+":"+t.binding());
        }
        String disposition=correctiveJob==null?"MATCHES_DESIRED":"CORRECTIVE_PLAN_CREATED";
        jdbc.update("""
                INSERT INTO integration.provisioning_recovery_decision
                    (id,tenant_id,task_id,reconciliation_run_id,task_revision,desired_revision,observation,
                     disposition,corrective_job_id,operator_id,execution_quiesced_at,recorded_at,correlation_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,tid,taskId,runId,revision,result.desiredRevision(),result.observation(),disposition,
                correctiveJob,operatorId,Timestamp.from(quiescedAt),Timestamp.from(now),correlation);
        return new Decision(id,taskId,runId,revision,result.desiredRevision(),result.observation(),disposition,correctiveJob,now);
    }

    private Result grant(TenantContext tenant,Task t,UUID target,UUID runId,Map<String,Object> p,UUID requestedPrincipal) {
        if(!"ADD_GRANT".equals(t.operation())&&!"REMOVE_GRANT".equals(t.operation()))throw conflict("unsupported grant operation");
        UUID principal=uuid(p,"principalId"), entitlement=uuid(p,"entitlementId");
        if(requestedPrincipal!=null&&!requestedPrincipal.equals(principal))throw conflict("principal differs from original route");
        String account=required(p,"providerPrincipalId"), group=required(p,"providerEntitlementId");
        var snapshot=desired.desiredGrant(tenant,t.subject());
        if(snapshot.status()!=DesiredProvisioningStateQuery.Status.CURRENT)throw conflict("current desired grant is unavailable");
        var g=snapshot.grant();
        if(!target.equals(g.applicationTargetId())||!entitlement.equals(g.entitlementId()))throw conflict("desired grant identity changed");
        var c=new ProvisioningCandidate(t.id(),t.id(),tenant.tenantId(),t.binding(),t.operation(),t.kind(),
                t.subject(),g.desiredRevision(),t.contract(),t.version(),"verified-recovery",runId,runId,p);
        if(!fence.removalMappingSafe(c,principal,entitlement))throw conflict("historical provider mapping conflicts");
        var demand=desired.technicalGrantDemand(tenant,target,entitlement,principal);
        if(demand==DesiredProvisioningStateQuery.TechnicalGrantDemand.UNAVAILABLE)throw conflict("current technical demand is unavailable");
        boolean required=demand==DesiredProvisioningStateQuery.TechnicalGrantDemand.REQUIRED;
        // Read only the exact pair from current materialized evidence; no provider-string fallback.
        var observed=jdbc.query("""
                SELECT present,last_observed_run_id FROM integration.observed_grant
                WHERE tenant_id=? AND connector_binding_id=? AND principal_provider_id=? AND entitlement_provider_id=?
                """,(rs,n)->Map.entry(rs.getBoolean(1),rs.getObject(2,UUID.class)),tenant.tenantId(),t.binding(),account,group);
        boolean present=!observed.isEmpty()&&observed.getFirst().getKey();
        if(present&&!runId.equals(observed.getFirst().getValue()))throw conflict("grant observation is not from the verification run");
        String observation=present?"PRESENT":"ABSENT";
        Map<String,Object> correction=new LinkedHashMap<>(p);
        correction.remove("providerEntitlementVersion");
        if(required){
            var routes=grants.addTargets(tenant,target,entitlement,principal);
            if(routes.size()!=1||!t.binding().equals(routes.getFirst().connectorBindingId())
                ||!account.equals(routes.getFirst().providerPrincipalId())||!group.equals(routes.getFirst().providerEntitlementId())
                ||!t.contract().equals(routes.getFirst().contractId())||t.version()!=routes.getFirst().contractVersion())
                throw conflict("current required grant route is not exact");
            if(present)return new Result(g.desiredRevision(),observation,null,Map.of());
            if(g.desiredState()!=DesiredPresence.PRESENT||!principal.equals(g.principalId()))
                throw conflict("another current desired cause must authorize the grant");
            if(routes.getFirst().providerEntitlementVersion()!=null)
                correction.put("providerEntitlementVersion",routes.getFirst().providerEntitlementVersion());
        }else if(!present)return new Result(g.desiredRevision(),observation,null,Map.of());
        return new Result(g.desiredRevision(),observation,required?"ADD_GRANT":"REMOVE_GRANT",correction);
    }

    private Result principal(TenantContext tenant,Task t,UUID target,UUID runId,Map<String,Object> p,UUID requestedPrincipal) {
        if(!java.util.Set.of("UPSERT_PRINCIPAL","DISABLE_PRINCIPAL","DEACTIVATE_PRINCIPAL").contains(t.operation()))
            throw conflict("unsupported principal operation");
        var snapshot=desired.desiredPrincipal(tenant,t.subject());
        if(snapshot.status()!=DesiredProvisioningStateQuery.Status.CURRENT)throw conflict("current desired principal is unavailable");
        var d=snapshot.principal();
        if(!target.equals(d.applicationTargetId())||!uuid(p,"identityId").equals(d.identityId())
                ||!uuid(p,"applicationTargetId").equals(target))throw conflict("desired principal identity changed");
        String historical=text(p,"providerStableId");
        UUID principal=text(p,"principalId")==null?requestedPrincipal:uuid(p,"principalId");
        if(principal==null||requestedPrincipal!=null&&!requestedPrincipal.equals(principal))
            throw conflict("uncertain account requires an explicitly mapped governed Principal");
        var subject=identities.resolve(tenant,principal);
        if(subject.status()==PrincipalTechnicalReferenceQuery.Status.UNAVAILABLE||!target.equals(subject.applicationTargetId())
                ||!d.identityId().equals(subject.identityId()))throw conflict("governed principal is unavailable or incompatible");
        var mapped=jdbc.query("""
                SELECT provider_stable_id FROM integration.principal_observation_mapping
                WHERE tenant_id=? AND connector_binding_id=? AND principal_id=? AND lifecycle_state='ACTIVE'
                ORDER BY provider_stable_id LIMIT 2 FOR SHARE
                """,(rs,n)->rs.getString(1),tenant.tenantId(),t.binding(),principal);
        if(mapped.size()!=1||historical!=null&&!historical.equals(mapped.getFirst()))
            throw conflict("exact active principal mapping required");
        String account=mapped.getFirst();
        // If a late successful create returned a stable ID, require agreement; no matching by username or externalId.
        if(exists("""
                SELECT EXISTS(SELECT 1 FROM integration.provisioning_attempt
                  WHERE tenant_id=? AND task_id=? AND outcome='SUCCEEDED'
                    AND provider_object_id IS NOT NULL AND provider_object_id<>?)
                """,tenant.tenantId(),t.id(),account))throw conflict("mapping conflicts with immutable provider success evidence");
        record Observed(boolean present,UUID run,String active) {}
        var observed=jdbc.query("""
                SELECT present,last_observed_run_id,
                    CASE WHEN jsonb_typeof(observed_state->'active')='boolean' THEN observed_state->>'active' END
                FROM integration.observed_principal WHERE tenant_id=? AND connector_binding_id=? AND provider_stable_id=?
                """,(rs,n)->new Observed(rs.getBoolean(1),rs.getObject(2,UUID.class),rs.getString(3)),
                    tenant.tenantId(),t.binding(),account);
        boolean present=!observed.isEmpty()&&observed.getFirst().present();
        boolean required=d.desiredState()==DesiredPresence.PRESENT;
        if(!present){
            if(historical==null||required)throw conflict("no exact positive account evidence; never replay an uncertain create");
            return new Result(d.desiredRevision(),"ABSENT",null,Map.of());
        }
        var o=observed.getFirst();
        if(!runId.equals(o.run())||o.active()==null)throw conflict("current explicit account activation observation required");
        boolean active=Boolean.parseBoolean(o.active());
        String observation=active?"ACTIVE":"INACTIVE";
        if(required==active)return new Result(d.desiredRevision(),observation,null,Map.of());
        var routes=principals.existingTargets(tenant,target,d.id(),principal,subject.nativePrincipalKey());
        var exact=routes.stream().filter(x->t.binding().equals(x.connectorBindingId())&&account.equals(x.providerPrincipalId())
                &&t.contract().equals(x.contractId())&&t.version()==x.contractVersion()).toList();
        if(exact.size()!=1||required&&routes.size()!=1)throw conflict("current account route is not exact");
        Map<String,Object> correction=new LinkedHashMap<>();
        correction.put("identityId",d.identityId().toString());correction.put("applicationTargetId",target.toString());
        correction.put("principalId",principal.toString());correction.put("providerStableId",account);
        if(required)correction.put("active",true);
        return new Result(d.desiredRevision(),observation,required?"UPSERT_PRINCIPAL":"DISABLE_PRINCIPAL",correction);
    }

    public Decision read(TenantContext tenant,UUID id) {
        return jdbc.query("""
                SELECT id,task_id,reconciliation_run_id,task_revision,desired_revision,observation,disposition,corrective_job_id,recorded_at
                FROM integration.provisioning_recovery_decision WHERE tenant_id=? AND id=?
                """,(rs,n)->new Decision(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getObject(8,UUID.class),rs.getTimestamp(9).toInstant()),
                tenant.tenantId(),id).stream().findFirst().orElseThrow(()->new IntegrationAdministrationException("not_found","recovery decision not found"));
    }
    private boolean exists(String sql,Object...args){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
    private static String text(Map<String,Object> p,String k){return p.get(k) instanceof String s&&!s.isBlank()?s:null;}
    private static String required(Map<String,Object> p,String k){String s=text(p,k);if(s==null)throw conflict("exact historical route required");return s;}
    private static UUID uuid(Map<String,Object> p,String k){try{return UUID.fromString(required(p,k));}catch(IllegalArgumentException e){throw conflict("invalid historical governed identity");}}
    private static IntegrationAdministrationException conflict(String message){return new IntegrationAdministrationException("recovery_conflict",message);}
}
