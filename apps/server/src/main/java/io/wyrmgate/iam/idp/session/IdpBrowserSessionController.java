package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.idp.protocol.IdpAuthorizationController;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin browser interaction for first-party sign-in/session/logout. */
@RestController
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public class IdpBrowserSessionController {

    public static final String CSRF_COOKIE = "__Host-wyrmgate_csrf";
    public static final String CSRF_HEADER = "X-Wyrmgate-CSRF";

    private final IdpInteractiveLoginService login;
    private final IdpBrowserSessionService sessions;
    private final IdpCsrfTokenCodec csrf;

    public IdpBrowserSessionController(
            IdpInteractiveLoginService login,
            IdpBrowserSessionService sessions,
            IdpCsrfTokenCodec csrf) {
        this.login = login;
        this.sessions = sessions;
        this.csrf = csrf;
    }

    @PostMapping(
            path = "/api/auth/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> login(@RequestBody LoginRequest request) {
        char[] password = request.password() == null ? new char[0] : request.password();
        try {
            var established = login.authenticate(
                    request.clientId(),
                    request.applicationTargetId(),
                    request.principalKey(),
                    password,
                    Instant.now());
            if (established.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore())
                        .body(Map.of("error", "invalid_credentials"));
            }

            String csrfToken = csrf.issue();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.SET_COOKIE,
                            sessionCookie(established.orElseThrow().token()).toString(),
                            csrfCookie(csrfToken).toString())
                    .body(Map.of("authenticated", true));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @GetMapping(path = "/api/auth/session", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> session(
            Authentication authentication,
            @CookieValue(
                    name = IdpAuthorizationController.BROWSER_SESSION_COOKIE,
                    required = false) String browserSessionToken) {
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && browserSessionToken != null
                && !browserSessionToken.isBlank();
        if (!authenticated) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("authenticated", false));
        }

        String csrfToken = csrf.issue();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, csrfCookie(csrfToken).toString())
                .body(Map.of("authenticated", true));
    }

    @PostMapping(path = "/api/auth/logout")
    ResponseEntity<Void> logout(
            @CookieValue(
                    name = IdpAuthorizationController.BROWSER_SESSION_COOKIE,
                    required = false) String browserSessionToken) {
        sessions.logout(browserSessionToken, Instant.now());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE,
                        clearSessionCookie().toString(),
                        clearCsrfCookie().toString())
                .build();
    }

    private static ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(IdpAuthorizationController.BROWSER_SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
    }

    private static ResponseCookie csrfCookie(String token) {
        return ResponseCookie.from(CSRF_COOKIE, token)
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .build();
    }

    private static ResponseCookie clearSessionCookie() {
        return ResponseCookie.from(IdpAuthorizationController.BROWSER_SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private static ResponseCookie clearCsrfCookie() {
        return ResponseCookie.from(CSRF_COOKIE, "")
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    public record LoginRequest(
            String clientId,
            String applicationTargetId,
            String principalKey,
            char[] password) {
    }
}
