package io.wyrmgate.iam.integration.application.federation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OidcUpstreamAdapterTest {
    @Test void verifiesExactProofAndConsumesCallbackBeforeExchange() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();
        var tenant=new TenantContext(UUID.randomUUID());
        var trust=new FederationTrustQuery.Trust(tenant.tenantId(),UUID.randomUUID(),2,FederationTrustQuery.Protocol.OIDC,OidcTrustSettings.PROVIDER_KEY,"https://issuer.example","upstream-client");
        var settings=new OidcTrustSettings("https://issuer.example/authorize","https://issuer.example/token","key",Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
        var now=Instant.parse("2026-11-15T08:00:00Z");var context=new FederationTrustQuery.VerificationContext(trust,UUID.randomUUID(),now.plusSeconds(300));
        var repo=mock(FederationTrustRepository.class);
        var record=new FederationTrustRepository.TrustRecord(trust,settings,"ACTIVE",now,now,null,UUID.randomUUID());
        when(repo.findTrust(tenant,trust.id())).thenReturn(Optional.of(record));
        var consumed=new AtomicBoolean();var calls=new AtomicInteger();String state="s".repeat(43),browser="b".repeat(43);
        var nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(("nonce:"+state+":"+browser).getBytes(StandardCharsets.US_ASCII)));
        var claims=new HashMap<String,Object>(Map.of("iss",trust.issuer(),"aud",trust.audience(),"sub","exact-subject","nonce",nonce,"iat",now.getEpochSecond(),"auth_time",now.getEpochSecond(),"exp",now.plusSeconds(600).getEpochSecond()));
        var adapter=new OidcUpstreamAdapter(repo,(c,s,b,n)->s.equals(OidcUpstreamAdapter.hash(state))&&b.equals(OidcUpstreamAdapter.hash(browser))&&consumed.compareAndSet(false,true),
            (endpoint,aud,redirect,code,verifier)->{assertThat(consumed).isTrue();calls.incrementAndGet();try{return signed(key,claims);}catch(Exception e){throw new IllegalStateException(e);}},"https://local.example/oauth2/federation/callback");
        assertThat(adapter.authorizationUri(record,state,browser).toString()).contains("code_challenge_method=S256").doesNotContain(browser);
        var callback=new OidcUpstreamAdapter.Callback("code",state,browser,trust.issuer());
        assertThat(adapter.authenticate(context,callback,now).exactSubject()).isEqualTo("exact-subject");
        assertThatThrownBy(()->adapter.authenticate(context,callback,now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(()->adapter.verify(context,settings,signed(key,claims),"wrong-nonce",now)).isInstanceOf(IllegalArgumentException.class);
        claims.put("aud","other");assertThatThrownBy(()->adapter.verify(context,settings,signed(key,claims),nonce,now)).isInstanceOf(IllegalArgumentException.class);
        claims.put("aud",trust.audience());claims.put("exp",now.getEpochSecond());assertThatThrownBy(()->adapter.verify(context,settings,signed(key,claims),nonce,now)).isInstanceOf(IllegalArgumentException.class);
    }
    private static String signed(KeyPair key,Map<String,Object> claims)throws Exception{
        var json=new ObjectMapper();var b64=Base64.getUrlEncoder().withoutPadding();
        String body=b64.encodeToString(json.writeValueAsBytes(Map.of("alg","RS256","kid","key")))+"."+b64.encodeToString(json.writeValueAsBytes(claims));
        var sign=Signature.getInstance("SHA256withRSA");sign.initSign(key.getPrivate());sign.update(body.getBytes(StandardCharsets.US_ASCII));
        return body+"."+b64.encodeToString(sign.sign());
    }
}
