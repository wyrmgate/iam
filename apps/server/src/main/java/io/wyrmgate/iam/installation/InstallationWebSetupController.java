package io.wyrmgate.iam.installation;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Same-origin first-run browser adapter for the deployment-scoped installation bootstrap.
 * It projects the existing durable state machine and never owns an independent setup workflow.
 */
@RestController
@ConditionalOnProperty(prefix = "iam.bootstrap.web", name = "enabled", havingValue = "true")
public final class InstallationWebSetupController {

    static final String SESSION_COOKIE = "__Host-wyrmgate_setup";
    static final String CSRF_COOKIE = "__Host-wyrmgate_setup_csrf";
    static final String CSRF_HEADER = "X-Wyrmgate-Setup-CSRF";

    private final InstallationBootstrapRepository repository;
    private final InstallationBootstrapService bootstrap;
    private final InstallationBootstrapProperties properties;
    private final InstallationSetupSessionCodec sessions;

    public InstallationWebSetupController(
            InstallationBootstrapRepository repository,
            InstallationBootstrapService bootstrap,
            InstallationBootstrapProperties properties,
            InstallationSetupSessionCodec sessions) {
        this.repository = repository;
        this.bootstrap = bootstrap;
        this.properties = properties;
        this.sessions = sessions;
    }

    @GetMapping(path = "/api/setup/status", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> status(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
        Instant now = Instant.now();
        return noStore(HttpStatus.OK, statusBody(sessionToken, now));
    }

    @PostMapping(
            path = "/api/setup/session",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> establishSession(@RequestBody SetupProofRequest request) {
        if (bootstrap.isSealed()) {
            return error(HttpStatus.CONFLICT, "INSTALLATION_SEALED", "Installation setup is permanently sealed.");
        }
        char[] proof = request.setupSecret() == null ? new char[0] : request.setupSecret();
        try {
            if (!sessions.authenticate(proof)) {
                return error(HttpStatus.UNAUTHORIZED, "INVALID_SETUP_PROOF", "Setup access was not accepted.");
            }
            Instant now = Instant.now();
            String token = sessions.issue(now);
            String csrf = sessions.issueCsrf();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.SET_COOKIE,
                            sessionCookie(token).toString(),
                            csrfCookie(csrf).toString())
                    .body(statusBody(token, now));
        } finally {
            Arrays.fill(proof, '\0');
        }
    }

    @PostMapping(
            path = "/api/setup/initialize",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> initialize(
            @RequestBody InitializeRequest request,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader) {
        Instant now = Instant.now();
        if (bootstrap.isSealed()) {
            return sealedResponse();
        }
        if (!sessions.verify(sessionToken, now)) {
            return error(HttpStatus.UNAUTHORIZED, "SETUP_SESSION_REQUIRED", "A valid setup session is required.");
        }
        if (!sessions.verifyCsrf(csrfCookie, csrfHeader)) {
            return error(HttpStatus.FORBIDDEN, "SETUP_CSRF_REJECTED", "Setup request verification failed.");
        }

        char[] password = request.password() == null ? new char[0] : request.password();
        try {
            InstallationBootstrapService.Input input = new InstallationBootstrapService.Input(
                    request.tenantDisplayName(),
                    request.identityDisplayName(),
                    request.username(),
                    trustedConsoleRedirectUri());
            bootstrap.run(input, password, now);
            if (bootstrap.isSealed()) {
                return sealedResponse();
            }
            return noStore(HttpStatus.OK, statusBody(sessionToken, Instant.now()));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_SETUP_INPUT", "Required setup fields are invalid.");
        } catch (IllegalStateException exception) {
            return error(
                    HttpStatus.CONFLICT,
                    "INSTALLATION_RESUME_FAILED",
                    "Installation could not resume with the supplied setup values. Refresh status and retry with the original values and password.");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private ResponseEntity<Map<String, Object>> sealedResponse() {
        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE,
                        clearSessionCookie().toString(),
                        clearCsrfCookie().toString())
                .body(statusBody(null, Instant.now()));
    }

    private Map<String, Object> statusBody(String sessionToken, Instant now) {
        InstallationBootstrapPlan plan = repository.find().orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        if (plan == null) {
            body.put("status", "UNINITIALIZED");
            body.put("phase", null);
            body.put("tenantDisplayName", null);
            body.put("identityDisplayName", null);
            body.put("username", null);
            body.put("consoleRedirectUri", trustedConsoleRedirectUri());
            body.put("setupSessionAuthenticated", sessions.verify(sessionToken, now));
            return body;
        }

        boolean sealed = plan.state() == InstallationBootstrapState.SEALED;
        body.put("status", sealed ? "SEALED" : "IN_PROGRESS");
        body.put("phase", plan.state().name());
        body.put("tenantDisplayName", plan.tenantDisplayName());
        body.put("identityDisplayName", plan.identityDisplayName());
        body.put("username", plan.principalKey());
        body.put("consoleRedirectUri", plan.consoleRedirectUri());
        body.put("setupSessionAuthenticated", !sealed && sessions.verify(sessionToken, now));
        return body;
    }

    private String trustedConsoleRedirectUri() {
        String value = properties.consoleRedirectUri();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("trusted Console redirect URI is not configured");
        }
        return value.trim();
    }

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String code,
            String message) {
        return noStore(status, Map.of("code", code, "message", message));
    }

    private static ResponseEntity<Map<String, Object>> noStore(
            HttpStatus status,
            Map<String, Object> body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    private static ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
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
        return ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
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

    public record SetupProofRequest(char[] setupSecret) {
    }

    public record InitializeRequest(
            String tenantDisplayName,
            String identityDisplayName,
            String username,
            char[] password) {
    }
}
