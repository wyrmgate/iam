package io.wyrmgate.iam.idp.protocol;
import io.wyrmgate.iam.integration.application.federation.OidcCallbackFence;
import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery.VerificationContext;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcIdpFederationInteraction implements OidcCallbackFence {
    record Pending(UUID id,UUID trustId,long trustRevision,IdpLoginInteractionRepository.Interaction downstream) {
        @Override public String toString(){return "Pending[REDACTED]";}
    }
    private final JdbcTemplate jdbc;
    JdbcIdpFederationInteraction(JdbcTemplate jdbc){this.jdbc=jdbc;}
    void insert(Pending p,String stateHash,String browserHash){
        var i=p.downstream();var q=i.request();
        jdbc.update("""
            INSERT INTO platform.idp_federation_interaction(id,state_hash,browser_hash,tenant_id,trust_id,trust_revision,registration_id,
            registration_revision,target_id,client_id,redirect_uri,scope_value,downstream_state,code_challenge,nonce_value,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,p.id(),stateHash,browserHash,i.tenant().tenantId(),p.trustId(),p.trustRevision(),i.registrationId(),
            i.clientRevision(),i.targetId(),q.clientId(),q.redirectUri(),q.scope(),q.state(),q.codeChallenge(),q.nonce(),Timestamp.from(i.expiresAt()));
    }
    Optional<Pending> find(String stateHash,String browserHash,Instant now){
        return jdbc.query("SELECT * FROM platform.idp_federation_interaction WHERE state_hash=? AND browser_hash=? AND expires_at>?",(rs,n)->{
            var i=new IdpLoginInteractionRepository.Interaction(rs.getString("state_hash"),rs.getString("browser_hash"),
                new TenantContext(rs.getObject("tenant_id",UUID.class)),rs.getObject("registration_id",UUID.class),rs.getLong("registration_revision"),
                rs.getObject("target_id",UUID.class),new IdpProtocolService.AuthorizationRequest("code",rs.getString("client_id"),rs.getString("redirect_uri"),
                rs.getString("scope_value"),rs.getString("downstream_state"),rs.getString("code_challenge"),"S256",rs.getString("nonce_value")),
                rs.getTimestamp("expires_at").toInstant());
            return new Pending(rs.getObject("id",UUID.class),rs.getObject("trust_id",UUID.class),rs.getLong("trust_revision"),i);
        },stateHash,browserHash,Timestamp.from(now)).stream().findFirst();
    }
    @Override public boolean consume(VerificationContext c,String stateHash,String browserHash,Instant now){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("upstream exchange requires committed callback consumption");
        return jdbc.update("DELETE FROM platform.idp_federation_interaction WHERE id=? AND tenant_id=? AND trust_id=? AND trust_revision=? AND state_hash=? AND browser_hash=? AND expires_at>?",
            c.interactionId(),c.trust().tenantId(),c.trust().id(),c.trust().revision(),stateHash,browserHash,Timestamp.from(now))==1;
    }
}
