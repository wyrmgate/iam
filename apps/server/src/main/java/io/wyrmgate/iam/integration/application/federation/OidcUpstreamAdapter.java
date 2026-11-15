package io.wyrmgate.iam.integration.application.federation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

/** Initial explicit public-client OIDC profile. No discovery, redirect following, or claim adoption. */
public final class OidcUpstreamAdapter implements FederatedAuthenticationAdapter<OidcUpstreamAdapter.Callback> {
    public record Callback(String code,String state,String browser,String issuer) {
        @Override public String toString(){return "Callback[REDACTED]";}
    }
    @FunctionalInterface public interface TokenExchange {
        String exchange(String endpoint,String audience,String redirect,String code,String verifier);
    }
    private final FederationTrustRepository trusts;
    private final OidcCallbackFence fence;
    private final TokenExchange exchange;
    private final String redirect;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public OidcUpstreamAdapter(FederationTrustRepository trusts,OidcCallbackFence fence,TokenExchange exchange,String redirect){
        this.trusts=trusts;this.fence=fence;this.exchange=exchange;this.redirect=redirect;
    }
    public String providerKey(){return OidcTrustSettings.PROVIDER_KEY;}
    public FederationTrustQuery.Protocol protocol(){return FederationTrustQuery.Protocol.OIDC;}
    public Class<Callback> requestType(){return Callback.class;}
    public URI authorizationUri(FederationTrustRepository.TrustRecord record,String state,String browser){
        return URI.create(record.settings().authorizationEndpoint()+"?"+form(Map.of("response_type","code","client_id",record.trust().audience(),
            "redirect_uri",redirect,"scope","openid","state",state,"nonce",derive("nonce",state,browser),
            "code_challenge",base64(digest(derive("pkce",state,browser))),"code_challenge_method","S256","prompt","login","max_age","0")));
    }
    @Override public VerifiedFederatedSubject authenticate(FederationTrustQuery.VerificationContext context,Callback callback,Instant now){
        try {
            var trust=context.trust();
            if(!now.isBefore(context.expiresAt())||!providerKey().equals(trust.providerKey())||trust.protocol()!=protocol()
                ||!opaque(callback.state())||!opaque(callback.browser())||callback.code()==null||callback.code().isBlank()||callback.code().length()>2048
                ||callback.code().codePoints().anyMatch(Character::isISOControl)
                ||(callback.issuer()!=null&&!trust.issuer().equals(callback.issuer())))throw denied();
            var record=trusts.findTrust(new TenantContext(trust.tenantId()),trust.id())
                .filter(v->"ACTIVE".equals(v.lifecycleState())&&v.trust().equals(trust)&&v.clientRegistrationId()!=null).orElseThrow(OidcUpstreamAdapter::denied);
            if(!fence.consume(context,hash(callback.state()),hash(callback.browser()),now))throw denied();
            String token=exchange.exchange(record.settings().tokenEndpoint(),trust.audience(),redirect,callback.code(),derive("pkce",callback.state(),callback.browser()));
            return verify(context,record.settings(),token,derive("nonce",callback.state(),callback.browser()),now);
        }catch(RuntimeException failure){throw denied();}
    }
    VerifiedFederatedSubject verify(FederationTrustQuery.VerificationContext context,OidcTrustSettings settings,String token,String nonce,Instant now){
        try {
            if(token==null||token.length()>16384)throw denied();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=3||java.util.Arrays.stream(parts).anyMatch(v->!v.matches("[A-Za-z0-9_-]+")))throw denied();
            var header=json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(!header.isObject()||!"RS256".equals(text(header,"alg"))||!settings.keyId().equals(text(header,"kid")))throw denied();
            for(String field:java.util.List.of("crit","jku","jwk","x5u","x5c","b64","zip"))if(header.has(field))throw denied();
            var sig=Signature.getInstance("SHA256withRSA");sig.initVerify(settings.verificationKey());
            sig.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(!sig.verify(Base64.getUrlDecoder().decode(parts[2])))throw denied();
            var claims=json.readTree(Base64.getUrlDecoder().decode(parts[1]));var trust=context.trust();
            if(!claims.isObject()||!trust.issuer().equals(text(claims,"iss"))||!nonce.equals(text(claims,"nonce")))throw denied();
            JsonNode aud=claims.get("aud");if(aud==null||!(aud.isTextual()&&trust.audience().equals(aud.textValue())
                ||aud.isArray()&&aud.size()==1&&aud.get(0).isTextual()&&trust.audience().equals(aud.get(0).textValue())))throw denied();
            if(claims.has("azp")&&!trust.audience().equals(text(claims,"azp")))throw denied();
            Instant exp=time(claims,"exp"),iat=time(claims,"iat"),auth=time(claims,"auth_time");
            if(!exp.isAfter(now)||iat.isAfter(now.plusSeconds(30))||iat.isBefore(context.expiresAt().minusSeconds(360))
                ||auth.isAfter(now)||auth.isBefore(context.expiresAt().minusSeconds(360))||auth.isAfter(iat.plusSeconds(30))
                ||!exp.isAfter(iat)||claims.has("nbf")&&time(claims,"nbf").isAfter(now))throw denied();
            return new VerifiedFederatedSubject(context,text(claims,"sub"),auth,exp);
        }catch(Exception failure){throw denied();}
    }
    private static String text(JsonNode n,String key){var v=n.get(key);if(v==null||!v.isTextual())throw denied();return v.textValue();}
    private static Instant time(JsonNode n,String key){var v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong())throw denied();return Instant.ofEpochSecond(v.longValue());}
    private static boolean opaque(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{43}");}
    public static String hash(String s){return HexFormat.of().formatHex(digest(s));}
    private static String derive(String domain,String state,String browser){if(!opaque(state)||!opaque(browser))throw denied();return base64(digest(domain+":"+state+":"+browser));}
    private static byte[] digest(String s){try{return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String base64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String form(Map<String,String> values){return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(Collectors.joining("&"));}
    private static IllegalArgumentException denied(){return new IllegalArgumentException("upstream authentication rejected");}
}
