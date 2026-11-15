package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.FindingContext;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcGovernanceFindingRepository implements GovernanceFindingRepository {
    private static final String SELECT="""
            SELECT id,finding_key,finding_type,subject_kind,connector_binding_id,provider_stable_id,related_provider_stable_id,
                   lifecycle_state,first_observed_at,last_observed_at,resolved_at,revision,created_at,updated_at,
                   identity_id,principal_id,entitlement_id,application_target_id,sod_rule_id,related_binding_id,related_principal_id,related_entitlement_id,exception_id
            FROM governance.finding WHERE tenant_id=?
            """;
    private final JdbcTemplate jdbc;
    public JdbcGovernanceFindingRepository(JdbcTemplate jdbc){this.jdbc=java.util.Objects.requireNonNull(jdbc);}
    @Override
    public Optional<GovernanceFinding> findByKey(TenantContext tenant,String key){
        return jdbc.query(SELECT+" AND finding_key=?",(rs,n)->row(rs),tenant.tenantId(),key).stream().findFirst();
    }
    @Override
    public Optional<GovernanceFinding> findById(TenantContext tenant,UUID id){
        return jdbc.query(SELECT+" AND id=?",(rs,n)->row(rs),tenant.tenantId(),id).stream().findFirst();
    }
    @Override
    public List<GovernanceFinding> findOpenPage(TenantContext tenant,UUID binding,String after,int limit){
        if(limit<1 || limit>101) throw new IllegalArgumentException("finding page limit");
        return jdbc.query(SELECT+" AND connector_binding_id=? AND lifecycle_state='OPEN' AND (?::text IS NULL OR finding_key>?) ORDER BY finding_key LIMIT ?",
                (rs,n)->row(rs),tenant.tenantId(),binding,after,after,limit);
    }
    @Override
    public List<GovernanceFinding> findPage(TenantContext tenant,FindingContext.State state,Instant afterCreatedAt,UUID afterId,int limit){
        if(limit<1 || limit>101 || (afterCreatedAt==null)!=(afterId==null)) throw new IllegalArgumentException("invalid finding page");
        return jdbc.query(SELECT+" AND (?::text IS NULL OR lifecycle_state=?) AND (?::timestamptz IS NULL OR (created_at,id)>(?,?)) ORDER BY created_at,id LIMIT ?",
                (rs,n)->row(rs),tenant.tenantId(),state==null?null:state.name(),state==null?null:state.name(),
                ts(afterCreatedAt),ts(afterCreatedAt),afterId,limit);
    }
    @Override
    public GovernanceFinding insert(TenantContext tenant,UUID id,String key,String type,String subject,UUID binding,
            String provider,String related,Instant at,FindingContext c){
        jdbc.update("""
                INSERT INTO governance.finding (id,tenant_id,finding_key,finding_type,subject_kind,connector_binding_id,provider_stable_id,
                   related_provider_stable_id,lifecycle_state,first_observed_at,last_observed_at,resolved_at,revision,created_at,updated_at,
                   identity_id,principal_id,entitlement_id,application_target_id,sod_rule_id,related_binding_id,related_principal_id,related_entitlement_id,exception_id)
                VALUES (?,?,?,?,?,?,?,?,'OPEN',?,?,NULL,1,?,?,?,?,?,?,?,?,?,?,?)
                """,id,tenant.tenantId(),key,type,subject,binding,provider,related,ts(at),ts(at),ts(at),ts(at),
                c.identityId(),c.principalId(),c.entitlementId(),c.applicationTargetId(),c.sodRuleId(),c.relatedBindingId(),
                c.relatedPrincipalId(),c.relatedEntitlementId(),c.exceptionId());
        return findByKey(tenant,key).orElseThrow();
    }
    @Override
    public GovernanceFinding touchOpen(TenantContext tenant,UUID id,long expectedRevision,Instant at){
        int changed=jdbc.update("""
                UPDATE governance.finding SET last_observed_at=GREATEST(last_observed_at,?),revision=revision+1,updated_at=GREATEST(updated_at,?)
                WHERE tenant_id=? AND id=? AND revision=? AND lifecycle_state='OPEN'
                """,ts(at),ts(at),tenant.tenantId(),id,expectedRevision);
        return requireUpdated(tenant,id,expectedRevision,changed);
    }
    @Override
    public GovernanceFinding reopen(TenantContext tenant,UUID id,long expectedRevision,Instant at){
        int changed=jdbc.update("""
                UPDATE governance.finding SET lifecycle_state='OPEN',last_observed_at=GREATEST(last_observed_at,?),resolved_at=NULL,
                    revision=revision+1,updated_at=GREATEST(updated_at,?)
                WHERE tenant_id=? AND id=? AND revision=? AND lifecycle_state='RESOLVED'
                """,ts(at),ts(at),tenant.tenantId(),id,expectedRevision);
        return requireUpdated(tenant,id,expectedRevision,changed);
    }
    @Override
    public GovernanceFinding resolve(TenantContext tenant,UUID id,long expectedRevision,Instant at){
        int changed=jdbc.update("""
                UPDATE governance.finding SET lifecycle_state='RESOLVED',resolved_at=GREATEST(last_observed_at,?),revision=revision+1,updated_at=GREATEST(updated_at,?)
                WHERE tenant_id=? AND id=? AND revision=? AND lifecycle_state='OPEN'
                """,ts(at),ts(at),tenant.tenantId(),id,expectedRevision);
        return requireUpdated(tenant,id,expectedRevision,changed);
    }
    private GovernanceFinding requireUpdated(TenantContext tenant,UUID id,long revision,int changed){
        var current=findById(tenant,id).orElseThrow(()->new IllegalArgumentException("governance finding does not exist"));
        if(changed!=1) throw new StaleWriteException("governance-finding",id,revision);
        return current;
    }
    private static Timestamp ts(Instant at){return at==null?null:Timestamp.from(at);}
    private static GovernanceFinding row(java.sql.ResultSet rs) throws java.sql.SQLException{
        var resolved=rs.getTimestamp("resolved_at");
        return new GovernanceFinding(rs.getObject("id",UUID.class),rs.getString("finding_key"),rs.getString("finding_type"),rs.getString("subject_kind"),
                rs.getObject("connector_binding_id",UUID.class),rs.getString("provider_stable_id"),rs.getString("related_provider_stable_id"),rs.getString("lifecycle_state"),
                rs.getTimestamp("first_observed_at").toInstant(),rs.getTimestamp("last_observed_at").toInstant(),resolved==null?null:resolved.toInstant(),
                rs.getLong("revision"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
                new FindingContext(rs.getObject("identity_id",UUID.class),rs.getObject("principal_id",UUID.class),rs.getObject("entitlement_id",UUID.class),
                        rs.getObject("application_target_id",UUID.class),rs.getObject("sod_rule_id",UUID.class),rs.getObject("related_binding_id",UUID.class),
                        rs.getObject("related_principal_id",UUID.class),rs.getObject("related_entitlement_id",UUID.class),rs.getObject("exception_id",UUID.class)));
    }
}
