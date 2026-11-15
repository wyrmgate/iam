package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.integration.application.federation.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcFederationTrustRepository implements FederationTrustRepository {
    private final JdbcTemplate jdbc;
    public JdbcFederationTrustRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String TRUST="SELECT tenant_id,id,revision,protocol,provider_key,issuer,audience,authorization_endpoint,token_endpoint,key_id,public_key_spki,lifecycle_state,created_at,updated_at,retired_at,client_registration_id FROM integration.federation_trust";
    private static final String LINK="SELECT l.tenant_id,l.id,l.revision,l.trust_id,l.exact_subject,l.principal_id,l.lifecycle_state,l.created_at,l.updated_at,l.retired_at FROM integration.federated_subject_link l";
    @Override public Optional<TrustRecord> activeForClient(TenantContext t,UUID client){return jdbc.query(TRUST+" WHERE tenant_id=? AND client_registration_id=? AND lifecycle_state='ACTIVE'",JdbcFederationTrustRepository::trust,t.tenantId(),client).stream().findFirst();}
    @Override public TrustRecord bindClient(TenantContext t,UUID id,long revision,UUID client,Instant now){
        try {if(jdbc.update("UPDATE integration.federation_trust SET client_registration_id=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND id=? AND revision=? AND lifecycle_state='ACTIVE' AND client_registration_id IS NULL",client,Timestamp.from(now),t.tenantId(),id,revision)!=1)throw conflict(findTrust(t,id).isPresent());}
        catch(DuplicateKeyException e){throw new IntegrationAdministrationException("federation_conflict","Client already has an active trust.");}
        return findTrust(t,id).orElseThrow();
    }
    @Override public Optional<TrustRecord> findTrust(TenantContext t,UUID id){return jdbc.query(TRUST+" WHERE tenant_id=? AND id=?",JdbcFederationTrustRepository::trust,t.tenantId(),id).stream().findFirst();}
    @Override public Optional<LinkRecord> findLink(TenantContext t,UUID id){return jdbc.query(LINK+" WHERE l.tenant_id=? AND l.id=?",JdbcFederationTrustRepository::link,t.tenantId(),id).stream().findFirst();}
    @Override public Optional<TrustRecord> lockActiveTrust(TenantContext t,UUID id){return jdbc.query(TRUST+" WHERE tenant_id=? AND id=? AND lifecycle_state='ACTIVE' FOR UPDATE",JdbcFederationTrustRepository::trust,t.tenantId(),id).stream().findFirst();}
    @Override public Optional<Trust> activeTrust(TenantContext t,UUID id){return findTrust(t,id).filter(v->"ACTIVE".equals(v.lifecycleState())).map(TrustRecord::trust);}
    @Override public Optional<SubjectLink> activeLink(TenantContext t,UUID id,String subject) {
        return jdbc.query(LINK+" JOIN integration.federation_trust f ON f.tenant_id=l.tenant_id AND f.id=l.trust_id AND f.lifecycle_state='ACTIVE' WHERE l.tenant_id=? AND l.trust_id=? AND l.exact_subject=? AND l.lifecycle_state='ACTIVE'",
            JdbcFederationTrustRepository::link,t.tenantId(),id,subject).stream().findFirst().map(LinkRecord::link);
    }
    @Override public Optional<SubjectLink> activeLinkById(TenantContext t,UUID id) {
        return jdbc.query(LINK+" JOIN integration.federation_trust f ON f.tenant_id=l.tenant_id AND f.id=l.trust_id AND f.lifecycle_state='ACTIVE' WHERE l.tenant_id=? AND l.id=? AND l.lifecycle_state='ACTIVE'",
            JdbcFederationTrustRepository::link,t.tenantId(),id).stream().findFirst().map(LinkRecord::link);
    }
    @Override public TrustRecord insertTrust(TenantContext t,Trust v,OidcTrustSettings s,Instant now) {
        if(!t.tenantId().equals(v.tenantId())||v.revision()!=1||v.protocol()!=Protocol.OIDC||!OidcTrustSettings.PROVIDER_KEY.equals(v.providerKey()))throw new IllegalArgumentException("invalid trust creation");
        try {jdbc.update("""
                INSERT INTO integration.federation_trust(tenant_id,id,revision,protocol,provider_key,issuer,audience,
                  authorization_endpoint,token_endpoint,key_id,public_key_spki,lifecycle_state,created_at,updated_at)
                VALUES (?,?,1,'OIDC',?,?,?,?,?,?,?,'ACTIVE',?,?)
                """,t.tenantId(),v.id(),v.providerKey(),v.issuer(),v.audience(),s.authorizationEndpoint(),s.tokenEndpoint(),s.keyId(),s.publicKeySpki(),Timestamp.from(now),Timestamp.from(now));}
        catch(DuplicateKeyException e){throw new IntegrationAdministrationException("federation_conflict","Active federation trust already exists.");}
        return findTrust(t,v.id()).orElseThrow();
    }
    @Override public LinkRecord insertLink(TenantContext t,SubjectLink l,Instant now) {
        if(!t.tenantId().equals(l.tenantId())||l.revision()!=1)throw new IllegalArgumentException("invalid link creation");
        try {jdbc.update("""
                INSERT INTO integration.federated_subject_link(tenant_id,id,revision,trust_id,exact_subject,principal_id,lifecycle_state,created_at,updated_at)
                VALUES (?,?,1,?,?,?,'ACTIVE',?,?)
                """,t.tenantId(),l.id(),l.trustId(),l.exactSubject(),l.principalId(),Timestamp.from(now),Timestamp.from(now));}
        catch(DuplicateKeyException e){throw new IntegrationAdministrationException("federation_conflict","Active federation subject link already exists.");}
        return findLink(t,l.id()).orElseThrow();
    }
    @Override public TrustRecord retireTrust(TenantContext t,UUID id,long revision,Instant now) {
        if(jdbc.update("UPDATE integration.federation_trust SET lifecycle_state='RETIRED',revision=revision+1,updated_at=?,retired_at=? WHERE tenant_id=? AND id=? AND revision=? AND lifecycle_state='ACTIVE'",
            Timestamp.from(now),Timestamp.from(now),t.tenantId(),id,revision)!=1)throw conflict(findTrust(t,id).isPresent());
        return findTrust(t,id).orElseThrow();
    }
    @Override public LinkRecord retireLink(TenantContext t,UUID id,long revision,Instant now) {
        if(jdbc.update("UPDATE integration.federated_subject_link SET lifecycle_state='RETIRED',revision=revision+1,updated_at=?,retired_at=? WHERE tenant_id=? AND id=? AND revision=? AND lifecycle_state='ACTIVE'",
            Timestamp.from(now),Timestamp.from(now),t.tenantId(),id,revision)!=1)throw conflict(findLink(t,id).isPresent());
        return findLink(t,id).orElseThrow();
    }
    private static RuntimeException conflict(boolean exists){return new IntegrationAdministrationException(exists?"federation_stale":"not_found",exists?"Federation resource revision or lifecycle changed.":"Federation resource not found.");}
    private static TrustRecord trust(ResultSet rs,int row)throws SQLException {
        var v=new Trust(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),Protocol.valueOf(rs.getString(4)),rs.getString(5),rs.getString(6),rs.getString(7));
        return new TrustRecord(v,new OidcTrustSettings(rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11)),rs.getString(12),instant(rs,13),instant(rs,14),instant(rs,15),rs.getObject(16,UUID.class));
    }
    private static LinkRecord link(ResultSet rs,int row)throws SQLException {
        return new LinkRecord(new SubjectLink(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getObject(6,UUID.class)),rs.getString(7),instant(rs,8),instant(rs,9),instant(rs,10));
    }
    private static Instant instant(ResultSet rs,int i)throws SQLException {var value=rs.getTimestamp(i);return value==null?null:value.toInstant();}
}
