package io.wyrmgate.iam.idp.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.credential.application.LocalPasswordChangeService;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialRotation;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

class IdpPasswordChangeControllerTest {
    @Test
    void usesOnlyExactServerSessionSubjectAndClearsSecretsAndCookies() {
        var sessions = mock(IdpBrowserSessionService.class);
        var changes = mock(LocalPasswordChangeService.class);
        var audit = mock(SecurityAuditPort.class);
        var tenant = new TenantContext(UUID.randomUUID());
        var actor = UUID.randomUUID();
        var principal = UUID.randomUUID();
        var credential = UUID.randomUUID();
        when(sessions.resolvePasswordChangeSubject(eq("opaque"), any(Instant.class)))
                .thenReturn(Optional.of(new IdpBrowserSessionService.PasswordChangeSubject(
                        tenant, actor, principal, credential, 3)));
        when(changes.change(eq(tenant), eq(actor), eq(principal), eq(credential), eq(3L),
                any(char[].class), any(char[].class), any(Instant.class)))
                .thenReturn(Optional.of(mock(CredentialRotation.class)));
        var controller = new IdpPasswordChangeController(sessions, changes, audit, UUID::randomUUID);
        char[] old = "old-password".toCharArray();
        char[] next = "new-password-example".toCharArray();
        var response = controller.change(new PreAuthenticatedAuthenticationToken(principal.toString(), null, List.of()),
                "opaque", new IdpPasswordChangeController.PasswordChangeRequest(old, next));
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().get("Set-Cookie")).hasSize(2);
        assertThat(old).containsOnly((char) 0);
        assertThat(next).containsOnly((char) 0);
        verify(audit).append(eq(tenant), argThat(d -> d.actorId().equals(actor)
                && d.resourceId().equals(credential) && d.actionType().equals("credential:change-password")));
    }

    @Test
    void nonBrowserAuthenticationCannotInvokeLocalPasswordChange() {
        var sessions = mock(IdpBrowserSessionService.class);
        var changes = mock(LocalPasswordChangeService.class);
        var controller = new IdpPasswordChangeController(sessions, changes, mock(SecurityAuditPort.class), UUID::randomUUID);
        var authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        char[] old = "old-password".toCharArray();
        char[] next = "new-password-example".toCharArray();
        assertThat(controller.change(authentication, "opaque",
                new IdpPasswordChangeController.PasswordChangeRequest(old, next)).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(sessions, changes);
        assertThat(old).containsOnly((char) 0);
        assertThat(next).containsOnly((char) 0);
    }
}
