package io.wyrmgate.iam.integration.application.federation;

import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/** Initial explicit upstream OIDC public-client profile. Public verification material only. */
public record OidcTrustSettings(String authorizationEndpoint,String tokenEndpoint,String keyId,String publicKeySpki) {
    public static final String PROVIDER_KEY="oidc-code-pkce-rs256";
    public OidcTrustSettings {
        https(authorizationEndpoint);https(tokenEndpoint);
        if(keyId==null||keyId.isBlank()||keyId.length()>128||!keyId.equals(keyId.strip()))throw new IllegalArgumentException("invalid key ID");
        key(publicKeySpki);
    }
    public void validateIssuer(String issuer) {
        URI origin=https(issuer);
        for(String endpoint:new String[]{authorizationEndpoint,tokenEndpoint}) {
            URI uri=https(endpoint);
            if(!origin.getHost().equalsIgnoreCase(uri.getHost())||port(origin)!=port(uri))
                throw new IllegalArgumentException("initial OIDC endpoints must share issuer origin");
        }
    }
    public RSAPublicKey verificationKey(){return key(publicKeySpki);}
    private static int port(URI u){return u.getPort()==-1?443:u.getPort();}
    private static URI https(String value) {
        if(value==null||value.length()>2048||!value.equals(value.strip()))throw new IllegalArgumentException("invalid OIDC URI");
        URI u=URI.create(value);
        if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null)
            throw new IllegalArgumentException("OIDC URI must be HTTPS without userinfo, query or fragment");
        return u;
    }
    private static RSAPublicKey key(String encoded) {
        if(encoded==null||encoded.length()>4096)throw new IllegalArgumentException("invalid public key");
        try {
            var result=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if(result.getModulus().bitLength()<2048||result.getModulus().bitLength()>4096||result.getPublicExponent().compareTo(java.math.BigInteger.valueOf(65537))<0)
                throw new IllegalArgumentException("unsupported RSA verification key");
            return result;
        }catch(java.security.GeneralSecurityException|ClassCastException invalid){throw new IllegalArgumentException("invalid RSA public key");}
    }
}
