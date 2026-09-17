package io.wyrmgate.iam.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.platform.id.IdGenerator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

class SemanticAuthenticationEntryPointTest {

    @Test
    void authenticationFailureIsDataMinimizedAndCorrelated() throws Exception {
        UUID correlationId = UUID.fromString("0199d839-8c00-7000-8000-000000000004");
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.nextId()).thenReturn(correlationId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SemanticAuthenticationEntryPoint(ids).commence(
                new MockHttpServletRequest("GET", "/api/v1/identities"),
                response,
                new AuthenticationCredentialsNotFoundException("JWT library detail must not leak"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("authentication_required");
        assertThat(response.getContentAsString()).contains(correlationId.toString());
        assertThat(response.getContentAsString()).doesNotContain("JWT library detail must not leak");
    }
}
