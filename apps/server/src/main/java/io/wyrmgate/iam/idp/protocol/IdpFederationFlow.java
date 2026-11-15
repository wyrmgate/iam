package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.idp.federation.FederatedSubjectResolver;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpSessionTokenCodec;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.integration.application.federation.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;

final class IdpFederationFlow {
    static final String COOKIE="__Host-wyrmgate_federation";
    static final String START="/oauth2/federation/start", CALLBACK="/oauth2/federation/callback";
    private final boolean enabled;private final Set<String> origins;
    private final IdpLoginInteractionService local;private final JdbcIdpFederationInteraction pending;
    private final FederationTrustRepository trusts;private final OidcUpstreamAdapter adapter;
    private final FederatedSubjectResolver resolver;private final PrincipalTechnicalReferenceQuery principals;
    private final IdpBrowserSessionService sessions;private final IdpProtocolService protocol;private final IdpSessionTokenCodec tokens;
    IdpFederationFlow(boolean enabled,Set<String> origins,IdpLoginInteractionService local,JdbcIdpFederationInteraction pending,
        FederationTrustRepository trusts,OidcUpstreamAdapter adapter,FederatedSubjectResolver resolver,PrincipalTechnicalReferenceQuery principals,
        IdpBrowserSessionService sessions,IdpProtocolService protocol,IdpSessionTokenCodec tokens){
        this.enabled=enabled;this.origins=Set.copyOf(origins);this.local=local;this.pending=pending;this.trusts=trusts;this.adapter=adapter;
        this.resolver=resolver;this.principals=principals;this.sessions=sessions;this.protocol=protocol;this.tokens=tokens;
    }
    private boolean allowed(FederationTrustRepository.TrustRecord t){return origins.contains("https://"+URI.create(t.settings().tokenEndpoint()).getRawAuthority());}
    boolean available(IdpProtocolService.ValidatedAuthorization validated){
        if(!enabled)return false;
        try{return trusts.activeForClient(validated.resolved().tenant(),validated.resolved().registration().id()).filter(this::allowed).isPresent();}
        catch(RuntimeException failure){return false;}
    }
    record Started(URI location,String browser){@Override public String toString(){return "Started[REDACTED]";}}
    Started start(String form,String browser,String previousSession,Instant now){
        if(!enabled)throw denied();
        var i=local.consume(form,browser,now);local.current(i);
        var t=trusts.activeForClient(i.tenant(),i.registrationId()).filter(this::allowed).orElseThrow(IdpFederationFlow::denied);
        var state=tokens.issue();var proof=tokens.issue();
        var downstream=new IdpLoginInteractionRepository.Interaction(state.hash(),proof.hash(),i.tenant(),i.registrationId(),i.clientRevision(),i.targetId(),i.request(),now.plusSeconds(300));
        pending.insert(new JdbcIdpFederationInteraction.Pending(UUID.randomUUID(),t.trust().id(),t.trust().revision(),downstream),state.hash(),proof.hash());
        // The Strict session cookie may not return on a cross-site callback. Revoke replacement at initiation.
        sessions.logout(previousSession,now);
        return new Started(adapter.authorizationUri(t,state.value(),proof.value()),proof.value());
    }
    IdpLoginInteractionService.Completed complete(String code,String state,String browser,String issuer,Instant now){
        if(!enabled)throw denied();
        try {
            var p=pending.find(tokens.hash(state),tokens.hash(browser),now).orElseThrow(IdpFederationFlow::denied);var i=p.downstream();
            local.current(i);
            var t=trusts.activeForClient(i.tenant(),i.registrationId()).filter(v->v.trust().id().equals(p.trustId())&&v.trust().revision()==p.trustRevision()).filter(this::allowed).orElseThrow(IdpFederationFlow::denied);
            var context=new FederationTrustQuery.VerificationContext(t.trust(),p.id(),i.expiresAt());
            var proof=adapter.authenticate(context,new OidcUpstreamAdapter.Callback(code,state,browser,issuer),now);
            Instant after=Instant.now();
            var anchor=resolver.resolve(context,proof,after).orElseThrow(IdpFederationFlow::denied);
            var principal=principals.resolve(i.tenant(),anchor.principalId());
            if(principal.status()!=PrincipalTechnicalReferenceQuery.Status.ACTIVE||!i.targetId().equals(principal.applicationTargetId())||!anchor.identityId().equals(principal.identityId()))throw denied();
            var validated=local.current(i);
            var session=sessions.establishFederated(i.tenant(),anchor,after);
            try{return new IdpLoginInteractionService.Completed(protocol.authorizeValidated(validated,session.token(),after),session);}
            catch(RuntimeException failure){sessions.logout(session.token(),after);throw failure;}
        }catch(RuntimeException failure){throw denied();}
    }
    private static IdpProtocolService.ProtocolException denied(){return IdpProtocolService.ProtocolException.direct("access_denied");}
}
