package io.wyrmgate.iam.integration.application.federation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.io.ByteArrayOutputStream;

/** Exact deployment-approved HTTPS token endpoint origin; bounded response and no redirects. */
public final class HttpOidcTokenExchange implements OidcUpstreamAdapter.TokenExchange {
    private final Set<String> origins;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public HttpOidcTokenExchange(Set<String> origins){this.origins=Set.copyOf(origins);}
    @Override public String exchange(String endpoint,String audience,String redirect,String code,String verifier){
        try {
            URI uri=URI.create(endpoint);
            String origin="https://"+uri.getRawAuthority();
            if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||!origins.contains(origin))throw new IllegalArgumentException();
            var req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type","application/x-www-form-urlencoded")
                .header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofString(OidcUpstreamAdapter.form(Map.of("grant_type","authorization_code","client_id",audience,"redirect_uri",redirect,"code",code,"code_verifier",verifier)))).build();
            var resp=client.sendAsync(req,info->new LimitedBody()).orTimeout(12,java.util.concurrent.TimeUnit.SECONDS).join();
            if(resp.statusCode()!=200)throw new IllegalArgumentException();
            var node=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(resp.body());
            if(node==null||!node.isObject()||!node.path("id_token").isTextual())throw new IllegalArgumentException();
            return node.path("id_token").textValue();
        }catch(Exception failure){throw new IllegalArgumentException("upstream exchange rejected");}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> done=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return done;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> chunks){for(var chunk:chunks){if(chunk.remaining()>65536-bytes.size()){subscription.cancel();done.completeExceptionally(new IllegalArgumentException("response too large"));return;}byte[] b=new byte[chunk.remaining()];chunk.get(b);bytes.writeBytes(b);}subscription.request(1);}
        public void onError(Throwable failure){done.completeExceptionally(failure);}
        public void onComplete(){done.complete(bytes.toByteArray());}
    }
}
