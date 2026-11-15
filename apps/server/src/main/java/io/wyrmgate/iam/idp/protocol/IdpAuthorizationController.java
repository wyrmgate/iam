package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.idp.protocol.IdpProtocolService.AuthorizationRequest;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.AuthorizationResult;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.ProtocolException;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.TokenRequest;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.TokenResult;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionController;
import io.wyrmgate.iam.idp.session.IdpCsrfTokenCodec;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.http.ResponseCookie;
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
import java.net.URLEncoder;

/** Public Authorization Code + PKCE adapter over current first-party sessions. */
@RestController
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public class IdpAuthorizationController {

    public static final String BROWSER_SESSION_COOKIE = "__Host-wyrmgate_sso";

    private final IdpProtocolService protocol;
    private final IdpLoginInteractionService interactions;
    private final IdpBrowserSessionService sessions;
    private final IdpCsrfTokenCodec csrf;

    public IdpAuthorizationController(IdpProtocolService protocol, IdpLoginInteractionService interactions,
            IdpBrowserSessionService sessions, IdpCsrfTokenCodec csrf) {
        this.protocol = protocol;
        this.interactions = interactions;
        this.sessions = sessions;
        this.csrf = csrf;
    }

    @GetMapping("/oauth2/authorize")
    ResponseEntity<?> authorize(
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code_challenge", required = false) String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(name = "nonce", required = false) String nonce,
            @RequestParam(name = "prompt", required = false) String prompt,
            @RequestParam(name = "max_age", required = false) String maxAge,
            @CookieValue(name = BROWSER_SESSION_COOKIE, required = false) String browserSessionToken) {
        var request = new AuthorizationRequest(responseType, clientId, redirectUri, scope, state,
                codeChallenge, codeChallengeMethod, nonce);
        var validated = protocol.validate(request);
        if (maxAge != null || (prompt != null && !prompt.equals("none") && !prompt.equals("login"))) {
            throw ProtocolException.redirect("invalid_request", request.redirectUri(), request.state());
        }
        if (!"login".equals(prompt)) {
            try {
                return redirectResult(protocol.authorizeValidated(validated, browserSessionToken, Instant.now()));
            } catch (ProtocolException error) {
                if (!"login_required".equals(error.error()) || "none".equals(prompt)) throw error;
            }
        }
        var interaction = interactions.begin(validated, Instant.now());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
                .header(HttpHeaders.SET_COOKIE, interactionCookie(interaction.browserProof(), Duration.ofMinutes(5)).toString())
                .body(loginPage(interaction.formProof()));
    }

    @PostMapping(path = "/oauth2/authorize", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<?> continueAuthorization(
            @RequestParam(name = "interaction", required = false) String formProof,
            @RequestParam(name = "principalKey", required = false) String principalKey,
            @RequestParam(name = "password", required = false) String password,
            @CookieValue(name = IdpLoginInteractionService.COOKIE, required = false) String browserProof,
            @CookieValue(name = BROWSER_SESSION_COOKIE, required = false) String previousSession) {
        char[] secret = password == null ? new char[0] : password.toCharArray();
        try {
            var completed = interactions.complete(formProof, browserProof, principalKey, secret, Instant.now());
            // Do not keep the previous authenticated browser session alive after replacement.
            sessions.logout(previousSession, Instant.now());
            return ResponseEntity.status(303)
                    .location(redirect(completed.result().redirectUri(),
                            Map.of("code", completed.result().code(), "state", completed.result().state())))
                    .cacheControl(CacheControl.noStore())
                    .header("Referrer-Policy", "no-referrer")
                    .header(HttpHeaders.SET_COOKIE, IdpBrowserSessionController.sessionCookie(completed.session().token()).toString(),
                            IdpBrowserSessionController.csrfCookie(csrf.issue()).toString(),
                            interactionCookie("", Duration.ZERO).toString())
                    .build();
        } finally {
            Arrays.fill(secret, (char) 0);
        }
    }

    private static ResponseEntity<Void> redirectResult(AuthorizationResult result) {
        return ResponseEntity.status(302)
                .location(redirect(result.redirectUri(), Map.of("code", result.code(), "state", result.state())))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
    }

    private static ResponseCookie interactionCookie(String value, Duration age) {
        return ResponseCookie.from(IdpLoginInteractionService.COOKIE, value)
                .secure(true).httpOnly(true).sameSite("Strict").path("/").maxAge(age).build();
    }

    private static String loginPage(String formProof) {
        // formProof is generated base64url, never user-controlled HTML.
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Sign in to Wyrmgate</title></head><body><main>
                <h1>Sign in to Wyrmgate</h1><p>Authenticate to continue to your registered application.</p>
                <form method="post" action="/oauth2/authorize">
                <input type="hidden" name="interaction" value="%s">
                <p><label>Username <input name="principalKey" autocomplete="username" maxlength="512" required></label></p>
                <p><label>Password <input type="password" name="password" autocomplete="current-password" maxlength="1024" required></label></p>
                <button type="submit">Sign in</button></form></main></body></html>
                """.formatted(formProof);
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
                    .header("Referrer-Policy", "no-referrer")
                    .build();
        }
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", error.error()));
    }

    private static String encodeQueryParameter(String value, java.nio.charset.Charset charset) {
        return URLEncoder.encode(value, charset).replace("+", "%20");
    }

    private static URI redirect(String registeredRedirectUri, Map<String, String> parameters) {
        StringBuilder location = new StringBuilder(registeredRedirectUri);
        location.append(registeredRedirectUri.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (!first) location.append('&');
            first = false;
            location.append(encodeQueryParameter(
                    parameter.getKey(), StandardCharsets.UTF_8));
            location.append('=');
            location.append(encodeQueryParameter(
                    parameter.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(location.toString());
    }
}
