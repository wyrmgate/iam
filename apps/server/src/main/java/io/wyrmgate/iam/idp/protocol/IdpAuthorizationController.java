package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.idp.protocol.IdpProtocolService.AuthorizationRequest;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.AuthorizationResult;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.ProtocolException;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.TokenRequest;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.TokenResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/** Public Authorization Code + PKCE adapter. Browser-session establishment is a later slice. */
@RestController
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public class IdpAuthorizationController {

    public static final String BROWSER_SESSION_COOKIE = "__Host-wyrmgate_sso";

    private final IdpProtocolService protocol;

    public IdpAuthorizationController(IdpProtocolService protocol) {
        this.protocol = protocol;
    }

    @GetMapping("/oauth2/authorize")
    ResponseEntity<Void> authorize(
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code_challenge", required = false) String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(name = "nonce", required = false) String nonce,
            @CookieValue(name = BROWSER_SESSION_COOKIE, required = false) String browserSessionToken) {
        AuthorizationResult result = protocol.authorize(
                new AuthorizationRequest(
                        responseType,
                        clientId,
                        redirectUri,
                        scope,
                        state,
                        codeChallenge,
                        codeChallengeMethod,
                        nonce),
                browserSessionToken,
                Instant.now());
        return ResponseEntity.status(302)
                .location(redirect(
                        result.redirectUri(),
                        Map.of("code", result.code(), "state", result.state())))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping(
            path = "/oauth2/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> token(
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "code_verifier", required = false) String codeVerifier) {
        TokenResult result = protocol.redeem(
                new TokenRequest(
                        grantType, code, clientId, redirectUri, codeVerifier),
                Instant.now());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", result.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", result.expiresInSeconds());
        body.put("id_token", result.idToken());
        body.put("scope", result.scope());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(Map.copyOf(body));
    }

    @ExceptionHandler(ProtocolException.class)
    ResponseEntity<?> protocolError(ProtocolException error) {
        if (error.redirectUri().isPresent()) {
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("error", error.error());
            error.state().ifPresent(state -> parameters.put("state", state));
            return ResponseEntity.status(302)
                    .location(redirect(error.redirectUri().orElseThrow(), parameters))
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", error.error()));
    }

    private static URI redirect(String registeredRedirectUri, Map<String, String> parameters) {
        StringBuilder location = new StringBuilder(registeredRedirectUri);
        location.append(registeredRedirectUri.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (!first) location.append('&');
            first = false;
            location.append(UriUtils.encodeQueryParam(
                    parameter.getKey(), StandardCharsets.UTF_8));
            location.append('=');
            location.append(UriUtils.encodeQueryParam(
                    parameter.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(location.toString());
    }
}
