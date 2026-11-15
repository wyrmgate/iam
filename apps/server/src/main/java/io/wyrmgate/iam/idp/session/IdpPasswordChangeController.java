package io.wyrmgate.iam.idp.session;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.credential.application.LocalPasswordChangeService;
import io.wyrmgate.iam.idp.protocol.IdpAuthorizationController;
import io.wyrmgate.iam.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Dedicated first-party secret input boundary; never a generic Credential resource mutation. */
@RestController
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public final class IdpPasswordChangeController {
    private static final Logger LOG = LoggerFactory.getLogger(IdpPasswordChangeController.class);
    private final IdpBrowserSessionService sessions;
    private final LocalPasswordChangeService changes;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    public IdpPasswordChangeController(IdpBrowserSessionService sessions,
            LocalPasswordChangeService changes, SecurityAuditPort audit, IdGenerator ids) {
        this.sessions = sessions;
        this.changes = changes;
        this.audit = audit;
        this.ids = ids;
    }

    @PostMapping(path = "/api/auth/password/change", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> change(Authentication authentication,
            @CookieValue(name = IdpAuthorizationController.BROWSER_SESSION_COOKIE, required = false) String token,
            @RequestBody PasswordChangeRequest request) {
        char[] current = request.currentPassword() == null ? new char[0] : request.currentPassword();
        char[] replacement = request.newPassword() == null ? new char[0] : request.newPassword();
        UUID correlation = ids.nextId();
        Instant now = Instant.now();
        try {
            // The browser filter authenticates this route and checks the existing double-submit
            // CSRF contract. A bearer-authenticated actor cannot change a local password here.
            if (!(authentication instanceof PreAuthenticatedAuthenticationToken)
                    || !authentication.isAuthenticated() || token == null || token.isBlank()) {
                return error(HttpStatus.UNAUTHORIZED, "invalid_session", correlation);
            }
            var subject = sessions.resolvePasswordChangeSubject(token, now);
            if (subject.isEmpty() || !authentication.getName().equals(subject.get().principalId().toString())) {
                return error(HttpStatus.UNAUTHORIZED, "invalid_session", correlation);
            }
            var target = subject.orElseThrow();
            boolean changed;
            try {
                changed = changes.change(target.tenant(), target.identityId(), target.principalId(),
                        target.credentialId(), target.credentialRevision(), current, replacement, now).isPresent();
            } catch (RuntimeException unavailable) {
                record(target, now, correlation, AuditOutcome.FAILURE);
                return error(HttpStatus.SERVICE_UNAVAILABLE, "password_change_unavailable", correlation);
            }
            record(target, now, correlation, changed ? AuditOutcome.SUCCESS : AuditOutcome.DENIED);
            if (!changed) return error(HttpStatus.BAD_REQUEST, "password_change_rejected", correlation);
            // Exact old credential revision is revoked in the committed transaction.
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                    .header("X-Correlation-Id", correlation.toString())
                    .header(HttpHeaders.SET_COOKIE, IdpBrowserSessionController.clearSessionCookie().toString(),
                            IdpBrowserSessionController.clearCsrfCookie().toString()).build();
        } finally {
            Arrays.fill(current, (char) 0);
            Arrays.fill(replacement, (char) 0);
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidBody() {
        return error(HttpStatus.BAD_REQUEST, "password_change_rejected", ids.nextId());
    }

    private void record(IdpBrowserSessionService.PasswordChangeSubject subject, Instant now,
            UUID correlation, AuditOutcome outcome) {
        try {
            audit.append(subject.tenant(), new AuditRecordDraft(ids.nextId(), now, subject.identityId(),
                    "credential:change-password", "credential", subject.credentialId(), outcome, correlation, null));
        } catch (RuntimeException unavailable) {
            LOG.warn("Password change audit append failed; correlationId={} outcome={}", correlation, outcome);
        }
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, UUID correlation) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", correlation.toString())
                .body(Map.of("error", code));
    }

    public record PasswordChangeRequest(char[] currentPassword, char[] newPassword) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object ignored) {
            throw new IllegalArgumentException("unexpected password change field");
        }
        @Override public String toString() { return "PasswordChangeRequest[REDACTED]"; }
    }
}
