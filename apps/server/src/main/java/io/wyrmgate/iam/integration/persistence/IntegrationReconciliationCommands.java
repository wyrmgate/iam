package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.catalog.application.CatalogTargetReferenceQuery;
import io.wyrmgate.iam.identity.application.SourceCorrelationService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Integration-owned durable reconciliation scheduling. No provider call in the command. */
public final class IntegrationReconciliationCommands {
    private final JdbcTemplate jdbc;
    private final IdGenerator ids;
    private final SourceCorrelationService sources;
    private final CatalogTargetReferenceQuery targets;
    public IntegrationReconciliationCommands(JdbcTemplate jdbc,IdGenerator ids,SourceCorrelationService sources,CatalogTargetReferenceQuery targets){
        this.jdbc=jdbc;this.ids=ids;this.sources=sources;this.targets=targets;
    }
    public record ScheduledRun(UUID id,UUID connectorBindingId,String scopeObjectClass,String scopeMode,long revision,String state){}
    private record Binding(UUID target,String kind,long revision,long connectorRevision,long config,String runtime,String runtimeVersion,String contract,int contractVersion){}
    public ScheduledRun schedule(TenantContext tenant,UUID binding,long expectedRevision,String objectClass,String scopeMode,
            String checkpoint,Instant now,UUID correlation){
        if(objectClass==null||scopeMode==null||!java.util.Set.of("PRINCIPAL","ENTITLEMENT","GRANT").contains(objectClass)||!java.util.Set.of("FULL","INCREMENTAL").contains(scopeMode)
            ||("FULL".equals(scopeMode)&&checkpoint!=null)
            ||("INCREMENTAL".equals(scopeMode)&&(checkpoint==null||checkpoint.isBlank()||checkpoint.length()>2048)))
            throw new IllegalArgumentException("invalid reconciliation scope");
        var rows=jdbc.query("""
                SELECT b.target_id,b.target_kind,b.revision,ci.revision,ci.configuration_version,ci.runtime_id,ci.runtime_version,
                    b.contract_id,b.contract_version
                FROM integration.connector_binding b JOIN integration.connector_instance ci
                    ON ci.tenant_id=b.tenant_id AND ci.id=b.connector_instance_id
                WHERE b.tenant_id=? AND b.id=? AND b.lifecycle_state='ACTIVE' AND ci.lifecycle_state='ACTIVE'
                FOR UPDATE OF b,ci
                """,(rs,n)->new Binding(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),
                    rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9)),tenant.tenantId(),binding);
        if(rows.isEmpty())throw new IntegrationAdministrationException("binding_not_found","active binding not found");
        var b=rows.getFirst();
        if(b.revision()!=expectedRevision)throw new IntegrationAdministrationException("stale_revision","binding revision changed");
        if("SOURCE_SYSTEM".equals(b.kind())){
            if(!"PRINCIPAL".equals(objectClass)||!sources.sourceSystemExists(tenant,b.target()))
                throw new IllegalArgumentException("source discovery requires an existing same-tenant source and PRINCIPAL scope");
            var count=jdbc.queryForObject("SELECT count(*) FROM integration.connector_binding WHERE tenant_id=? AND target_kind='SOURCE_SYSTEM' AND target_id=? AND lifecycle_state='ACTIVE'",
                Integer.class,tenant.tenantId(),b.target());
            if(count==null||count!=1)throw new IllegalArgumentException("source discovery requires a unique active binding");
        }else if(!"APPLICATION_TARGET".equals(b.kind())||targets.validateActiveTarget(tenant,b.target())!=CatalogTargetReferenceQuery.Validation.VALID)
            throw new IllegalArgumentException("reconciliation target unavailable");
        if("scim-2.principal".equals(b.contract())&&!"PRINCIPAL".equals(objectClass))throw new IllegalArgumentException("principal contract requires PRINCIPAL scope");
        if("scim-2".equals(b.runtime())&&checkpoint!=null){
            try{if(Integer.parseInt(checkpoint)<1)throw new NumberFormatException();}catch(NumberFormatException invalid){throw new IllegalArgumentException("invalid SCIM checkpoint");}
        }
        UUID id=ids.nextId();
        jdbc.update("""
                INSERT INTO integration.reconciliation_run(id,tenant_id,connector_binding_id,operation_id,scope_object_class,
                    scope_mode,state,configuration_version,runtime_id,runtime_version,contract_id,contract_version,checkpoint_start,
                    correlation_id,revision,started_at,created_at,updated_at,scan_generation,binding_revision,connector_revision)
                VALUES (?,?,?,?,?,?,'RUNNING',?,?,?,?,?,?,1,?,?,?,nextval('integration.reconciliation_scan_generation_seq'),?,?)
                """,id,tenant.tenantId(),binding,ids.nextId(),objectClass,scopeMode,b.config(),b.runtime(),b.runtimeVersion(),
                b.contract(),b.contractVersion(),checkpoint,correlation,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),b.revision(),b.connectorRevision());
        return new ScheduledRun(id,binding,objectClass,scopeMode,1,"RUNNING");
    }
    public ScheduledRun read(TenantContext tenant,UUID id){
        return jdbc.query("""
                SELECT id,connector_binding_id,scope_object_class,scope_mode,revision,state
                FROM integration.reconciliation_run WHERE tenant_id=? AND id=?
                """,(rs,n)->new ScheduledRun(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getString(4),rs.getLong(5),rs.getString(6)),tenant.tenantId(),id).stream().findFirst()
                .orElseThrow(()->new IntegrationAdministrationException("run_not_found","run not found"));
    }
}
