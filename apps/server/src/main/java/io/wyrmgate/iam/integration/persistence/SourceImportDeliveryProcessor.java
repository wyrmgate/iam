package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.identity.application.SourceCorrelationService;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Deliver only positive source observations via Identity-owned semantic commands. */
public final class SourceImportDeliveryProcessor {
    private final JdbcTemplate jdbc;
    private final SourceCorrelationService identity;
    private final TransactionExecutor tx;
    public SourceImportDeliveryProcessor(JdbcTemplate jdbc,SourceCorrelationService identity,TransactionExecutor tx){
        this.jdbc=jdbc;this.identity=identity;this.tx=tx;
    }
    private record Delivery(UUID tenant,UUID run,UUID source,UUID binding,UUID importRun,String cursor,
            UUID correlation,long generation,long bindingRevision,long connectorRevision,long config,String coverage,String checkpoint){}
    private record Observation(String key,String attributes,Instant observedAt){}
    @Scheduled(fixedDelayString="${iam.integration.source-import.poll-interval:PT5S}")
    public void process(){for(int i=0;i<20;i++)if(!tx.required(()->advance(Instant.now())))break;}
    public boolean advance(Instant now){
        var rows=jdbc.query("""
                SELECT d.tenant_id,d.reconciliation_run_id,d.source_system_id,r.connector_binding_id,d.identity_import_run_id,
                    d.cursor_provider_id,r.correlation_id,r.scan_generation,r.binding_revision,r.connector_revision,
                    r.configuration_version,r.effective_completeness,r.checkpoint_end
                FROM integration.source_import_delivery d JOIN integration.reconciliation_run r
                    ON r.tenant_id=d.tenant_id AND r.id=d.reconciliation_run_id
                WHERE d.state='PENDING' ORDER BY d.updated_at,d.reconciliation_run_id LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """,(rs,n)->new Delivery(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getString(6),rs.getObject(7,UUID.class),
                    rs.getLong(8),rs.getLong(9),rs.getLong(10),rs.getLong(11),rs.getString(12),rs.getString(13)));
        if(rows.isEmpty())return false;
        var d=rows.getFirst();var tenant=new TenantContext(d.tenant());
        // Serialize source delivery across binding replacements; never access Identity-owned tables.
        long key=d.tenant().getMostSignificantBits()^d.source().getLeastSignificantBits()^0x494e54534f555243L;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",(rs,n)->1,key);
        Boolean compatible=jdbc.queryForObject("""
                SELECT b.lifecycle_state='ACTIVE' AND ci.lifecycle_state='ACTIVE'
                    AND b.target_kind='SOURCE_SYSTEM' AND b.target_id=? AND b.revision=? AND ci.revision=?
                    AND ci.configuration_version=? AND NOT EXISTS(
                        SELECT 1 FROM integration.connector_binding other
                        WHERE other.tenant_id=b.tenant_id AND other.target_kind='SOURCE_SYSTEM' AND other.target_id=b.target_id
                            AND other.lifecycle_state='ACTIVE' AND other.id<>b.id)
                FROM integration.connector_binding b JOIN integration.connector_instance ci
                    ON ci.tenant_id=b.tenant_id AND ci.id=b.connector_instance_id
                WHERE b.tenant_id=? AND b.id=? FOR SHARE OF b,ci
                """,Boolean.class,d.source(),d.bindingRevision(),d.connectorRevision(),d.config(),d.tenant(),d.binding());
        boolean newer=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM integration.reconciliation_run r JOIN integration.connector_binding b
                    ON b.tenant_id=r.tenant_id AND b.id=r.connector_binding_id
                    WHERE r.tenant_id=? AND b.target_kind='SOURCE_SYSTEM' AND b.target_id=?
                    AND r.scope_object_class='PRINCIPAL' AND r.scan_generation>? AND r.completion_lease_epoch IS NOT NULL)
                """,Boolean.class,d.tenant(),d.source(),d.generation()));
        if(!Boolean.TRUE.equals(compatible)||newer||!identity.sourceSystemExists(tenant,d.source())){
            if(d.importRun()!=null)identity.completeImport(tenant,d.importRun(),SourceImportCompleteness.PARTIAL,null,
                "source_delivery_superseded",now,d.correlation(),d.run());
            finish(d,"SUPERSEDED",d.importRun(),d.cursor(),now);return true;
        }
        UUID importRun=d.importRun();
        if(importRun==null)importRun=identity.startImport(tenant,d.source(),now).id();
        var observations=jdbc.query("""
                SELECT provider_stable_id,observed_state::text,observed_at FROM integration.reconciliation_principal_staging
                WHERE tenant_id=? AND reconciliation_run_id=? AND (?::varchar IS NULL OR provider_stable_id>?)
                ORDER BY provider_stable_id LIMIT 101
                """,(rs,n)->new Observation(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant()),
                d.tenant(),d.run(),d.cursor(),d.cursor());
        var page=observations.subList(0,Math.min(100,observations.size()));
        for(var o:page)identity.observe(tenant,importRun,o.key(),o.attributes(),null,o.observedAt(),d.correlation(),d.run());
        String cursor=page.isEmpty()?d.cursor():page.getLast().key();
        if(observations.size()>100){finish(d,"PENDING",importRun,cursor,now);return true;}
        boolean complete="COMPLETE".equals(d.coverage());
        identity.completeImport(tenant,importRun,complete?SourceImportCompleteness.COMPLETE:SourceImportCompleteness.PARTIAL,
            d.checkpoint(),complete?null:"provider_coverage_not_complete",now,d.correlation(),d.run());
        finish(d,"COMPLETED",importRun,cursor,now);return true;
    }
    private void finish(Delivery d,String state,UUID importRun,String cursor,Instant now){
        jdbc.update("UPDATE integration.source_import_delivery SET state=?,identity_import_run_id=?,cursor_provider_id=?,updated_at=? WHERE tenant_id=? AND reconciliation_run_id=?",
            state,importRun,cursor,Timestamp.from(now),d.tenant(),d.run());
    }
}
