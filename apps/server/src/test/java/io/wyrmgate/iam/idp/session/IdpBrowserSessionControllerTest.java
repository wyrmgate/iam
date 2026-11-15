package io.wyrmgate.iam.idp.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdpBrowserSessionControllerTest {
    @Test
    void loginRouteIssuesCsrfAndLoginRejectsMissingOrMalformedProofBeforeVerification() {
        var login = mock(IdpInteractiveLoginService.class);
        var sessions = mock(IdpBrowserSessionService.class);
        var csrf = new IdpCsrfTokenCodec();
        var route = mock(ConsoleLoginRouteQuery.class);
        when(route.resolve()).thenReturn(Optional.of(new ConsoleLoginRouteQuery.Route("client", UUID.randomUUID())));
        var controller = new IdpBrowserSessionController(login, sessions, csrf, route);
        assertThat(controller.loginRoute().getHeaders().get("Set-Cookie")).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("__Host-wyrmgate_csrf=", "Secure", "SameSite=Strict", "Path=/"));
        char[] password = "password-example".toCharArray();
        assertThat(controller.login(new IdpBrowserSessionController.LoginRequest("alice", password), null, null, null)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(password).containsOnly((char) 0);
        assertThat(controller.login(new IdpBrowserSessionController.LoginRequest("alice", "secret".toCharArray()), "bad", "bad", null)
                .getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(login, sessions);
    }

    @Test
    void legacyPublicLoginSelectorsAreRejectedBeforeAuthentication() throws Exception {
        var login = mock(IdpInteractiveLoginService.class);
        var sessions = mock(IdpBrowserSessionService.class);
        var controller = new IdpBrowserSessionController(login, sessions, new IdpCsrfTokenCodec(), mock(ConsoleLoginRouteQuery.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"principalKey\":\"alice\",\"password\":\"test-password\",\"applicationTargetId\":\"foreign\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-password"))));
        verifyNoInteractions(login, sessions);
    }

    @Test
    void logoutCookieRequiresProofEvenIfAnotherAuthenticationTransportIsPresent() {
        var sessions = mock(IdpBrowserSessionService.class);
        var csrf = new IdpCsrfTokenCodec();
        var controller = new IdpBrowserSessionController(mock(IdpInteractiveLoginService.class), sessions, csrf, mock(ConsoleLoginRouteQuery.class));
        assertThat(controller.logout("opaque", null, null).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(sessions);
        String token = csrf.issue();
        assertThat(controller.logout("opaque", token, token).getStatusCode().value()).isEqualTo(204);
        verify(sessions).logout(eq("opaque"), any(java.time.Instant.class));
    }
}
