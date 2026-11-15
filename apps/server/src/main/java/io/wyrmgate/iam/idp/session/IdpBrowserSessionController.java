package io.wyrmgate.iam.idp.session;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.wyrmgate.iam.idp.protocol.IdpAuthorizationController;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final ConsoleLoginRouteQuery consoleLoginRoute;

    public IdpBrowserSessionController(
            IdpInteractiveLoginService login,
            IdpBrowserSessionService sessions,
            IdpCsrfTokenCodec csrf,
            ConsoleLoginRouteQuery consoleLoginRoute) {
        this.login = login;
        this.sessions = sessions;
        this.csrf = csrf;
        this.consoleLoginRoute = consoleLoginRoute;
    }

    @GetMapping(path = "/api/auth/login-route", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> loginRoute() {
        var route = consoleLoginRoute.resolve();
        if (route.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(Map.of("error", "login_unavailable"));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE, csrfCookie(csrf.issue()).toString())
                .body(Map.of("available", true));
    }

    @PostMapping(
            path = "/api/auth/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> login(@RequestBody LoginRequest request,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader,
            @CookieValue(name = IdpAuthorizationController.BROWSER_SESSION_COOKIE, required = false) String previousSession) {
        char[] password = request.password() == null ? new char[0] : request.password();
        try {
            if (!csrf.matches(csrfCookie, csrfHeader)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                        .body(Map.of("error", "csrf_failed"));
            }
            var route = consoleLoginRoute.resolve();
            if (route.isEmpty()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore()).body(Map.of("error", "login_unavailable"));
            var established = login.authenticate(
                    route.get().clientId(),
                    route.get().applicationTargetId(),
                    request.principalKey(),
                    password,
                    Instant.now());
            if (established.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore())
                        .body(Map.of("error", "invalid_credentials"));
            }

            sessions.logout(previousSession, Instant.now());
            String csrfToken = csrf.issue();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.SET_COOKIE,
                            sessionCookie(established.orElseThrow().token()).toString(),
                            csrfCookie(csrfToken).toString())
                    .body(Map.of("authenticated", true));
        } catch (RuntimeException unavailable) {
            // Do not leak exception details or request inputs from mandatory authentication reads.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "login_unavailable"));
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
        boolean authenticated = authentication instanceof PreAuthenticatedAuthenticationToken
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
    ResponseEntity<?> logout(
            @CookieValue(
                    name = IdpAuthorizationController.BROWSER_SESSION_COOKIE,
                    required = false) String browserSessionToken,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader) {
        if (browserSessionToken != null && !browserSessionToken.isBlank() && !csrf.matches(csrfCookie, csrfHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "csrf_failed"));
        }
        sessions.logout(browserSessionToken, Instant.now());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE,
                        clearSessionCookie().toString(),
                        clearCsrfCookie().toString())
                .build();
    }

    public static ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(IdpAuthorizationController.BROWSER_SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
    }

    public static ResponseCookie csrfCookie(String token) {
        return ResponseCookie.from(CSRF_COOKIE, token)
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .build();
    }

    static ResponseCookie clearSessionCookie() {
        return ResponseCookie.from(IdpAuthorizationController.BROWSER_SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    static ResponseCookie clearCsrfCookie() {
        return ResponseCookie.from(CSRF_COOKIE, "")
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidLoginBody() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("error", "invalid_request"));
    }

    public record LoginRequest(
            String principalKey,
            char[] password) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object ignored) {
            throw new IllegalArgumentException("unexpected login field");
        }
        @Override public String toString() { return "LoginRequest[REDACTED]"; }
    }
}
