package io.wyrmgate.iam.administration.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExternalAuthenticationSubjectTest {

    @Test
    void requiresExactAbsoluteIssuerAndNonblankSubject() {
        assertThatThrownBy(() -> new ExternalAuthenticationSubject("issuer", "subject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalAuthenticationSubject(" https://issuer.example", "subject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalAuthenticationSubject("https://issuer.example", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
